import Foundation

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

  /// `url` is the stream endpoint itself (`BridgeStream.endpointURL`), already ws, wss or
  /// `cappiroh`. An iroh endpoint carries its WebSocket over an iroh stream instead.
  public func connect(url: URL) async throws -> BridgeWebSocket {
    if url.scheme?.lowercased() == irohURLScheme {
      return try await IrohWebSocketConnector().connect(url: url)
    }
    let task = URLSession.shared.webSocketTask(with: url)
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
      throw BridgeError.protocolError("stream URL is malformed")
    }
    switch scheme {
    case "http":
      components.scheme = "ws"
    case "https":
      components.scheme = "wss"
    case irohURLScheme:
      // An iroh machine keeps its own scheme; the connector carries the WebSocket over iroh.
      break
    default:
      throw BridgeError.protocolError(
        "stream URL scheme must be http or https, got '\(scheme)'")
    }
    components.path = "/api/stream"
    if let session {
      components.queryItems = [URLQueryItem(name: "session", value: session)]
    }
    guard let endpoint = components.url else {
      throw BridgeError.protocolError("stream URL is malformed")
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
          if isBridgeTLSFailure(error) {
            continuation.resume(throwing: error)
            return
          }
          continuation.resume(
            throwing: BridgeError.unreachable(
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
