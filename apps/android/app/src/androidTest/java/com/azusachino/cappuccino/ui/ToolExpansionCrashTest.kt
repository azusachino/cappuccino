package com.azusachino.cappuccino.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.core.ConversationPart
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ToolExpansionCrashTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun expandingShellToolOutsideMarkdownRendersNativeCommand() {
    compose.setContent {
      CappuccinoTheme {
        ConversationDetails(
          ConversationPart.ToolCall("bash", """{"command":"echo hello"}""", "hello", false)
        )
      }
    }
    compose.onNodeWithText("bash · Result available").performClick()
    compose.onAllNodesWithText("echo hello").onLast().assertIsDisplayed()
    compose.onAllNodesWithText("Copy code").assertCountEquals(2)
    compose.onAllNodesWithText("Copy code").onFirst().assertIsDisplayed()
    compose.onNodeWithText("Arguments").assertIsDisplayed()
  }
}
