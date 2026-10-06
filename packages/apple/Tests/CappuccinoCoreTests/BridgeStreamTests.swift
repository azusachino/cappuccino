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
