import Foundation

// WebSocket stream client for the bridge's /api/stream. Events are parsed
// into typed outputs and passed through a reassembler that enforces the
// reconciliation contract client-side: entry ids are idempotent, so a
// duplicate delivery is suppressed rather than rendered twice.

public struct BridgeTranscriptTool: Equatable, Sendable, Decodable {
  public let name: String
  public let args: [String: String]?
  public let detail: String?

  public init(name: String, args: [String: String]?, detail: String?) {
    self.name = name
    self.args = args
    self.detail = detail
  }
}

public struct BridgeTranscriptEntry: Equatable, Sendable, Decodable {
  public let seq: Int?
  public let id: String
  public let kind: String
  public let text: String
  public let complete: Bool
  public let tool: BridgeTranscriptTool?

  public init(
    seq: Int?, id: String, kind: String, text: String, complete: Bool,
    tool: BridgeTranscriptTool? = nil
  ) {
    self.seq = seq
    self.id = id
    self.kind = kind
    self.text = text
    self.complete = complete
    self.tool = tool
  }
}

public struct BridgePromptOption: Equatable, Sendable, Decodable {
  public let id: String
  public let label: String
  public let description: String?

  public init(id: String, label: String, description: String? = nil) {
    self.id = id
    self.label = label
    self.description = description
  }
}

public struct BridgePromptCard: Equatable, Sendable, Decodable {
  public let promptID: String
  public let type: String
  public let title: String
  public let message: String?
  public let toolName: String?
  public let command: String?
  public let options: [BridgePromptOption]
  public let selectedIndex: Int

  public init(
    promptID: String,
    type: String,
    title: String,
    message: String? = nil,
    toolName: String? = nil,
    command: String? = nil,
    options: [BridgePromptOption] = [],
    selectedIndex: Int = 0
  ) {
    self.promptID = promptID
    self.type = type
    self.title = title
    self.message = message
    self.toolName = toolName
    self.command = command
    self.options = options
    self.selectedIndex = selectedIndex
  }
}

public enum BridgeStreamOutput: Equatable, Sendable {
  case open(generation: Int)
  case entries([BridgeTranscriptEntry])
  case reset(generation: Int)
  case agentStatus(sessionID: String, state: String, detail: String?)
  case promptRequest(sessionID: String, prompt: BridgePromptCard)
  case promptResolved(sessionID: String, promptID: String)
}

enum BridgeStreamEvent: Equatable {
  case open(generation: Int)
  case entries([BridgeTranscriptEntry])
  case reset(generation: Int)
  case agentStatus(sessionID: String, state: String, detail: String?)
  case promptRequest(sessionID: String, prompt: BridgePromptCard)
  case promptResolved(sessionID: String, promptID: String)
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
    case "agent_status":
      let session = object["session_id"] as? String ?? ""
      let state = object["state"] as? String ?? "idle"
      let detail = object["detail"] as? String
      return .agentStatus(sessionID: session, state: state, detail: detail)
    case "prompt_request":
      let session = object["session_id"] as? String ?? ""
      guard let rawPrompt = object["prompt"] as? [String: Any] else {
        throw DaemonClientError.protocolError("prompt_request missing prompt object")
      }
      let rawOptions = rawPrompt["options"] as? [[String: Any]] ?? []
      let options = rawOptions.map { opt in
        BridgePromptOption(
          id: opt["id"] as? String ?? "",
          label: opt["label"] as? String ?? "",
          description: opt["description"] as? String
        )
      }
      let card = BridgePromptCard(
        promptID: rawPrompt["prompt_id"] as? String ?? "",
        type: rawPrompt["type"] as? String ?? "",
        title: rawPrompt["title"] as? String ?? "",
        message: rawPrompt["message"] as? String,
        toolName: rawPrompt["tool_name"] as? String,
        command: rawPrompt["command"] as? String,
        options: options,
        selectedIndex: rawPrompt["selected_index"] as? Int ?? 0
      )
      return .promptRequest(sessionID: session, prompt: card)
    case "prompt_resolved":
      let session = object["session_id"] as? String ?? ""
      let promptID = object["prompt_id"] as? String ?? ""
      return .promptResolved(sessionID: session, promptID: promptID)
    case "entries":
      let raw = object["entries"] as? [[String: Any]] ?? []
      let parsed = raw.map { entry -> BridgeTranscriptEntry in
        var tool: BridgeTranscriptTool?
        if let rawTool = entry["tool"] as? [String: Any],
          let name = rawTool["name"] as? String
        {
          let args = rawTool["args"] as? [String: String]
          tool = BridgeTranscriptTool(
            name: name, args: args, detail: rawTool["detail"] as? String)
        }
        return BridgeTranscriptEntry(
          seq: entry["seq"] as? Int,
          id: entry["id"] as? String ?? "",
          kind: entry["kind"] as? String ?? "output",
          text: entry["text"] as? String ?? "",
          complete: entry["complete"] as? Bool ?? true,
          tool: tool
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
  /// Graceful async close.
  func close() async
  /// Deterministic synchronous close for error/cleanup paths: the closed
  /// state must be observable the moment this returns, before an error
  /// propagates to the consumer (defer-ordered, never a detached Task).
  func closeImmediately()
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
    closeImmediately()
  }

  /// URLSession task cancellation is synchronous: after cancel returns, the
  /// task is torn down and receive loops observe the failure.
  public func closeImmediately() {
    task.cancel(with: .goingAway, reason: nil)
  }
}

extension BridgeClient {
  /// Follows one agent's pane-line stream. Failures surface as thrown errors
  /// in the stream (unauthorized/unreachable/protocol); the consumer owns
  /// visible display and any later reconnect policy.
  public func stream(
    base url: URL,
    session: String,
    connector: BridgeWebSocketConnecting = URLSessionWebSocketConnector()
  ) -> AsyncThrowingStream<BridgeStreamOutput, Error> {
    AsyncThrowingStream { continuation in
      let task = Task {
        var reassembler = TranscriptReassembler()
        do {
          let endpoint = try BridgeStream.endpointURL(from: url, session: session)
          let socket = try await connector.connect(url: endpoint)
          // Deterministic resource hygiene: closed before any error reaches
          // the consumer, on every path out of the receive loop (nil frame,
          // error event, receive failure, cancellation).
          defer { socket.closeImmediately() }
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
            case .agentStatus(let sessionID, let state, let detail):
              continuation.yield(.agentStatus(sessionID: sessionID, state: state, detail: detail))
            case .promptRequest(let sessionID, let prompt):
              continuation.yield(.promptRequest(sessionID: sessionID, prompt: prompt))
            case .promptResolved(let sessionID, let promptID):
              continuation.yield(.promptResolved(sessionID: sessionID, promptID: promptID))
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
