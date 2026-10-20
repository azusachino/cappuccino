import Foundation

/// Typed agent stream: a port of parseStreamEvent and the stream reducer in
/// core/BridgeProtocol.kt. The WebSocket connection itself stays in BridgeStream.swift.

let maximumWebSocketMessageBytes = 1024 * 1024
let maximumOutputRows = 2_000
let maximumOutputUTF8Bytes = 4 * 1024 * 1024

public struct StreamEntry: Equatable, Sendable {
  public let id: String
  public let seq: Int64
  public let kind: String
  public let text: String
  public let branch: String?
}

public enum AgentStreamEvent: Equatable, Sendable {
  case open(sessionID: String, generation: Int64)
  case entries([StreamEntry])
  case reset(generation: Int64)
  case error(code: String, message: String)
  case agentStatus(sessionID: String, state: String, detail: String?)
  case promptRequest(sessionID: String, prompt: PromptCard)
  case promptResolved(sessionID: String, promptID: String)
}

/// One output line for the transcript: an entry, or a marker that says output is missing.
public struct OutputRow: Equatable, Sendable {
  public let key: String
  public let text: String
  public let gap: Bool

  init(_ key: String, _ text: String, gap: Bool = false) {
    self.key = key
    self.text = text
    self.gap = gap
  }
}

/// The stream as the selected agent sees it. Entries are kept in sequence order and bounded.
public struct StreamState: Equatable, Sendable {
  public var sessionID: String?
  public var generation: Int64?
  public var entries: [StreamEntry] = []
  public var markers: [OutputRow] = []
  public var error: String?
  public var agentStatus: String?
  public var agentStatusDetail: String?
  public var pendingPrompt: PromptCard?

  public init(
    sessionID: String? = nil, generation: Int64? = nil, markers: [OutputRow] = []
  ) {
    self.sessionID = sessionID
    self.generation = generation
    self.markers = markers
  }

  /// Markers first, then entries, with a gap row wherever sequence numbers skip.
  public func rows() -> [OutputRow] {
    var result: [OutputRow] = []
    var previous: Int64?
    for entry in entries {
      if let last = previous, entry.seq > last + 1 {
        result.append(
          OutputRow(
            "gap:\(last):\(entry.seq)", "Output gap: \(entry.seq - last - 1) entries missing",
            gap: true))
      } else if previous == nil, entry.seq > 1 {
        result.append(
          OutputRow(
            "gap:start:\(entry.seq)", "Output begins at \(entry.seq); earlier output unavailable",
            gap: true))
      }
      result.append(OutputRow("\(generation.map(String.init) ?? "null"):\(entry.id)", entry.text))
      previous = entry.seq
    }
    return markers + result
  }
}

/// One frame of the agent stream. Frames that break the protocol throw, and the caller ends the stream.
public func parseStreamFrame(_ text: String) throws -> AgentStreamEvent {
  guard text.utf8.count <= maximumWebSocketMessageBytes else {
    throw BridgeError.protocolError("WebSocket message exceeds local limit")
  }
  return try parseStreamEvent(BridgeJSON.parse(Data(text.utf8)))
}

func parseStreamEvent(_ json: BridgeJSON) throws -> AgentStreamEvent {
  switch try json.string("event") {
  case "stream_open":
    return .open(sessionID: try locator(json), generation: try nonNegative(json, "generation"))
  case "entries":
    return .entries(try json.array("entries").map(parseEntry))
  case "stream_reset":
    return .reset(generation: try nonNegative(json, "generation"))
  case "error":
    return .error(code: try json.string("code"), message: try json.string("message"))
  case "agent_status":
    return .agentStatus(
      sessionID: try json.string("session_id"), state: try json.string("state"),
      detail: try json.optionalString("detail"))
  case "prompt_request":
    guard let prompt = json.optionalObject("prompt") else {
      throw BridgeError.protocolError("Missing prompt object")
    }
    return .promptRequest(
      sessionID: try json.string("session_id"), prompt: try parsePromptCard(prompt))
  case "prompt_resolved":
    return .promptResolved(
      sessionID: try json.string("session_id"), promptID: try json.string("prompt_id"))
  default:
    throw BridgeError.protocolError("Unknown stream event")
  }
}

private func parseEntry(_ row: BridgeJSON) throws -> StreamEntry {
  let id = try row.string("id")
  guard !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
    throw BridgeError.protocolError("Entry id is empty")
  }
  let seq = try row.long("seq")
  guard seq > 0 else { throw BridgeError.protocolError("Invalid sequence") }
  guard try row.boolean("complete") else {
    throw BridgeError.protocolError("Incomplete stream entry")
  }
  return StreamEntry(
    id: id, seq: seq, kind: try row.string("kind"), text: try row.string("text"),
    branch: try row.optionalString("branch"))
}

private func locator(_ json: BridgeJSON) throws -> String {
  let session = try json.string("session_id")
  guard !session.isEmpty else { throw BridgeError.protocolError("Stream locator is empty") }
  return session
}

private func nonNegative(_ json: BridgeJSON, _ key: String) throws -> Int64 {
  let value = try json.long(key)
  guard value >= 0 else { throw BridgeError.protocolError("Invalid \(key)") }
  return value
}

