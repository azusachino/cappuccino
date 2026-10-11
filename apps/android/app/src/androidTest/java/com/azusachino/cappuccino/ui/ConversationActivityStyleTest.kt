package com.azusachino.cappuccino.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.MainActivity
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationActivityStyleTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun proseBubbleDoesNotContainToolActivity() {
    val turn =
      ConversationTurn(
        "t",
        "agent",
        null,
        null,
        listOf(
          ConversationPart.Text("Actual reply"),
          ConversationPart.ToolCall("bash", "pwd", "workspace", false),
        ),
      )
    compose.activity.setContent { CappuccinoTheme { ConversationTurnRow(turn) } }
    compose
      .onNodeWithContentDescription("Assistant", useUnmergedTree = true)
      .assert(hasAnyDescendant(hasText("Actual reply")))
    compose
      .onNodeWithContentDescription("Assistant", useUnmergedTree = true)
      .assert(hasAnyDescendant(hasText("bash · Result available")).not())
    compose.onNodeWithText("bash · Result available").assertIsDisplayed()
    compose.onNodeWithText("workspace").assertIsDisplayed()
  }

  @Test
  fun markerOpensToolSheetWithoutPuttingResultsInChat() {
    val turns =
      listOf(
        ConversationTurn(
          "tools",
          "agent",
          null,
          null,
          listOf(ConversationPart.ToolCall("bash", "pwd", "literal result", false)),
        )
      )
    compose.activity.setContent {
      var open by
        androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
      CappuccinoTheme {
        ConversationTurnRow(turns.single(), showToolsInline = false)
        ReaderFloatingControls(
          toolActivityEntries(turns),
          false,
          onTools = { open = true },
          onLatest = { error("Unexpected latest action") },
        )
        if (open)
          ToolActivitySheet(toolActivityEntries(turns), "tools", onDismiss = { open = false })
      }
    }
    compose.onNodeWithText("literal result").assertDoesNotExist()
    compose.onNodeWithText("Tools · 1").performClick()
    compose.onNodeWithText("Tool activity").assertIsDisplayed()
    compose.onNodeWithText("literal result").assertIsDisplayed()
    compose.onNodeWithContentDescription("Close tool activity").performClick()
    compose.onNodeWithText("literal result").assertDoesNotExist()
  }

  @Test
  fun toolsAndLatestShareTheSameFloatingRow() {
    val entries =
      listOf(ToolActivityEntry("t", 0, ConversationPart.ToolCall("read", "file", "result")))
    compose.activity.setContent {
      CappuccinoTheme { ReaderFloatingControls(entries, true, onTools = {}, onLatest = {}) }
    }
    val tools = compose.onNodeWithText("Tools · 1").fetchSemanticsNode().boundsInRoot
    val latest = compose.onNodeWithText("Jump to latest").fetchSemanticsNode().boundsInRoot
    org.junit.Assert.assertEquals(tools.center.y, latest.center.y, 1f)
  }

  @Test
  fun paragraphsAndTimestampShareOneMessageBlock() {
    val timestamp = "2026-10-10T01:00:00Z"
    val turn =
      ConversationTurn(
        "block",
        "agent",
        timestamp,
        null,
        listOf(ConversationPart.Text("First paragraph"), ConversationPart.Text("Second paragraph")),
      )
    compose.activity.setContent { CappuccinoTheme { ConversationTurnRow(turn) } }
    compose
      .onAllNodesWithContentDescription("Assistant", useUnmergedTree = true)
      .assertCountEquals(1)
    val bubble =
      compose
        .onNodeWithContentDescription("Assistant", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val time =
      compose
        .onNodeWithContentDescription(formatConversationTimestamp(timestamp)!!)
        .fetchSemanticsNode()
        .boundsInRoot
    org.junit.Assert.assertTrue(bubble.contains(time.center))
    compose.onNodeWithText("First paragraph").assertIsDisplayed()
    compose.onNodeWithText("Second paragraph").assertIsDisplayed()
  }

  @Test
  fun hiddenToolOnlyTurnDoesNotLeaveAStrayTimestamp() {
    val timestamp = "2026-10-10T01:00:00Z"
    val turn =
      ConversationTurn(
        "tool",
        "agent",
        timestamp,
        null,
        listOf(ConversationPart.ToolCall("read", "file", "text")),
      )
    compose.activity.setContent {
      CappuccinoTheme { ConversationTurnRow(turn, showToolsInline = false) }
    }
    compose.onNodeWithText(formatConversationTimestamp(timestamp)!!).assertDoesNotExist()
  }

  @Test
  fun failedToolResultDoesNotLeakIntoChat() {
    val turn =
      ConversationTurn(
        "tool-error",
        "agent",
        null,
        null,
        listOf(ConversationPart.ToolCall("bash", "test", "private tool error", true)),
      )
    compose.activity.setContent {
      CappuccinoTheme { ConversationTurnRow(turn, showToolsInline = false) }
    }
    compose.onNodeWithText("private tool error").assertDoesNotExist()
    compose.onNodeWithText("Tool failed · View details").assertDoesNotExist()
  }

  @Test
  fun providerFailureIsNotChatNoiseButPartialReplyRemains() {
    val turn =
      ConversationTurn(
        "upstream",
        "agent",
        null,
        "Partial useful reply",
        listOf(ConversationPart.ResponseStatus("failed", "terminated")),
      )
    compose.activity.setContent {
      CappuccinoTheme { ConversationTurnRow(turn, showToolsInline = false) }
    }
    compose.onNodeWithText("Partial useful reply").assertIsDisplayed()
    compose.onNodeWithText("Response failed").assertDoesNotExist()
    compose.onNodeWithText("terminated").assertDoesNotExist()
  }

  @Test
  fun emptyAbortedResponseStillHasAVisibleStatus() {
    val turn =
      ConversationTurn(
        "abort",
        "agent",
        null,
        null,
        listOf(ConversationPart.ResponseStatus("aborted", null)),
      )
    compose.activity.setContent { CappuccinoTheme { ConversationTurnRow(turn) } }
    compose.onNodeWithText("Response aborted").assertIsDisplayed()
  }

  @Test
  fun toolOnlyActivityShowsRealStartAndResultTimes() {
    val start = "2026-10-10T01:00:00Z"
    val finish = "2026-10-10T01:00:03Z"
    compose.activity.setContent {
      CappuccinoTheme {
        ConversationDetails(
          ConversationPart.ToolCall("bash", "pwd", "workspace", false, start, finish)
        )
      }
    }
    compose
      .onNodeWithText(
        "Started ${formatConversationTimestamp(start)} · Result received ${formatConversationTimestamp(finish)}"
      )
      .assertIsDisplayed()
  }
}
