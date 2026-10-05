import XCTest
@testable import DaemonCore

// Regression tests for the live-append defects found at acceptance:
// 1. positional diffs froze when the TUI repainted (fixed by content diffing);
// 2. real pi panes churn their status header/footer on every poll, so the
//    diff must emit genuinely appended lines under continuous churn, exactly
//    once, without re-baselining them away.

final class StreamRingTests: XCTestCase {
  private func lines(_ count: Int, prefix: String = "line") -> [String] {
    (1...count).map { "\(prefix)\($0)" }
  }

  private func churnFooter(_ body: [String], stamp: String) -> [String] {
    // Simulates the pi TUI: separator, cwd line and a status footer whose
    // rate/token figures mutate on every repaint.
    return body + ["────", "~/work (main)", "↑\(stamp) $\(stamp) (auto)"]
  }

  func testInitialSnapshotIsConfirmedOnIdleQuiescence() {
    let ring = StreamRing()
    let initial = ring.ingest(
      snapshot: PaneSnapshot(lines: churnFooter(lines(65), stamp: "10k"), working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(initial.count, 68)
    XCTAssertEqual(initial.last?.text, "↑10k $10k (auto)")
  }

  func testAppendIsEmittedUnderContinuousFooterChurn() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: churnFooter(lines(65), stamp: "10k"), working: false),
      branch: "main", confirmTrailing: true)

    // Turn 1: footer mutates, one new transcript line arrives above it.
    var body = lines(65)
    body.append("ACK-MARK-B")
    let first = ring.ingest(
      snapshot: PaneSnapshot(lines: churnFooter(body, stamp: "11k"), working: true),
      branch: "main", confirmTrailing: false)
    // Fresh candidates only pending on the first churning poll; nothing yet.
    XCTAssertTrue(first.isEmpty || first.map { $0.text } == ["ACK-MARK-B"])

    // Turn 2 (next poll): footer churns again, no new content.
    let second = ring.ingest(
      snapshot: PaneSnapshot(lines: churnFooter(body, stamp: "12k"), working: true),
      branch: "main", confirmTrailing: false)
    XCTAssertTrue(second.map { $0.text }.contains("ACK-MARK-B"))

