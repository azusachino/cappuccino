package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.activityState
import org.junit.Assert.*
import org.junit.Test

class ToolActivitySheetTest {
  @Test
  fun activityPreservesResponseAssociationAndOriginalOrderWithoutProse() {
    val turns =
      listOf(
        ConversationTurn(
          "one",
          "agent",
          null,
          null,
          listOf(ConversationPart.Text("Reply"), ConversationPart.ToolCall("read", "file", "text")),
        ),
        ConversationTurn(
          "two",
          "agent",
          null,
          null,
          listOf(
            ConversationPart.ToolCall("bash", "test", null, true),
            ConversationPart.Thinking("reason"),
          ),
        ),
      )
    val entries = toolActivityEntries(turns)
    assertEquals(listOf("one", "two"), entries.map { it.turnId })
    assertEquals(listOf(1, 0), entries.map { it.partIndex })
    assertEquals(listOf("read", "bash"), entries.map { it.part.name })
    assertEquals("Show tool activity · 2 · 1 failed", toolActivityLabel(entries))
  }

  @Test
  fun cappedCountDistinguishesShownHistoryFromRecordedHistoryAndIsNotAQueue() {
    assertEquals("100/350", toolActivityCount(100, 250))
    assertEquals("3", toolActivityCount(3, 0))
    val entries =
      listOf(ToolActivityEntry("old", 0, ConversationPart.ToolCall("bash", "echo hello", null)))
    assertEquals(
      "Show tool activity · 1/4 · 1 shown from loaded history · 1 result not recorded",
      toolActivityLabel(entries, 3),
    )
    assertFalse(toolActivityLabel(entries).contains("awaiting"))
    assertFalse(toolActivityLabel(entries).contains("queued"))
  }

  @Test
  fun unknownResultIsNotSuccessfulAndExplicitFailureIsNotPending() {
    assertEquals(
      ToolActivityState.RESULT_NOT_RECORDED,
      ConversationPart.ToolCall("any-provider", "", null).activityState,
    )
    assertEquals(
      ToolActivityState.RESULT_AVAILABLE,
      ConversationPart.ToolCall("any-provider", "", "error in ordinary output").activityState,
    )
    assertEquals(
      ToolActivityState.FAILED,
      ConversationPart.ToolCall("any-provider", "", null, true).activityState,
    )
  }
}
