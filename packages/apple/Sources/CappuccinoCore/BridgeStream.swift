import Foundation

// WebSocket stream client for the bridge's /api/stream. Events are parsed
// into typed outputs and passed through a reassembler that enforces the
// reconciliation contract client-side: entry ids are idempotent, so a
// duplicate delivery is suppressed rather than rendered twice.

public struct BridgeTranscriptEntry: Equatable, Sendable, Decodable {
  public let seq: Int?
  public let id: String
  public let kind: String
  public let text: String
  public let complete: Bool

  public init(seq: Int?, id: String, kind: String, text: String, complete: Bool) {
    self.seq = seq
    self.id = id
    self.kind = kind
    self.text = text
    self.complete = complete
  }
}

public enum BridgeStreamOutput: Equatable, Sendable {
  case open(generation: Int)
  case entries([BridgeTranscriptEntry])
  case reset(generation: Int)
}

enum BridgeStreamEvent: Equatable {
  case open(generation: Int)
  case entries([BridgeTranscriptEntry])
  case reset(generation: Int)
  case error(DaemonClientError)

  /// Parses one bridge stream event (a single JSON object).
  static func parse(_ data: Data) throws -> BridgeStreamEvent {
    guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw DaemonClientError.protocolError("stream event is not a JSON object")
    }
    switch object["event"] as? String {
    case "stream_open":
      return .open(generation: object["generation"] as? Int ?? 0)
    case "stream_reset":
      return .reset(generation: object["generation"] as? Int ?? 0)
    case "entries":
      let raw = object["entries"] as? [[String: Any]] ?? []
      let parsed = raw.map { entry -> BridgeTranscriptEntry in
        BridgeTranscriptEntry(
          seq: entry["seq"] as? Int,
          id: entry["id"] as? String ?? "",
          kind: entry["kind"] as? String ?? "output",
          text: entry["text"] as? String ?? "",
          complete: entry["complete"] as? Bool ?? true
        )
      }
      return .entries(parsed)
    case "error":
      if object["code"] as? String == "unauthorized" {
        return .error(.unauthorized)
      }
      return .error(.protocolError(object["message"] as? String ?? "stream error"))
    default:
      throw DaemonClientError.protocolError("unknown stream event")
    }
  }
}

/// Client-side reconciliation: the bridge already dedupes and orders, but the
/// contract makes idempotency end-to-end — an entry id delivered twice is
/// rendered once, here or on any future transport.
public struct TranscriptReassembler: Sendable {
  private var seenIDs = Set<String>()

  public init() {}

  /// Filters an event's entries down to ids not delivered before.
  public mutating func newEntries(from entries: [BridgeTranscriptEntry])
    -> [BridgeTranscriptEntry]
  {
    var fresh: [BridgeTranscriptEntry] = []
    for entry in entries {
      guard !seenIDs.contains(entry.id) else { continue }
      seenIDs.insert(entry.id)
      fresh.append(entry)
    }
    return fresh
  }
}

/// Transport seam for the stream so tests script frames instead of sockets.
public protocol BridgeWebSocket: Sendable {
  /// Next text frame from the bridge (nil = the socket closed cleanly).
  func receiveText() async throws -> String?
  func close() async
}

public protocol BridgeWebSocketConnecting: Sendable {
  func connect(url: URL) async throws -> BridgeWebSocket
}

public struct URLSessionWebSocketConnector: BridgeWebSocketConnecting {
  public init() {}

  public func connect(url: URL) async throws -> BridgeWebSocket {
    let wsURL = try BridgeStream.endpointURL(from: url, session: nil)
    let task = URLSession.shared.webSocketTask(with: wsURL)
    task.resume()
    return URLSessionWebSocketConnection(task: task)
  }
}

public enum BridgeStream {
  /// Maps the machine's HTTP(S) base URL + session to the stream endpoint,
  /// deriving the WebSocket scheme from the transport security: http→ws,
  /// https→wss. Any other (or missing) scheme is a configuration mistake and
  /// throws — a TLS tailnet endpoint must never be silently downgraded.
  public static func endpointURL(from machineURL: URL, session: String?) throws -> URL {
    guard var components = URLComponents(url: machineURL, resolvingAgainstBaseURL: false),
      let scheme = components.scheme?.lowercased()
    else {
      throw DaemonClientError.protocolError("stream URL is malformed")
    }
    switch scheme {
    case "http":
      components.scheme = "ws"
    case "https":
      components.scheme = "wss"
    default:
      throw DaemonClientError.protocolError(
        "stream URL scheme must be http or https, got '\(scheme)'")
    }
    components.path = "/api/stream"
    if let session {
      components.queryItems = [URLQueryItem(name: "session", value: session)]
    }
    guard let endpoint = components.url else {
      throw DaemonClientError.protocolError("stream URL is malformed")
    }
    return endpoint
  }
}

public final class URLSessionWebSocketConnection: BridgeWebSocket, @unchecked Sendable {
  private let task: URLSessionWebSocketTask

  init(task: URLSessionWebSocketTask) {
    self.task = task
  }

  public func receiveText() async throws -> String? {
    try await withCheckedThrowingContinuation { continuation in
      task.receive { result in
        switch result {
        case .success(.string(let text)):
          continuation.resume(returning: text)
        case .success:
          // Binary or ping frames are not part of this protocol; keep waiting
          // is impossible from here, so treat as a clean end.
          continuation.resume(returning: nil)
        case .failure(let error):
          continuation.resume(
            throwing: DaemonClientError.unreachable(
              "stream receive failed: \(error.localizedDescription)"))
        }
      }
    }
  }

  public func close() async {
    task.cancel(with: .goingAway, reason: nil)
  }
}

extension BridgeClient {
  /// Follows one agent's pane-line stream. Failures surface as thrown errors
  /// in the stream (unauthorized/unreachable/protocol); the consumer owns
  /// visible display and any later reconnect policy.
  public func stream(
    base url: URL, session: String, connector: BridgeWebSocketConnecting
  ) -> AsyncThrowingStream<BridgeStreamOutput, Error> {
    AsyncThrowingStream { continuation in
      let task = Task {
        var reassembler = TranscriptReassembler()
        do {
          let endpoint = try BridgeStream.endpointURL(from: url, session: session)
          let socket = try await connector.connect(url: endpoint)
          defer { Task { await socket.close() } }
          while true {
            guard let text = try await socket.receiveText() else {
              throw DaemonClientError.unreachable("daemon closed the stream")
            }
            let frame = text.data(using: .utf8) ?? Data()
            switch try BridgeStreamEvent.parse(frame) {
            case .open(let generation):
              continuation.yield(.open(generation: generation))
            case .entries(let entries):
              let fresh = reassembler.newEntries(from: entries)
              if !fresh.isEmpty {
                continuation.yield(.entries(fresh))
              }
            case .reset(let generation):
              reassembler = TranscriptReassembler()
              continuation.yield(.reset(generation: generation))
            case .error(let error):
              throw error
            }
          }
        } catch {
          continuation.finish(throwing: error)
        }
      }
      continuation.onTermination = { _ in
        task.cancel()
      }
    }
  }
}
