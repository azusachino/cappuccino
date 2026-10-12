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
    // Prose and the activity line are separate rows: the reply text never wraps tool output.
    val summary = com.azusachino.cappuccino.core.activitySummary(turn.parts)
    val reply = compose.onNodeWithText("Actual reply").fetchSemanticsNode().boundsInRoot
    val activity = compose.onNodeWithText(summary).fetchSemanticsNode().boundsInRoot
    org.junit.Assert.assertTrue("reply=$reply activity=$activity", reply.bottom <= activity.top)
    compose.onNodeWithText(summary).performClick()
    compose.onNodeWithText("bash").performClick()
    compose.onNodeWithText("workspace").assertIsDisplayed()
    org.junit.Assert.assertTrue(
      compose.onNodeWithText("workspace").fetchSemanticsNode().boundsInRoot.top >= reply.bottom
    )
  }

  @Test
  fun paragraphsAndTimestampShareOneMessageBlock() {
    val turn =
      ConversationTurn(
        "block",
        "agent",
        null,
        null,
        listOf(ConversationPart.Text("First paragraph"), ConversationPart.Text("Second paragraph")),
      )
    // Only the person's prompts carry a time; it sits inside that prompt's block.
    val timestamp = "2026-10-10T01:00:00Z"
    val prompt = ConversationTurn("prompt", "user", timestamp, "Synthetic prompt", emptyList())
    compose.activity.setContent {
      CappuccinoTheme {
        androidx.compose.foundation.layout.Column {
          ConversationTurnRow(prompt)
          ConversationTurnRow(turn)
        }
      }
    }
    compose
      .onAllNodesWithContentDescription("Assistant", useUnmergedTree = true)
      .assertCountEquals(1)
    val bubble =
      compose
        .onNodeWithContentDescription("Assistant", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    for (paragraph in listOf("First paragraph", "Second paragraph")) {
      val bounds = compose.onNodeWithText(paragraph).fetchSemanticsNode().boundsInRoot
      org.junit.Assert.assertTrue(paragraph, bubble.contains(bounds.center))
    }
    val you =
      compose
        .onNodeWithContentDescription("You", useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
    val time =
      compose
        .onNodeWithContentDescription(formatConversationTimestamp(timestamp)!!)
        .fetchSemanticsNode()
        .boundsInRoot
    org.junit.Assert.assertTrue(you.contains(time.center))
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
