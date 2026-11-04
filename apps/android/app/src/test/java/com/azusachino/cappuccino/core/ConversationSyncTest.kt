package com.azusachino.cappuccino.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSyncTest {
  private fun turn(id: String, text: String = id) =
    ConversationTurn(id, "agent", null, text, listOf(ConversationPart.Text(text)))

  private fun page(
    upserts: List<ConversationTurn>,
    mode: String = "append",
    after: String? = null,
    hasAfter: Boolean = false,
    before: String? = null,
    hasBefore: Boolean = false,
    conversationId: String? = "pi:a",
  ) =
    SyncPage(
      mode = mode,
      upserts = upserts,
      afterToken = after,
      hasAfter = hasAfter,
      beforeToken = before,
      hasBefore = hasBefore,
      caughtUp = true,
      conversationId = conversationId,
    )

  private val cursor =
    SyncCursor(after = "A", before = "B", startReached = false, conversationId = "pi:a")

  @Test
  fun newestPageReplacesAnEmptyLogAndSetsBothCursors() {
    val (turns, next) =
      applySyncPage(
        emptyList(),
        null,
        SyncDirection.Newest,
        page(
          listOf(turn("t1"), turn("t2")),
          after = "A",
          hasAfter = true,
          before = "B",
          hasBefore = true,
        ),
      )
    assertEquals(listOf("t1", "t2"), turns.map { it.id })
    assertEquals(SyncCursor("A", "B", startReached = false, conversationId = "pi:a"), next)
  }

  @Test
  fun forwardPageAppendsNewTurnsAndReplacesAHeldTurnInPlace() {
    val held = listOf(turn("t1"), turn("t2", "old"))
    val (turns, next) =
      applySyncPage(
        held,
        cursor,
        SyncDirection.Forward,
        page(listOf(turn("t2", "complete"), turn("t3")), after = "A2", hasAfter = true),
      )
    assertEquals(listOf("t1", "t2", "t3"), turns.map { it.id })
    assertEquals("complete", (turns[1].parts.single() as ConversationPart.Text).text)
    assertEquals("A2", next?.after)
    assertEquals("B", next?.before)
  }

  @Test
  fun backwardPageIsPrependedInOrderAndLeavesTheForwardCursor() {
    val log = listOf(turn("t5"), turn("t6"))
    val (turns, next) =
      applySyncPage(
        log,
        cursor,
        SyncDirection.Backward,
        page(listOf(turn("t3"), turn("t4")), hasBefore = true, before = null),
      )
    assertEquals(listOf("t3", "t4", "t5", "t6"), turns.map { it.id })
    assertEquals("A", next?.after)
    assertTrue(next?.startReached == true)
    assertNull(next?.before)
  }

  @Test
  fun absentTokenKeepsTheOldCursorValue() {
    val (_, next) =
      applySyncPage(
        listOf(turn("t1")),
        cursor,
        SyncDirection.Backward,
        page(listOf(turn("t0")), hasBefore = true, before = "B2"),
      )
    assertEquals("A", next?.after)
    assertEquals("B2", next?.before)
    assertEquals(false, next?.startReached)
  }

  @Test
  fun resyncReplacesTheLogAndTheCursor() {
    val (turns, next) =
      applySyncPage(
        listOf(turn("t1"), turn("t2")),
        cursor,
        SyncDirection.Forward,
        page(
          listOf(turn("x")),
          mode = "resync",
          after = "A9",
          hasAfter = true,
          before = "B9",
          hasBefore = true,
        ),
      )
    assertEquals(listOf("x"), turns.map { it.id })
    assertEquals("A9", next?.after)
    assertEquals("B9", next?.before)
  }

  @Test
  fun scrollbackReplacesTheLogAndStopsSyncing() {
    val (turns, next) =
      applySyncPage(
        listOf(turn("t1")),
        cursor,
        SyncDirection.Forward,
        page(listOf(turn("s1")), mode = "scrollback"),
      )
    assertEquals(listOf("s1"), turns.map { it.id })
    assertNull(next)
  }

  @Test
  fun aChangedConversationReplacesEvenAForwardPage() {
    val (turns, next) =
      applySyncPage(
        listOf(turn("t1")),
        cursor,
        SyncDirection.Forward,
        page(listOf(turn("n1")), after = "N", hasAfter = true, conversationId = "pi:b"),
      )
    assertEquals(listOf("n1"), turns.map { it.id })
    assertEquals("pi:b", next?.conversationId)
  }

  @Test
  fun forwardWithoutACursorReplacesRatherThanMerging() {
    val (turns, _) =
      applySyncPage(listOf(turn("t1")), null, SyncDirection.Forward, page(listOf(turn("t9"))))
    assertEquals(listOf("t9"), turns.map { it.id })
  }
}
