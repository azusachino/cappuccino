import Foundation
import Testing

@testable import CappuccinoCore

// Scripted WebSocket frames: initial entries, a live append, duplicate-id
// suppression and a disconnect that surfaces as a visible failure. No real
// sockets, no daemon.

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
  var recordedURL: URL?

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
      throws: DaemonClientError.protocolError(
        "stream URL scheme must be http or https, got 'ftp'")
    ) {
      try BridgeStream.endpointURL(from: URL(string: "ftp://127.0.0.1:7392")!, session: nil)
    }
    #expect(throws: DaemonClientError.protocolError("stream URL is malformed")) {
      try BridgeStream.endpointURL(from: URL(string: "no-scheme")!, session: nil)
    }
  }
}

final class URLBox: @unchecked Sendable {
  private let lock = NSLock()
  private var url: URL?

  func set(_ value: URL) {
    lock.lock()
    defer { lock.unlock() }
    url = value
  }

  var value: URL? {
    lock.lock()
    defer { lock.unlock() }
    return url
  }
}

final class RecordURLConnector: BridgeWebSocketConnecting {
  let box = URLBox()

  var recordedURL: URL? { box.value }

  func connect(url: URL) async throws -> BridgeWebSocket {
    box.set(url)
    return NoFramesSocket()
  }
}

struct NoFramesSocket: BridgeWebSocket {
  func receiveText() async throws -> String? { nil }
  func close() async {}
  func closeImmediately() {}
}

private func entriesJSON(_ entries: [String]) -> String {
  let body =
    entries
    .map { text in
      #"{"seq":1,"id":"\#(BridgeStreamEntryID.fixtures(tag: text))","kind":"output","text":"\#(text)","complete":true}"#
    }
    .joined(separator: ",")
  return #"{"event":"entries","entries":[\#(body)]}"#
}

enum BridgeStreamEntryID {
  static func fixtures(tag: String) -> String {
    // Stable per-text ids like the bridge produces (sha256 prefix); distinct
    // texts yield distinct ids.
    "id-\(tag)"
  }
}

@Suite(.serialized) struct BridgeStreamTests {
  @Test func initialEntriesThenAppendThenDuplicateSuppressed() async throws {
    let socket = FakeBridgeWebSocket(frames: [
      #"{"event":"stream_open","session_id":"cap-spike-agent","generation":0}"#,
      entriesJSON(["line-1", "line-2"]),
      entriesJSON(["line-3"]),
      entriesJSON(["line-1"]),  // duplicate id: must be suppressed
      nil,  // clean close
    ])
    let client = BridgeClient(
      host: "127.0.0.1", port: 7392,
      session: URLSession(configuration: .ephemeral))
    var outputs: [BridgeStreamOutput] = []
    do {
      for try await output in client.stream(
        base: URL(string: "http://127.0.0.1:7392")!,
        session: "cap-spike-agent",
        connector: FakeBridgeConnector(socket: socket))
      {
        outputs.append(output)
      }
      Issue.record("a clean server close mid-stream is still a failed stream")
    } catch let error as DaemonClientError {
      guard case .unreachable = error else {
        Issue.record("expected unreachable on close, got \(error)")
        return
      }
    }
    guard case .open(let generation) = outputs.first else {
      Issue.record("expected stream_open first")
      return
    }
    #expect(generation == 0)
    let allEntries = outputs.flatMap { output -> [BridgeTranscriptEntry] in
      if case .entries(let entries) = output { return entries }
      return []
    }
    #expect(allEntries.map { $0.text } == ["line-1", "line-2", "line-3"])
    #expect(Set(allEntries.map { $0.id }).count == allEntries.count)
    #expect(socket.closed)
  }

