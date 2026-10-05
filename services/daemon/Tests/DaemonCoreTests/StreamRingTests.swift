import XCTest
@testable import DaemonCore

// Regression tests for the live-append defect found at acceptance: the poll
// loop must detect lines appended beyond an initial snapshot for any pane,
// including after a TUI repaint that shrinks or shifts the line list.

final class StreamRingTests: XCTestCase {
  private func lines(_ count: Int, prefix: String = "line") -> [String] {
    (1...count).map { "\(prefix)\($0)" }
  }

  func testAppendBeyondInitialSnapshotIsDetected() {
    let ring = StreamRing()
    let initial = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(65), working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(initial.count, 65)
    XCTAssertEqual(initial.last?.text, "line65")

    // Second poll: two lines appended while the agent is working — the
    // trailing line is held, the other becomes a confirmed entry.
    let grown = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(67), working: true),
      branch: "main", confirmTrailing: false)
    XCTAssertEqual(grown.map { $0.text }, ["line66"])
    XCTAssertEqual(grown.map { $0.complete }, [true])

    let quiesced = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(67), working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(quiesced.map { $0.text }, ["line67"])
  }

  func testAppendedLinesAfterRepaintShrinkAreStillDetected() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(65), working: false),
      branch: "main", confirmTrailing: true)
    // TUI repaint: scrollback compacts to 42 lines, then the agent appends.
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(42), working: true),
      branch: "main", confirmTrailing: false)
    let grown = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(45), working: true),
      branch: "main", confirmTrailing: false)
    XCTAssertEqual(grown.map { $0.text }, ["line43", "line44"])
    let quiesced = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(45), working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(quiesced.map { $0.text }, ["line45"])
  }

  func testFullRepaintWithNoContinuityRebaselinesWithoutFabricating() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(65), working: false),
      branch: "main", confirmTrailing: true)
    let unrelated = ring.ingest(
      snapshot: PaneSnapshot(lines: ["totally", "different", "content"], working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(unrelated, [])
    XCTAssertEqual(ring.count, 65, "previously confirmed entries are retained")

    // Growth after the re-baseline is detected again.
    let grown = ring.ingest(
      snapshot: PaneSnapshot(lines: ["totally", "different", "content", "more"], working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(grown.map { $0.text }, ["more"])
  }

  func testSequencesStayMonotonicAcrossAppends() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(3), working: false),
      branch: "main", confirmTrailing: true)
    let second = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(5), working: false),
      branch: "main", confirmTrailing: true)
    let (replay, _) = ring.replay(after: 1)
    XCTAssertEqual(replay.map { $0.seq }, [2, 3, 4, 5])
    XCTAssertEqual(Set(second.map { $0.seq }).count, second.count)
  }

  func testBranchChangeResetsAndKeepsGeneration() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: ["a", "b"], working: false),
      branch: "main", confirmTrailing: true)
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: ["fresh"], working: false),
      branch: "feat/next", confirmTrailing: true)
    XCTAssertEqual(ring.generation, 1)
    let (replay, generation) = ring.replay(after: -1)
    XCTAssertEqual(generation, 1)
    XCTAssertEqual(replay.map { $0.text }, ["fresh"])
  }
}

final class LineDiffTests: XCTestCase {
  func testIdenticalSnapshotsAppendNothing() {
    XCTAssertEqual(LineDiff.appendedLines(previous: ["a", "b"], current: ["a", "b"]), [])
  }

  func testPureAppend() {
    XCTAssertEqual(LineDiff.appendedLines(previous: ["a", "b"], current: ["a", "b", "c"]), ["c"])
  }

  func testOverlapAfterCompaction() {
    XCTAssertEqual(
      LineDiff.appendedLines(previous: ["a", "b", "c", "d"], current: ["c", "d", "e"]),
      ["e"])
  }

  func testNoContinuityReturnsNil() {
    XCTAssertNil(LineDiff.appendedLines(previous: ["a", "b"], current: ["x", "y"]))
    XCTAssertNil(LineDiff.appendedLines(previous: ["a"], current: []))
  }
}
