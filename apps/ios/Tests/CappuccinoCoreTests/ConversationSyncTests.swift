import Foundation
import Testing

@testable import CappuccinoCore

/// Ports ConversationSyncTest.kt: the merge, cursor and resync rules of contract §7.
struct ConversationSyncTests {
  private func turn(_ id: String, _ text: String? = nil) -> ConversationTurn {
    ConversationTurn(
      id: id, role: "agent", timestamp: nil, text: text ?? id, parts: [.text(text ?? id)])
  }

  private func page(
    _ upserts: [ConversationTurn], mode: String = "append", after: String? = nil,
    hasAfter: Bool = false, before: String? = nil, hasBefore: Bool = false,
    conversationID: String? = "pi:a"
  ) -> SyncPage {
    SyncPage(
      mode: mode, upserts: upserts, afterToken: after, hasAfter: hasAfter, beforeToken: before,
      hasBefore: hasBefore, caughtUp: true, conversationID: conversationID)
  }

  private let cursor = SyncCursor(
    after: "A", before: "B", startReached: false, conversationID: "pi:a")

  @Test func newestPageReplacesAnEmptyLogAndSetsBothCursors() {
    let result = applySyncPage(
      [], cursor: nil, direction: .newest,
      page: page(
        [turn("t1"), turn("t2")], after: "A", hasAfter: true, before: "B", hasBefore: true))
    #expect(result.turns.map(\.id) == ["t1", "t2"])
    #expect(
      result.cursor
        == SyncCursor(after: "A", before: "B", startReached: false, conversationID: "pi:a"))
  }

  @Test func forwardPageAppendsNewTurnsAndReplacesAHeldTurnInPlace() {
    let result = applySyncPage(
      [turn("t1"), turn("t2", "old")], cursor: cursor, direction: .forward,
      page: page([turn("t2", "complete"), turn("t3")], after: "A2", hasAfter: true))
    #expect(result.turns.map(\.id) == ["t1", "t2", "t3"])
    #expect(result.turns[1].text == "complete")
    #expect(result.cursor?.after == "A2")
    #expect(result.cursor?.before == "B")
  }

  @Test func backwardPageIsPrependedInOrderAndLeavesTheForwardCursor() {
    let result = applySyncPage(
      [turn("t5"), turn("t6")], cursor: cursor, direction: .backward,
      page: page([turn("t3"), turn("t4")], before: nil, hasBefore: true))
    #expect(result.turns.map(\.id) == ["t3", "t4", "t5", "t6"])
    #expect(result.cursor?.after == "A")
    #expect(result.cursor?.startReached == true)
    #expect(result.cursor?.before == nil)
  }

  @Test func absentTokenKeepsTheOldCursorValue() {
    let result = applySyncPage(
      [turn("t1")], cursor: cursor, direction: .backward,
      page: page([turn("t0")], before: "B2", hasBefore: true))
    #expect(result.cursor?.after == "A")
    #expect(result.cursor?.before == "B2")
    #expect(result.cursor?.startReached == false)
  }

  @Test func resyncReplacesTheLogAndTheCursor() {
    let result = applySyncPage(
      [turn("t1"), turn("t2")], cursor: cursor, direction: .forward,
      page: page(
        [turn("x")], mode: "resync", after: "A9", hasAfter: true, before: "B9", hasBefore: true))
    #expect(result.turns.map(\.id) == ["x"])
    #expect(result.cursor?.after == "A9")
    #expect(result.cursor?.before == "B9")
  }

  @Test func scrollbackReplacesTheLogAndStopsSyncing() {
    let result = applySyncPage(
      [turn("t1")], cursor: cursor, direction: .forward,
      page: page([turn("s1")], mode: "scrollback"))
    #expect(result.turns.map(\.id) == ["s1"])
    #expect(result.cursor == nil)
  }

  @Test func aChangedConversationReplacesEvenAForwardPage() {
    let result = applySyncPage(
      [turn("t1")], cursor: cursor, direction: .forward,
      page: page([turn("n1")], after: "N", hasAfter: true, conversationID: "pi:b"))
    #expect(result.turns.map(\.id) == ["n1"])
    #expect(result.cursor?.conversationID == "pi:b")
  }

  @Test func anAppendPageWithoutAnIdentityContinuesTheSameConversation() {
    let result = applySyncPage(
      [turn("t1")], cursor: cursor, direction: .forward,
      page: page([turn("t2")], after: "A2", hasAfter: true, conversationID: nil))
    #expect(result.turns.map(\.id) == ["t1", "t2"])
    #expect(result.cursor?.conversationID == "pi:a")
  }

  @Test func forwardWithoutACursorReplacesRatherThanMerging() {
    let result = applySyncPage(
      [turn("t1")], cursor: nil, direction: .forward, page: page([turn("t9")]))
    #expect(result.turns.map(\.id) == ["t9"])
  }
}
