import Foundation
import Testing

@testable import CappuccinoCore

// Fixture-driven transcript tests: mapping, reconciliation-contract
// conformance (fixtures/reconciliation-cases.json), dedupe and scale.

private func fixtureURL(_ name: String) -> URL {
  URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .deletingLastPathComponent()
    .appendingPathComponent("fixtures/\(name)")
}

@Suite struct TranscriptFixtureTests {
  @Test func historyFixtureMapsEntriesAndToolCalls() throws {
    let fixture = try TranscriptFixture.load(
      from: Data(contentsOf: fixtureURL("history-active-branch.json")))
    #expect(fixture.sessionID == "s-aurora")
    #expect(fixture.activeBranch == "feat/collector-fix")
    #expect(fixture.entries.count == 3)
    let user = try #require(fixture.entries.first { $0.isUser })
    #expect(user.text.contains("collector path"))
    #expect(user.seq == 1)
    let editor = try #require(fixture.entries.first { $0.tool?.name == "edit" })
    let tool = try #require(editor.tool)
    #expect(tool.args["path"] == "Makefile")
    #expect(tool.detail?.contains("ANDROID_TEST_OUTPUT") == true)
    #expect(editor.complete)
  }

  @Test func reconciliationFixtureCasesMatchContract() throws {
    // duplicate-append-is-idempotent: the reassembler renders the duplicate once.
    var reassembler = TranscriptHistoryReassembler()
    let duplicate = TranscriptEntry(
      seq: 4, id: nil, kind: "assistant", text: "first", branch: nil, complete: true, tool: nil)
    let sameContentAgain = TranscriptEntry(
      seq: 4, id: nil, kind: "assistant", text: "first", branch: nil, complete: true, tool: nil)
    _ = reassembler.merge([duplicate])
    _ = reassembler.merge([sameContentAgain])
    // Identical text at identical seq synthesizes the identical id, so the
    // duplicate is suppressed and exactly one entry renders.
    #expect(reassembler.entries.count == 1)
    #expect(reassembler.entries[0].text == "first")

    // gap-is-visible-placeholder: a mid-stream seq jump inserts a visible
    // gap row (joining mid-stream at the first entry renders no placeholder —
    // the bridge emits its own gap markers for lost prefixes).
    let seeded: [TranscriptEntry] = (1...4).map { seq in
      let text = "e\(seq)"
      return TranscriptEntry(
        seq: seq, id: nil, kind: "assistant", text: text, branch: nil, complete: true, tool: nil)
    }
    _ = reassembler.merge(seeded)
    let later = TranscriptEntry(
      seq: 9, id: nil, kind: "assistant", text: "later", branch: nil, complete: true, tool: nil)
    _ = reassembler.merge([later])
    // duplicate(1) + seeded(4) + gap + later
    #expect(reassembler.entries.count == 7)
    let gapIndex = try #require(reassembler.entries.firstIndex { $0.isGap })
    #expect(reassembler.entries[gapIndex].text.contains("gap"))
    #expect(reassembler.entries[gapIndex + 1].text == "later")
  }

  @Test func distinctIdsSameTextAreNotSuppressed() {
    var reassembler = TranscriptHistoryReassembler()
    let first = TranscriptEntry(
      seq: 1, id: "a", kind: "assistant", text: "same", branch: nil, complete: true, tool: nil)
    let second = TranscriptEntry(
      seq: 2, id: "b", kind: "assistant", text: "same", branch: nil, complete: true, tool: nil)
    let added = reassembler.merge([first, second])
    #expect(added.count == 2, "distinct real ids are distinct deliveries")
    #expect(reassembler.entries.count == 2)
  }
}

@Suite struct TranscriptModelTests {
  @MainActor
  private func makeModel(stream: AsyncThrowingStream<TranscriptStreamEvent, Error>)
    -> TranscriptModel
  {
    TranscriptModel(
      session: "s-aurora",
      machineURL: URL(string: "http://127.0.0.1:7392")!,
      streaming: ScriptedTranscriptStreamer(stream: stream),
      branch: "feat/collector-fix")
  }

