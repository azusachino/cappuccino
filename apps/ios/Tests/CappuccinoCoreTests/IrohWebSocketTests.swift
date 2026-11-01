import Foundation
import Testing

@testable import CappuccinoCore

/// A stream that records what it is written and replays a scripted reply.
private actor ScriptedWebSocketStream: IrohStreaming {
  private var reply: [UInt8]
  private(set) var written = Data()

  init(reply: [UInt8]) {
    self.reply = reply
  }

  func write(_ bytes: Data) { written.append(bytes) }
  func finish() {}
  func read(max: UInt32) -> Data {
    let take = min(Int(max), reply.count)
    let chunk = Data(reply.prefix(take))
    reply.removeFirst(take)
    return chunk
  }
  func stopReading() {}
}

@Suite struct IrohWebSocketTests {
  // The RFC 6455 section 1.3 example key and its accept value.
  private let key = "dGhlIHNhbXBsZSBub25jZQ=="
  private let accept = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="

  private func handshake(status: String = "101 Switching Protocols", accept: String) -> [UInt8] {
    Array(
      "HTTP/1.1 \(status)\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: \(accept)\r\n\r\n"
        .utf8)
  }

  @Test func acceptValueMatchesTheRFCExample() {
    #expect(webSocketAccept(forKey: key) == accept)
  }

  @Test func clientFramesAreMasked() {
    let frame = encodeClientFrame(opcode: 0x1, payload: Data("Hi".utf8), maskKey: [1, 2, 3, 4])
    #expect(Array(frame) == [0x81, 0x82, 1, 2, 3, 4, UInt8(ascii: "H") ^ 1, UInt8(ascii: "i") ^ 2])
  }

  @Test func serverFramesDecodeWithShortAndExtendedLengths() throws {
    var short: [UInt8] = [0x81, 0x05] + Array("Hello".utf8)
    #expect(
      try decodeServerFrame(&short)
        == ServerFrame(fin: true, opcode: 0x1, payload: Data("Hello".utf8)))
    #expect(short.isEmpty)

    let body = [UInt8](repeating: 0x41, count: 300)
    var extended: [UInt8] = [0x81, 126, 0x01, 0x2C] + body
    let frame = try decodeServerFrame(&extended)
    #expect(frame?.payload.count == 300)
  }

  @Test func anIncompleteFrameWaitsAndAMaskedServerFrameFails() throws {
    var partial: [UInt8] = [0x81, 0x05, 0x48]
    #expect(try decodeServerFrame(&partial) == nil)
    #expect(partial.count == 3, "nothing is consumed until the frame is whole")

    var masked: [UInt8] = [0x81, 0x85, 0, 0, 0, 0, 0x48, 0x65, 0x6C, 0x6C, 0x6F]
    #expect(throws: WebSocketWireError.maskedServerFrame) { try decodeServerFrame(&masked) }
  }

  @Test func theHandshakeUpgradesAndTheFirstFrameIsDelivered() async throws {
    let reply = handshake(accept: accept) + [0x81, 0x02] + Array("hi".utf8)
    let stream = ScriptedWebSocketStream(reply: reply)
    let connector = IrohWebSocketConnector(open: { _ in stream }, key: { key })

    let socket = try await connector.connect(
      url: URL(string: "cappiroh://peer/api/stream?session=s1")!)
    #expect(try await socket.receiveText() == "hi")

    let request = String(decoding: await stream.written, as: UTF8.self)
    #expect(request.hasPrefix("GET /api/stream?session=s1 HTTP/1.1\r\nHost: peer\r\n"))
    #expect(request.contains("Sec-WebSocket-Key: \(key)\r\n"))
  }

  @Test func aWrongAcceptOrAnotherStatusStopsTheUpgrade() async {
    let wrong = ScriptedWebSocketStream(reply: handshake(accept: "AAAAAAAAAAAAAAAAAAAAAAAAAAA="))
    let badAccept = IrohWebSocketConnector(open: { _ in wrong }, key: { key })
    await #expect(throws: WebSocketWireError.badAccept) {
      _ = try await badAccept.connect(url: URL(string: "cappiroh://peer/api/stream")!)
    }

    let refused = ScriptedWebSocketStream(
      reply: handshake(status: "401 Unauthorized", accept: accept))
    let rejected = IrohWebSocketConnector(open: { _ in refused }, key: { key })
    await #expect(throws: WebSocketWireError.handshakeRejected(401)) {
      _ = try await rejected.connect(url: URL(string: "cappiroh://peer/api/stream")!)
    }
  }
}