    // The ACK line is emitted exactly once across further churn.
    let third = ring.ingest(
      snapshot: PaneSnapshot(lines: churnFooter(body, stamp: "13k"), working: true),
      branch: "main", confirmTrailing: false)
    XCTAssertFalse(third.map { $0.text }.contains("ACK-MARK-B"))
    let all = ring.replay(after: -1).entries.map { $0.text }
    XCTAssertEqual(all.filter { $0 == "ACK-MARK-B" }.count, 1)
  }

  func testRepaintShrinkThenGrowthStillDetectsAppend() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(65), working: false),
      branch: "main", confirmTrailing: true)
    // Compaction swallows the visible history down to 42 lines.
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(42), working: true),
      branch: "main", confirmTrailing: false)
    // Genuinely new content arrives (unique text, not a subset of the base).
    var grown = lines(42)
    grown.append("appended-43")
    grown.append("appended-44")
    // The appending poll registers the candidates...
    let first = ring.ingest(
      snapshot: PaneSnapshot(lines: grown, working: true),
      branch: "main", confirmTrailing: false)
    // ...a persisting line is confirmed on the next working poll, and the
    // idle quiescence poll confirms anything still fresh.
    let second = ring.ingest(
      snapshot: PaneSnapshot(lines: grown, working: true),
      branch: "main", confirmTrailing: false)
    let idle = ring.ingest(
      snapshot: PaneSnapshot(lines: grown, working: false),
      branch: "main", confirmTrailing: true)
    let emitted = (first + second + idle).map { $0.text }
    XCTAssertTrue(Set(emitted).isSuperset(of: ["appended-43", "appended-44"]))
    let all = ring.replay(after: -1).entries.map { $0.text }
    XCTAssertEqual(all.filter { $0 == "appended-43" }.count, 1)
  }

  func testFullRepaintRebaselinesWithoutFabricating() {
    let ring = StreamRing()
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: lines(65), working: false),
      branch: "main", confirmTrailing: true)
    let unrelated = ring.ingest(
      snapshot: PaneSnapshot(lines: ["totally", "different"], working: false),
      branch: "main", confirmTrailing: true)
    // Quiescence confirms the fresh content immediately (it IS the pane now).
    XCTAssertEqual(unrelated.map { $0.text }, ["totally", "different"])
    XCTAssertEqual(ring.replay(after: 65).entries.map { $0.text }, ["totally", "different"])
  }

  func testBranchChangeResetsGeneration() {
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

/// Adversarial pane shapes from the acceptance crash: a pane larger than the
/// LCS window made Array(suffix:) hand back a slice with non-zero base indices,
/// which the alignment walk indexed from 0 — an out-of-bounds fatal that killed
/// the daemon on the first poll of the target session.
final class AdversarialPaneTests: XCTestCase {
  private func wide(_ count: Int, stamp: String = "0") -> [String] {
    var lines = (1...count).map { "pane-line-\($0)-" + String(repeating: "x", count: $0 % 90) }
    lines += ["──────", "~/work (main)", "↑\(stamp) $\(stamp) (auto) · zai 7%"]
    return lines
  }

  func testPaneLargerThanWindowDoesNotCrashAndDetectsAppend() {
    let ring = StreamRing()
    let base = ring.ingest(
      snapshot: PaneSnapshot(lines: wide(600, stamp: "1k"), working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(base.count, 603)
    var grown = wide(600, stamp: "2k")
    grown.insert("APPEND-MARKER-1", at: 600)
    let first = ring.ingest(
      snapshot: PaneSnapshot(lines: grown, working: true),
      branch: "main", confirmTrailing: false)
    let second = ring.ingest(
      snapshot: PaneSnapshot(lines: grown, working: false),
      branch: "main", confirmTrailing: true)
    let emitted = (first + second).map { $0.text }
    XCTAssertTrue(Set(emitted).contains("APPEND-MARKER-1"))
    XCTAssertEqual(
      ring.replay(after: -1).entries.filter { $0.text == "APPEND-MARKER-1" }.count, 1)
  }

  func testAdversarialShapesSurvive() {
    let ring = StreamRing()
    // Empty read.
    XCTAssertEqual(ring.ingest(
      snapshot: PaneSnapshot(lines: [], working: false),
      branch: "main", confirmTrailing: true), [])
    // Single line.
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: ["only"], working: false),
      branch: "main", confirmTrailing: true)
    // Box-drawing, combining marks, wide glyphs, control-ish content.
    let odd = [
      "─\u{0338}\u{0301} box \u{2028} combining",
      "cafe\u{0301} 漢字 カナ 🎉 width",
      String(repeating: "─", count: 400),
      "\u{7f}\u{1b}[31m escape-ish",
    ]
    let a = ring.ingest(
      snapshot: PaneSnapshot(lines: odd, working: false),
      branch: "main", confirmTrailing: true)
    XCTAssertEqual(a.count, 4)
    // Rapid shrink and regrow across window boundary.
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: wide(600), working: true),
      branch: "main", confirmTrailing: false)
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: wide(3), working: true),
      branch: "main", confirmTrailing: false)
    let regrown = ring.ingest(
      snapshot: PaneSnapshot(lines: wide(80), working: true),
      branch: "main", confirmTrailing: false)
    XCTAssertLessThanOrEqual(regrown.count, 80)
    // Branch churn on top of everything still resets cleanly.
    _ = ring.ingest(
      snapshot: PaneSnapshot(lines: ["fresh"], working: false),
      branch: "feat/next", confirmTrailing: true)
    XCTAssertEqual(ring.generation, 1)
  }
}

final class LineDiffTests: XCTestCase {
  func testIdenticalLinesAlignCompletely() {
    let (matched, last) = LineDiff.align(previous: ["a", "b", "c"], current: ["a", "b", "c"])
    XCTAssertEqual(matched, [0, 1, 2])
    XCTAssertEqual(last, 2)
  }

  func testInPlaceChurnStillAlignsTail() {
    let previous = ["head", "↑10k $1 (auto)", "mid", "foot \(1)"]
    let current = ["head", "↑11k $2 (auto)", "mid", "foot 1"]
    let (matched, last) = LineDiff.align(previous: previous, current: current)
    XCTAssertEqual(matched, [0, 2, 3])
    XCTAssertEqual(last, 3)
  }

  func testCompactedPaneAlignsOnSuffix() {
    let (matched, last) = LineDiff.align(
      previous: ["a", "b", "c", "d", "e"], current: ["c", "d", "e", "f"])
    XCTAssertEqual(last, 2)
    XCTAssertEqual(matched, [0, 1, 2])
  }

  func testNoCommonGroundAlignsNothing() {
    let (matched, last) = LineDiff.align(previous: ["a", "b"], current: ["x", "y"])
    XCTAssertTrue(matched.isEmpty)
    XCTAssertEqual(last, -1)
  }
}
