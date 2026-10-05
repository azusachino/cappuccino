import DaemonCore

import Foundation

// Cappuccino daemon wire protocol v0 (provisional, slice A):
// newline-delimited JSON over TCP, bound to 127.0.0.1 (or a tailnet address).
//
//   client -> daemon:
//     {"op":"pair","token":"…"}
//     {"op":"list","token":"…"}
//     {"op":"stream","token":"…","session_id":"cap-spike-agent","after_seq":0}
//     {"op":"ping","token":"…"}
//   daemon -> client:
//     {"event":"paired","machine_id":"…","protocol":1}
//     {"event":"error","code":"unauthorized|bad_request|not_found|internal","message":"…"}
//     {"event":"agents","machine_id":"…","agents":[AgentInfo…]}
//     {"event":"stream_open","session_id":"…","generation":N}
//     {"event":"stream_reset","generation":N}          // active branch changed
//     {"event":"entries","entries":[TranscriptEntry…]} // replay + live appends
//
// Authorization: the token must accompany every op. A wrong token yields a
// visible `unauthorized` error and closes the connection — never a silent
// retry. Idempotency: clients track the last `seq` they rendered and reconnect
// with `after_seq`; replay is deduped by seq, so reconnect never duplicates.

public enum DaemonError: Error, CustomStringConvertible {
  case unauthorized
  case badRequest(String)
  case notFound(String)
  case internalError(String)

  public var code: String {
    switch self {
    case .unauthorized: return "unauthorized"
    case .badRequest: return "bad_request"
    case .notFound: return "not_found"
    case .internalError: return "internal"
    }
  }

  public var description: String {
    switch self {
    case .unauthorized: return "pairing token rejected"
    case .badRequest(let m): return m
    case .notFound(let m): return m
    case .internalError(let m): return m
    }
  }
}

/// Shared, thread-safe per-session stream state plus the poll loop that turns
/// pane reads into confirmed entries.
public final class DaemonState: @unchecked Sendable {
  public let pairing: PairingStore
  public let catalog: AgentCatalog
  private let lock = NSLock()
  private var streams: [String: SessionStream] = [:]
  private let condition = NSCondition()
  private var generationSeen: [String: Int] = [:]

  public struct SessionStream {
    let ring: StreamRing
    let paneId: String
  }

  public init(pairing: PairingStore, catalog: AgentCatalog) {
    self.pairing = pairing
    self.catalog = catalog
  }

  public func hasStream(_ sessionId: String) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return streams[sessionId] != nil
  }

  @discardableResult
  public func ensureStream(sessionId: String) throws -> StreamRing {
    lock.lock()
    if let existing = streams[sessionId] {
      lock.unlock()
      return existing.ring
    }
    lock.unlock()
    let agents = try catalog.listAgents()
    guard let agent = agents.first(where: { $0.sessionId == sessionId }) else {
      throw DaemonError.notFound("no agent with session_id \(sessionId) on this machine")
    }
    lock.lock()
    defer { lock.unlock() }
    if let existing = streams[sessionId] { return existing.ring }
    let stream = SessionStream(ring: StreamRing(), paneId: agent.paneId)
    streams[sessionId] = stream
    return stream.ring
  }

  public func paneId(sessionId: String) -> String? {
    lock.lock()
    defer { lock.unlock() }
    return streams[sessionId]?.paneId
  }

  public func ringFor(_ sessionId: String) -> StreamRing? {
    lock.lock()
    defer { lock.unlock() }
    return streams[sessionId]?.ring
  }

  /// One poll of every live stream: read pane text, confirm completed lines
  /// into rings, wake stream waiters. Errors on one session do not stop others.
  public func poll(client: HerdrClient) {
    lock.lock()
    let snapshot = streams
    lock.unlock()
    for (sessionId, stream) in snapshot {
      do {
        let read = try client.request(
          method: "pane.read",
          params: [
            "pane_id": .string(stream.paneId),
            "source": .string("recent_unwrapped"),
            "lines": .number(2000),
          ])
        let text = read["read"]?["text"]?.stringValue ?? ""
        var lines = text.components(separatedBy: "\n")
        while let last = lines.last, last.isEmpty { lines.removeLast() }
        let agent = try? catalog.listAgents().first { $0.sessionId == sessionId }
        // The trailing line is only confirmed when the agent is idle; while it
        // is working the trailing line may still be growing.
        let confirmTrailing = !(agent?.working ?? true)
        _ = stream.ring.ingest(
          snapshot: PaneSnapshot(lines: lines, working: agent?.working ?? true),
          branch: agent?.activeBranch,
          confirmTrailing: confirmTrailing,
          trace: sessionId)
        lock.lock()
        condition.broadcast()
        lock.unlock()
      } catch {
        continue
      }
    }
  }

  /// Blocks until the next poll broadcast or timeout.
  public func awaitChange(timeout: TimeInterval) {
    condition.lock()
    _ = condition.wait(until: Date().addingTimeInterval(timeout))
    condition.unlock()
  }

  public func wakeWaiters() {
    lock.lock()
    condition.broadcast()
    lock.unlock()
  }

  /// Current generation for a session (0 when unknown).
  public func generation(sessionId: String) -> Int {
    lock.lock()
    defer { lock.unlock() }
    return streams[sessionId]?.ring.generation ?? 0
  }
}
