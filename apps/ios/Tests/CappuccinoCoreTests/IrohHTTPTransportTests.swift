import Foundation
import Testing

@testable import CappuccinoCore

/// A stream that records the request it receives and replays a scripted response.
private actor ReplyingStream: IrohStreaming {
  private var reply: [Data]
  private(set) var request = Data()

  init(reply: String) {
    self.reply = reply.isEmpty ? [] : [Data(reply.utf8)]
  }

  func write(_ bytes: Data) { request.append(bytes) }
  func finish() {}
  func read(max: UInt32) -> Data { reply.isEmpty ? Data() : reply.removeFirst() }
  func stopReading() {}
}

@Suite(.serialized) struct IrohHTTPTransportTests {
  private func peer(_ id: String) -> IrohEndpointAddr {
    IrohEndpointAddr(endpointID: id, relayURL: nil, directAddrs: [])
  }

  private func useStream(_ stream: ReplyingStream) {
    IrohHub.shared.setOpener { addr, alpn in
      #expect(alpn == irohBridgeALPN)
      #expect(addr.endpointID == "peer-ok")
      return stream
    }
  }

  @Test func aGetRunsOverTheStreamAndReturnsTheBridgeReply() async throws {
    IrohHub.shared.register(peer("peer-ok"))
    let stream = ReplyingStream(
      reply:
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 11\r\n\r\n{\"ok\":true}"
    )
    useStream(stream)

    let url = URL(string: "cappiroh://peer-ok/v1/session?verbose=1")!
    let (data, response) = try await BridgeSession.shared.data(from: url)
    let http = try #require(response as? HTTPURLResponse)
    #expect(http.statusCode == 200)
    #expect(http.value(forHTTPHeaderField: "Content-Type") == "application/json")
    #expect(String(decoding: data, as: UTF8.self) == "{\"ok\":true}")

    let sent = String(decoding: await stream.request, as: UTF8.self)
    #expect(sent.hasPrefix("GET /v1/session?verbose=1 HTTP/1.1\r\nHost: peer-ok\r\n"))
  }

  @Test func anUnknownPeerFailsBeforeAnyStreamOpens() async {
    IrohHub.shared.setOpener { _, _ in
      Issue.record("no stream may open for an unknown peer")
      return ReplyingStream(reply: "")
    }
    let url = URL(string: "cappiroh://never-paired/v1/session")!
    await #expect(throws: URLError.self) { _ = try await BridgeSession.shared.data(from: url) }
  }

  @Test func aMalformedReplyBecomesABadServerResponse() async {
    IrohHub.shared.register(peer("peer-bad"))
    IrohHub.shared.setOpener { _, _ in ReplyingStream(reply: "not http at all") }
    let url = URL(string: "cappiroh://peer-bad/v1/session")!
    do {
      _ = try await BridgeSession.shared.data(from: url)
      Issue.record("a malformed reply must fail")
    } catch {
      #expect((error as? URLError)?.code == .badServerResponse)
    }
  }

  @Test func anIrohProfileMustNameItsPairedPeer() throws {
    let addr = peer("peer-a")
    let machine = UUID()
    _ = try BridgeProfile(
      machineID: machine, endpoint: URL(string: "cappiroh://peer-a")!, name: "Mini", iroh: addr)
    #expect(throws: BridgeError.self) {
      try BridgeProfile(
        machineID: machine, endpoint: URL(string: "cappiroh://peer-b")!, name: "Mini", iroh: addr)
    }
    #expect(throws: BridgeError.self) {
      try BridgeProfile(
        machineID: machine, endpoint: URL(string: "cappiroh://peer-a")!, name: "Mini", iroh: nil)
    }
  }
}
