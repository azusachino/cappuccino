import Foundation
import Testing

@testable import CappuccinoCore

// Scripted WebSocket frames: the transport yields typed events in order, and a
// protocol violation ends the stream with its reason. No real sockets, no bridge.

final class FakeBridgeWebSocket: BridgeWebSocket, @unchecked Sendable {
  private let lock = NSLock()
  private var frames: [String?]
  private(set) var closed = false

  init(frames: [String?]) {
    self.frames = frames
  }

  func receiveText() async throws -> String? {
    popFrame()
  }

  func close() async {
    markClosed()
  }

  func closeImmediately() {
    markClosed()
  }

  private func popFrame() -> String? {
    lock.lock()
    defer { lock.unlock() }
    guard !frames.isEmpty else { return nil }
    return frames.removeFirst()
  }

  private func markClosed() {
    lock.lock()
    defer { lock.unlock() }
    closed = true
  }
}

struct FakeBridgeConnector: BridgeWebSocketConnecting {
  let socket: FakeBridgeWebSocket

  func connect(url: URL) async throws -> BridgeWebSocket {
    #expect(url.path.hasSuffix("/api/stream"))
    #expect(url.query?.contains("session=cap-spike-agent") == true)
    return socket
  }
}

@Suite struct WebSocketSchemeTests {
  @Test func httpMapsToWS() throws {
    let endpoint = try BridgeStream.endpointURL(
      from: URL(string: "http://127.0.0.1:7392")!, session: "s")
    #expect(endpoint.scheme == "ws")
    #expect(endpoint.host == "127.0.0.1")
    #expect(endpoint.port == 7392)
    #expect(endpoint.path == "/api/stream")
    #expect(endpoint.query?.contains("session=s") == true)
  }

  @Test func httpsMapsToWSS() throws {
    let endpoint = try BridgeStream.endpointURL(
      from: URL(string: "https://host.tailnet.ts.net")!, session: "cap-spike-agent")
    #expect(endpoint.scheme == "wss")
    #expect(endpoint.host == "host.tailnet.ts.net")
    #expect(endpoint.path == "/api/stream")
    #expect(endpoint.query?.contains("session=cap-spike-agent") == true)
  }

  @Test func unsupportedOrMissingSchemeThrowsProtocolError() {
    #expect(
      throws: BridgeError.protocolError("stream URL scheme must be http or https, got 'ftp'")
    ) {
      try BridgeStream.endpointURL(from: URL(string: "ftp://127.0.0.1:7392")!, session: nil)
    }
    #expect(throws: BridgeError.protocolError("stream URL is malformed")) {
      try BridgeStream.endpointURL(from: URL(string: "no-scheme")!, session: nil)
    }
  }
}

@Suite(.serialized) struct AgentEventsTests {
  private func collect(_ frames: [String?]) async -> (events: [AgentStreamEvent], failure: Error?) {
    let client = BridgeClient(session: URLSession(configuration: .ephemeral))
    var events: [AgentStreamEvent] = []
    do {
      for try await event in client.agentEvents(
        base: URL(string: "http://127.0.0.1:7392")!, session: "cap-spike-agent",
        connector: FakeBridgeConnector(socket: FakeBridgeWebSocket(frames: frames)))
      {
        events.append(event)
      }
      return (events, nil)
    } catch {
      return (events, error)
    }
  }

  @Test func yieldsEachFrameInOrderThenTheCleanCloseAsAFailure() async {
    let entry =
      #"{"event":"entries","entries":[{"id":"e1","seq":1,"kind":"output","text":"hi","complete":true}]}"#
    let (events, failure) = await collect([
      #"{"event":"stream_open","session_id":"cap-spike-agent","generation":0}"#, entry, nil,
    ])
    #expect(failure as? BridgeError == .unreachable("bridge closed the stream"))
    #expect(events.count == 2)
    #expect(events.first == .open(sessionID: "cap-spike-agent", generation: 0))
  }

  @Test func aMalformedFrameEndsTheStreamWithItsReason() async {
    let (events, failure) = await collect([
      #"{"event":"stream_open","session_id":"cap-spike-agent","generation":0}"#,
      #"{"event":"mystery"}"#,
    ])
    #expect(events.count == 1)
    #expect(failure as? BridgeError == .protocolError("Unknown stream event"))
  }

  @Test func aCleanCloseIsReportedAsAVisibleFailure() async {
    let (_, failure) = await collect([nil])
    #expect(failure as? BridgeError == .unreachable("bridge closed the stream"))
  }
}

@Suite struct URLSessionConnectorTests {
  /// The connector receives the stream endpoint itself; converting it again rejected `ws`
  /// and left the real app with no live stream.
  @Test func acceptsTheWebSocketEndpointAsGiven() async throws {
    let endpoint = try BridgeStream.endpointURL(
      from: URL(string: "http://127.0.0.1:9")!, session: "w1:p1")
    #expect(endpoint.scheme == "ws")
    let socket = try await URLSessionWebSocketConnector().connect(url: endpoint)
    socket.closeImmediately()
  }
}
