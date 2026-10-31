package com.azusachino.cappuccino

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.activitySummary
import com.azusachino.cappuccino.ui.CappuccinoTheme
import com.azusachino.cappuccino.ui.ConversationTurnRow
import java.util.TimeZone
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationPresentationTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun detailsStartCollapsedAndExpandIndependently() {
    val turn =
      ConversationTurn(
        "synthetic",
        "assistant",
        null,
        "Visible reply",
        listOf(
          ConversationPart.Thinking("Thought summary\n\nHidden thought details"),
          ConversationPart.ToolCall("bash", "echo synthetic", "Hidden tool result"),
        ),
      )
    compose.activity.runOnUiThread {
      compose.activity.setContent { CappuccinoTheme { ConversationTurnRow(turn) } }
    }
    compose.onNodeWithText("Visible reply").assertIsDisplayed()
    capture("ConversationCollapsed")
    compose.onNodeWithText("Hidden thought details", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Hidden tool result", substring = true).assertDoesNotExist()
    // An older turn's activity is one closed summary line; it opens into a step per part.
    val summary = activitySummary(turn.parts)
    compose
      .onNodeWithText(summary)
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    compose.onNodeWithText(summary).performClick()
    compose.onNodeWithText("Thinking").assertIsDisplayed()
    compose.onNodeWithText("bash").assertIsDisplayed()
    compose.onNodeWithText("Hidden thought details", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Thinking").performClick()
    compose.onNodeWithText("Hidden thought details", substring = true).assertIsDisplayed()
    compose.onNodeWithText("Hidden tool result", substring = true).assertDoesNotExist()
    compose.onNodeWithText("bash").performClick()
    compose.onNodeWithText("Hidden tool result", substring = true).assertIsDisplayed()
    capture("ConversationExpanded")
    compose.onNodeWithText("Thinking").performClick()
    compose.onNodeWithText("Hidden thought details", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Hidden tool result", substring = true).assertIsDisplayed()
  }

  @Test
  fun markdownAndTimestampRenderAsReadableDeviceLocalContent() {
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
      val turn =
        ConversationTurn(
          "markdown",
          "assistant",
          "2026-10-07T16:02:03Z",
          null,
          listOf(
            ConversationPart.Text(
              "# Synthetic heading\n\nUse **bold** and `inline code`.\n\n```kotlin\nval value = 42\n```\n\n- First item\n- Second item"
            )
          ),
        )
      // Both user prompt and assistant response carry local time: HH:mm, with full time as
      // description.
      val prompt =
        ConversationTurn("prompt", "user", "2026-10-07T16:02:03Z", "Synthetic prompt", emptyList())
      compose.activity.runOnUiThread {
        compose.activity.setContent {
          CappuccinoTheme {
            Column {
              ConversationTurnRow(prompt)
              ConversationTurnRow(turn)
            }
          }
        }
      }
      compose.onAllNodesWithText("01:02").assertCountEquals(2)
      compose.onAllNodesWithContentDescription("2026-10-08 01:02:03").assertCountEquals(2)
      compose
        .onNodeWithText("Synthetic heading")
        .assertIsDisplayed()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
      // Inline code carries its own padding spaces, so match the words, not the spacing.
      compose
        .onNode(
          hasText("Use bold and", substring = true) and hasText("inline code", substring = true)
        )
        .assertIsDisplayed()
      compose.onNodeWithText("val value = 42").assertIsDisplayed()
      compose.onNodeWithText("First item").assertIsDisplayed()
      compose.onNodeWithText("Second item").assertIsDisplayed()
      compose.onNodeWithText("# Synthetic heading").assertDoesNotExist()
      capture("ConversationMarkdown")
    } finally {
      TimeZone.setDefault(original)
    }
  }

  private fun capture(name: String) {
    val bitmap =
      requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try {
      PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }
}
