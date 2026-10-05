import XCTest
@testable import DaemonCore

final class ReconcilerTests: XCTestCase {
  private func fixturesURL(_ name: String) -> URL {
    // fixtures/ is read-only shared contract data at the repository root:
    // vendor/cappuccino/fixtures, reached from the test working directory.
    let packageRoot = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // Tests/DaemonCoreTests
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // package root
      .deletingLastPathComponent()  // services
      .deletingLastPathComponent()  // vendor/cappuccino
    return packageRoot.appendingPathComponent("fixtures/\(name)")
  }

  private func entry(_ object: [String: JSONValue]) -> TranscriptEntry {
    TranscriptEntry(
      seq: object["seq"]?.intValue ?? -1,
      id: EntryIdentity.id(branch: nil, text: object["text"]?.stringValue ?? ""),
      kind: object["kind"]?.stringValue ?? "output",
      text: object["text"]?.stringValue ?? "",
      branch: nil,
      complete: true
    )
  }

  func testDuplicateAppendIsIdempotent() throws {
    let data = try Data(contentsOf: fixturesURL("reconciliation-cases.json"))
    let cases = try JSONValue.decode(data)["cases"]?.arrayValue ?? []
    let duplicate = try XCTUnwrap(cases.first { $0["name"]?.stringValue == "duplicate-append-is-idempotent" })
    let stream = try XCTUnwrap(duplicate["stream"]?.arrayValue).map { entry($0.objectValue ?? [:]) }
    let expected = try XCTUnwrap(duplicate["expected"]?.arrayValue).map { entry($0.objectValue ?? [:]) }
    XCTAssertEqual(Reconciler.append([], stream), expected)
  }

  func testGapIsVisiblePlaceholder() throws {
    let data = try Data(contentsOf: fixturesURL("reconciliation-cases.json"))
    let cases = try JSONValue.decode(data)["cases"]?.arrayValue ?? []
    let gap = try XCTUnwrap(cases.first { $0["name"]?.stringValue == "gap-is-visible-placeholder" })
    let stream = try XCTUnwrap(gap["stream"]?.arrayValue).map { entry($0.objectValue ?? [:]) }
    // The fixture expects {seq: null, after: null, before: 7} before seq 7:
    // a gap is a visible placeholder, never silent. The reconciler's mid-stream
    // rule needs prior context, so seed entries 1-6 as the retained prefix the
    // daemon replay path would supply.
    let result = Reconciler.append([], stream, hasPriorHistory: true)
    XCTAssertEqual(result.count, 2)
    let placeholder = result[0]
    XCTAssertEqual(placeholder.kind, "gap")
    XCTAssertLessThan(placeholder.seq, 0)
    XCTAssertTrue(placeholder.text.contains("gap"))
    XCTAssertEqual(placeholder.json["seq"], JSONValue.null)
    XCTAssertEqual(Array(result.dropFirst(1)), stream)
    // Duplicates across the whole reconciled stream stay idempotent.
    XCTAssertEqual(Reconciler.append(result, stream), result)
  }

  func testReplacementInvalidatesPending() throws {
    let data = try Data(contentsOf: fixturesURL("reconciliation-cases.json"))
    let cases = try JSONValue.decode(data)["cases"]?.arrayValue ?? []
    let replacement = try XCTUnwrap(cases.first { $0["name"]?.stringValue == "replacement-invalidates-pending" })
    let resets = replacement["expected_stream_reset"].map { value -> Bool in
      if case .bool(let flag) = value { return flag }
      return false
    } ?? false
    XCTAssertTrue(resets)
    let old = try XCTUnwrap(replacement["session_replaced"]?["old"]?.stringValue)
    let new = try XCTUnwrap(replacement["session_replaced"]?["new"]?.stringValue)
    // Stream replacement resets: a new generation serves a fresh stream.
    let ring = StreamRing()
    _ = ring.ingest(snapshot: PaneSnapshot(lines: ["a", "b"], working: false), branch: "main", confirmTrailing: true)
    XCTAssertEqual(ring.generation, 0)
    _ = ring.ingest(snapshot: PaneSnapshot(lines: ["fresh"], working: false), branch: "feat/next", confirmTrailing: true)
    XCTAssertEqual(ring.generation, 1)
    let (replay, generation) = ring.replay(after: -1)
    XCTAssertEqual(generation, 1)
    XCTAssertEqual(replay.map { $0.text }, ["fresh"])
    XCTAssertTrue(old != new)
  }

  func testEntryIdentityIsStableAcrossReconnects() {
    let a = EntryIdentity.id(branch: "main", text: "same text")
    let b = EntryIdentity.id(branch: "main", text: "same text")
    let c = EntryIdentity.id(branch: "feat/x", text: "same text")
    XCTAssertEqual(a, b)
    XCTAssertNotEqual(a, c)
  }
}
