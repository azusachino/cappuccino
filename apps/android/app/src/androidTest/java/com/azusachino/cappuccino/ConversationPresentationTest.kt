package com.azusachino.cappuccino

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
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
    compose.onNodeWithText("Hidden thought details").assertDoesNotExist()
    compose.onNodeWithText("Hidden tool result").assertDoesNotExist()
    compose
      .onNodeWithText("Thinking")
      .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    compose.onNodeWithText("Thinking").performClick()
    compose.onNodeWithText("Hidden thought details").assertIsDisplayed()
    compose.onNodeWithText("Hidden tool result").assertDoesNotExist()
    compose.onNodeWithText("Tool: bash · Result available").performClick()
    compose.onNodeWithText("Hidden tool result").assertIsDisplayed()
    capture("ConversationExpanded")
    compose.onNodeWithText("Thinking").performClick()
    compose.onNodeWithText("Hidden thought details").assertDoesNotExist()
    compose.onNodeWithText("Hidden tool result").assertIsDisplayed()
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
      compose.activity.runOnUiThread {
        compose.activity.setContent { CappuccinoTheme { Column { ConversationTurnRow(turn) } } }
      }
      compose.onNodeWithText("2026-10-08 01:02:03").assertIsDisplayed()
      compose
        .onNodeWithText("Synthetic heading")
        .assertIsDisplayed()
        .assert(SemanticsMatcher.expectValue(SemanticsProperties.Heading, Unit))
      compose.onNodeWithText("Use bold and inline code.").assertIsDisplayed()
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
