package com.azusachino.cappuccino.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.MainActivity
import com.azusachino.cappuccino.core.ConversationPart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolResultVisibilityTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun normalResultIsVisibleWithoutExpanding() {
    compose.activity.setContent {
      CappuccinoTheme {
        ConversationDetails(ConversationPart.ToolCall("bash", "echo hello", "hello", false))
      }
    }
    compose.onNodeWithText("Result").assertIsDisplayed()
    compose.onNodeWithText("hello").assertIsDisplayed()
  }

  @Test
  fun failedResultStartsOpenAndRemainsVisibleAfterManualCollapse() {
    compose.activity.setContent {
      CappuccinoTheme {
        ConversationDetails(ConversationPart.ToolCall("bash", "test", "fatal: failed", true))
      }
    }
    compose.onNodeWithText("Error").assertIsDisplayed()
    compose.onNodeWithText("fatal: failed", substring = true).assertIsDisplayed()
    compose.onNodeWithText("bash · Failed").performClick()
    compose.onNodeWithText("fatal: failed").assertIsDisplayed()
  }
}
