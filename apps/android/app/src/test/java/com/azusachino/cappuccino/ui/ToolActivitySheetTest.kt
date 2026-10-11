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
  fun unknownResultIsNotSuccessfulAndExplicitFailureIsNotPending() {
    assertEquals(
      ToolActivityState.AWAITING_RESULT,
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