/// Applies one event. Events for another session are ignored, except the ones that are
/// protocol errors for this stream. Malformed ordering throws.
public func reduceStream(
  _ state: StreamState, _ event: AgentStreamEvent, selectedSession: String
) throws -> StreamState {
  switch event {
  case .open(let session, let generation):
    guard session == selectedSession else {
      throw BridgeError.protocolError("Stream locator mismatch")
    }
    guard state.generation != nil else {
      return StreamState(sessionID: session, generation: generation)
    }
    // A reconnect keeps the owner informed that earlier output may be missing.
    return StreamState(
      sessionID: session, generation: generation,
      markers: [
        OutputRow(
          "reconnect:\(generation)",
          "Recent output window reopened; earlier output may be missing", gap: true)
      ])
  case .entries(let incoming):
    guard state.generation != nil, state.sessionID == selectedSession else {
      throw BridgeError.protocolError("Entries arrived before stream_open")
    }
    var byID = Dictionary(uniqueKeysWithValues: state.entries.map { ($0.id, $0) })
    var bySeq = Dictionary(uniqueKeysWithValues: state.entries.map { ($0.seq, $0) })
    for entry in incoming {
      if let known = byID[entry.id], known != entry {
        throw BridgeError.protocolError("Conflicting entry id")
      }
      if let known = bySeq[entry.seq], known != entry {
        throw BridgeError.protocolError("Conflicting entry sequence")
      }
      byID[entry.id] = entry
      bySeq[entry.seq] = entry
    }
    let ordered = byID.values.sorted { $0.seq < $1.seq }
    var retained = Array(ordered.suffix(maximumOutputRows))
    var bytes = retained.reduce(0) { $0 + $1.text.utf8.count }
    while bytes > maximumOutputUTF8Bytes, let first = retained.first {
      bytes -= first.text.utf8.count
      retained.removeFirst()
    }
    var next = state
    next.entries = retained
    if retained.count < ordered.count {
      next.markers = [
        OutputRow(
          "truncated:\(state.generation ?? 0)", "Earlier output truncated locally", gap: true)
      ]
    }
    return next
  case .reset(let generation):
    guard let current = state.generation else {
      throw BridgeError.protocolError("Reset arrived before stream_open")
    }
    if generation < current { throw BridgeError.protocolError("Regressive stream reset") }
    if generation == current { return state }
    return StreamState(
      sessionID: selectedSession, generation: generation,
      markers: [
        OutputRow(
          "reset:\(generation)", "Output window reset; earlier output unavailable", gap: true)
      ])
  case .error(let code, let message):
    var next = state
    next.error = "\(code): \(message)"
    return next
  case .agentStatus(let session, let status, let detail):
    guard session == selectedSession else { return state }
    var next = state
    next.agentStatus = status
    next.agentStatusDetail = detail
    return next
  case .promptRequest(let session, let prompt):
    guard session == selectedSession else { return state }
    var next = state
    next.pendingPrompt = prompt
    return next
  case .promptResolved(let session, let promptID):
    guard session == selectedSession, state.pendingPrompt?.promptID == promptID else {
      return state
    }
    var next = state
    next.pendingPrompt = nil
    return next
  }
}

/// Reconnect policy for the stream: the first attempt plus four bounded retries, which
/// resets for each new attachment.
public struct StreamRetryBudget: Sendable {
  private static let delays: [Int] = [1_000, 2_000, 4_000, 8_000]
  private var used = 0

  public init() {}

  public mutating func nextDelayMillis() -> Int? {
    guard used < Self.delays.count else { return nil }
    defer { used += 1 }
    return Self.delays[used]
  }
}

extension BridgeClient {
  /// The typed agent stream over the same socket as `stream(base:session:connector:)`.
  /// Frames are parsed by `parseStreamFrame`; a protocol violation ends the stream.
  public func agentEvents(
    base url: URL,
    session: String,
    connector: BridgeWebSocketConnecting = URLSessionWebSocketConnector()
  ) -> AsyncThrowingStream<AgentStreamEvent, Error> {
    AsyncThrowingStream(bufferingPolicy: .bufferingOldest(64)) { continuation in
      let task = Task {
        do {
          let endpoint = try BridgeStream.endpointURL(from: url, session: session)
          let socket = try await connector.connect(url: endpoint)
          defer { socket.closeImmediately() }
          try await withTaskCancellationHandler {
            while true {
              try Task.checkCancellation()
              guard let text = try await socket.receiveText() else {
                throw BridgeError.unreachable("bridge closed the stream")
              }
              let event = try parseStreamFrame(text)
              // Error envelopes are terminal for this socket, even if the model retries.
              if case .error = event { socket.closeImmediately() }
              switch continuation.yield(event) {
              case .dropped: throw BridgeError.streamOverflow
              case .terminated: return
              case .enqueued: break
              @unknown default: throw BridgeError.protocolError("Unknown stream buffer result")
              }
              if case .error = event {
                continuation.finish()
                return
              }
            }
          } onCancel: {
            socket.closeImmediately()
          }
        } catch {
          continuation.finish(throwing: error)
        }
      }
      continuation.onTermination = { _ in task.cancel() }
    }
  }
}
