import Foundation
import Testing

@testable import CappuccinoCore

/// A stream that records what was written and replays a scripted reply.
private actor ScriptedStream: IrohStreaming {
  private var reply: [Data]
  private(set) var written = Data()
  private(set) var finished = false

  init(reply: [String]) {
    self.reply = reply.map { Data($0.utf8) }
  }

  func write(_ bytes: Data) { written.append(bytes) }
  func finish() { finished = true }
  func read(max: UInt32) -> Data { reply.isEmpty ? Data() : reply.removeFirst() }
  func stopReading() {}
}

@Suite struct IrohPairingRedeemTests {
  private let pairing = IrohPairing(
    endpoint: IrohEndpointAddr(endpointID: "abc", relayURL: nil, directAddrs: []),
    token: "one-time-secret-token", tailnetURL: nil)

  private func redeem(_ stream: ScriptedStream) async throws {
    try await redeemIrohPairing(pairing) { addr, alpn in
      #expect(addr == pairing.endpoint)
      #expect(alpn == irohPairALPN)
      return stream
    }
  }

  @Test func presentsTheTokenAndAcceptsOK() async throws {
    let stream = ScriptedStream(reply: ["OK\n"])
    try await redeem(stream)
    #expect(await stream.written == Data("one-time-secret-token".utf8))
    #expect(await stream.finished)
  }

  @Test func anERRReplyIsRejected() async {
    let stream = ScriptedStream(reply: ["ERR\n"])
    await #expect(throws: IrohPairingRedeemError.rejected) { try await self.redeem(stream) }
  }

  @Test func anyOtherReplyFails() async {
    let stream = ScriptedStream(reply: ["OK"])
    await #expect(throws: IrohPairingRedeemError.unexpectedReply) { try await self.redeem(stream) }
  }

  @Test func aReplyLongerThanTheLimitFails() async {
    let stream = ScriptedStream(reply: Array(repeating: "OK\n", count: 6))
    await #expect(throws: IrohPairingRedeemError.replyTooLong) { try await self.redeem(stream) }
  }

  @Test func theTokenNeverAppearsInAnError() async {
    let stream = ScriptedStream(reply: ["ERR\n"])
    do {
      try await redeem(stream)
      Issue.record("ERR must throw")
    } catch {
      #expect(!String(describing: error).contains("one-time-secret-token"))
    }
  }
}
