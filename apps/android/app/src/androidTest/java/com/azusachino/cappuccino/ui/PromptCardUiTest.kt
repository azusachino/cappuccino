package com.azusachino.cappuccino.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.PromptOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PromptCardUiTest {
  @get:Rule val compose = createComposeRule()

  private val command = "git merge-base --is-ancestor b48c16b origin/main"

  private val approval =
    PromptCard(
      promptId = "p-1",
      type = "tool_approval",
      title = "Tool Permission Approval",
      message = "Run this command?",
      toolName = "run_command",
      command = command,
      options =
        listOf(
          PromptOption("allow_once", "Allow once", null),
          PromptOption("reject", "Reject", null),
        ),
      defaultOptionId = "allow_once",
    )

  private fun setCard(enabled: Boolean, onSelect: (String) -> Unit = {}) {
    compose.setContent {
      MaterialTheme {
        Surface {
          PromptCardView(
            prompt = approval,
            onSelectOption = onSelect,
            onCancel = {},
            enabled = enabled,
          )
        }
      }
    }
    compose.waitForIdle()
  }

  @Test
  fun approvalShowsTheExactCommandBeforeAnyAnswer() {
    setCard(enabled = true)
    compose.onNodeWithText("Run this command?").assertExists()
    compose.onNodeWithText(command).assertExists()
  }

  @Test
  fun optionsAreEnabledAndTappingOneReportsItsId() {
    var selected: String? = null
    setCard(enabled = true) { selected = it }
    compose.onNodeWithText("Allow once").assertIsEnabled()
    compose.onNodeWithText("Reject").assertIsEnabled()
    compose.onNodeWithText("Reject").performClick()
    assertEquals("reject", selected)
  }

  @Test
  fun optionsAreDisabledWhenAnswersAreNotAllowed() {
    setCard(enabled = false)
    compose.onNodeWithText("Allow once").assertIsNotEnabled()
    compose.onNodeWithText("Reject").assertIsNotEnabled()
  }
}