  @Test func unauthorizedStreamEventThrowsVisibly() async {
    let socket = FakeBridgeWebSocket(frames: [
      #"{"event":"error","code":"unauthorized","message":"pairing token rejected"}"#
    ])
    let client = BridgeClient(
      host: "127.0.0.1", port: 7392,
      session: URLSession(configuration: .ephemeral))
    do {
      for try await _ in client.stream(
        base: URL(string: "http://127.0.0.1:7392")!,
        session: "cap-spike-agent",
        connector: FakeBridgeConnector(socket: socket))
      {}
      Issue.record("expected an unauthorized failure")
    } catch let error as DaemonClientError {
      #expect(error == .unauthorized)
    } catch {
      Issue.record("unexpected error \(error)")
    }
  }

  @Test func reassemblerSuppressesDuplicateIDs() {
    var reassembler = TranscriptReassembler()
    let entry = BridgeTranscriptEntry(seq: 1, id: "a", kind: "output", text: "x", complete: true)
    #expect(reassembler.newEntries(from: [entry]).count == 1)
    #expect(reassembler.newEntries(from: [entry]).isEmpty)
    let fresh = BridgeTranscriptEntry(seq: 2, id: "b", kind: "output", text: "y", complete: true)
    #expect(reassembler.newEntries(from: [fresh]).count == 1)
  }
}

@Suite struct BridgeToolPayloadTests {
  // Live-shape check: wire entries that carry a tool payload map through to
  // the transcript domain model (issue #7: expandable args/details must work
  // against the real bridge, not only the demo fixture).
  @Test func wireToolPayloadMapsToTranscriptToolCall() throws {
    let frame = """
      {"event":"entries","entries":[
        {"seq":2,"id":"w-1","kind":"assistant","text":"Checked Makefile.","complete":true,
         "tool":{"name":"edit","args":{"path":"Makefile"},"detail":"+ANDROID_TEST_OUTPUT"}}
      ]}
      """.data(using: .utf8)!
    guard case .entries(let entries) = try BridgeStreamEvent.parse(frame) else {
      Issue.record("expected entries event")
      return
    }
    #expect(entries.count == 1)
    let tool = try #require(entries[0].tool)
    #expect(tool.name == "edit")
    #expect(tool.args?["path"] == "Makefile")
    #expect(tool.detail == "+ANDROID_TEST_OUTPUT")
  }

  @Test func wireEntriesWithoutToolStayNil() throws {
    let frame = """
      {"event":"entries","entries":[{"seq":1,"id":"w-2","kind":"user","text":"hi","complete":true}]}
      """.data(using: .utf8)!
    guard case .entries(let entries) = try BridgeStreamEvent.parse(frame) else {
      Issue.record("expected entries event")
      return
    }
    #expect(entries[0].tool == nil)
  }

  @Test func parsesAgentStatusEvent() throws {
    let frame = """
      {"event":"agent_status","session_id":"s-aurora","state":"working","detail":"running cargo test"}
      """.data(using: .utf8)!
    guard
      case .agentStatus(let sessionID, let state, let detail) = try BridgeStreamEvent.parse(frame)
    else {
      Issue.record("expected agent_status event")
      return
    }
    #expect(sessionID == "s-aurora")
    #expect(state == "working")
    #expect(detail == "running cargo test")
  }

  @Test func parsesPromptRequestAndResolvedEvents() throws {
    let reqFrame = """
      {"event":"prompt_request","session_id":"s-aurora","prompt":{
        "prompt_id":"p-1",
        "type":"tool_approval",
        "title":"Approve command?",
        "options":[{"id":"opt-1","label":"Allow"}]
      }}
      """.data(using: .utf8)!
    guard case .promptRequest(let sessionID, let card) = try BridgeStreamEvent.parse(reqFrame)
    else {
      Issue.record("expected prompt_request event")
      return
    }
    #expect(sessionID == "s-aurora")
    #expect(card.promptID == "p-1")
    #expect(card.type == "tool_approval")
    #expect(card.options.count == 1)

    let resFrame = """
      {"event":"prompt_resolved","session_id":"s-aurora","prompt_id":"p-1"}
      """.data(using: .utf8)!
    guard case .promptResolved(let resSession, let promptID) = try BridgeStreamEvent.parse(resFrame)
    else {
      Issue.record("expected prompt_resolved event")
      return
    }
    #expect(resSession == "s-aurora")
    #expect(promptID == "p-1")
  }
}