  @MainActor
  @Test func eventsApplyOpenEntriesResetAndFailure() async throws {
    let (stream, continuation) = AsyncThrowingStream<TranscriptStreamEvent, Error>.makeStream()
    let model = makeModel(stream: stream)
    model.start()
    continuation.yield(.open(generation: 0))
    continuation.yield(
      .entries([
        TranscriptEntry(
          seq: 1, id: "e1", kind: "user", text: "hello", branch: nil, complete: true, tool: nil)
      ]))
    continuation.yield(
      .entries([
        TranscriptEntry(
          seq: 1, id: "e1", kind: "user", text: "hello", branch: nil, complete: true, tool: nil)
      ]))
    // Wait for the batch to be applied.
    try await Task.sleep(nanoseconds: 50_000_000)
    #expect(model.entries.count == 1, "duplicate ids are idempotent")
    #expect(model.entries[0].text == "hello")

    // Reset clears; the next stream repopulates from scratch.
    continuation.yield(.reset(generation: 1))
    continuation.yield(
      .entries([
        TranscriptEntry(
          seq: 1, id: "r1", kind: "assistant", text: "fresh", branch: nil, complete: true, tool: nil
        )
      ]))
    try await Task.sleep(nanoseconds: 50_000_000)
    #expect(model.entries.map(\.text) == ["fresh"])

    continuation.finish(throwing: DaemonClientError.unreachable("bridge closed"))
    try await Task.sleep(nanoseconds: 50_000_000)
    guard case .failed(let message) = model.phase else {
      Issue.record("disconnect must surface a visible failure")
      return
    }
    #expect(message.contains("closed"))
    model.stop()
  }

  @MainActor
  @Test func stopTearsDownWithoutLeakedStream() async throws {
    let (stream, continuation) = AsyncThrowingStream<TranscriptStreamEvent, Error>.makeStream()
    let model = makeModel(stream: stream)
    model.start()
    continuation.yield(.open(generation: 0))
    try await Task.sleep(nanoseconds: 20_000_000)
    model.stop()
    continuation.finish()
    // No crash, no visible failure: deterministic teardown is silent.
    try await Task.sleep(nanoseconds: 20_000_000)
    if case .failed = model.phase {
      Issue.record("stop() must not render a failure")
    }
  }
}

/// Adapter for scripted streams in model tests.
struct ScriptedTranscriptStreamer: TranscriptStreaming {
  let stream: AsyncThrowingStream<TranscriptStreamEvent, Error>

  func transcriptStream(baseURL: URL, session: String, branch: String?)
    -> AsyncThrowingStream<TranscriptStreamEvent, Error>
  {
    stream
  }
}

@Suite struct TranscriptScaleTests {
  @Test func thousandEntryMergeIsLinearAndDeduplicates() {
    var reassembler = TranscriptHistoryReassembler()
    func measure(_ count: Int) -> Double {
      let clock = ContinuousClock()
      let start = clock.now
      for index in 1...count {
        let entry = TranscriptEntry(
          seq: index, id: "e\(index)", kind: index.isMultiple(of: 2) ? "assistant" : "user",
          text: "entry \(index)", branch: nil, complete: true, tool: nil)
        _ = reassembler.merge([entry])
      }
      #expect(reassembler.entries.count == count)
      let elapsed = clock.now - start
      return Double(elapsed.components.seconds) + Double(elapsed.components.attoseconds) / 1e18
    }
    let small = measure(1000)
    let large = measure(4000)
    // Amortized O(1): 4x the entries must stay within a bounded multiple
    // (generous floor keeps CI machines deterministic).
    let floor: Double = 0.01
    #expect(
      large / max(small, floor) < 8,
      "merge must be amortized linear: 1k=\(small)s 4k=\(large)s")
  }

  @Test func longFixtureParsesAllThousandEntries() throws {
    var entries: [String] = []
    for index in 1...1000 {
      entries.append(
        "{\"seq\":\(index),\"kind\":\"assistant\",\"text\":\"entry \(index)\"}"
      )
    }
    let payload =
      "{\"session_id\":\"s-long\",\"active_branch\":\"main\",\"entries\":[\(entries.joined(separator: ","))]}"
    let fixture = try TranscriptFixture.load(from: Data(payload.utf8))
    #expect(fixture.entries.count == 1000)
  }
}
