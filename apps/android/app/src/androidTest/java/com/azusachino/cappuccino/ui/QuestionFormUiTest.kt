package com.azusachino.cappuccino.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.PromptOption
import com.azusachino.cappuccino.core.PromptQuestion
import com.azusachino.cappuccino.core.QuestionAnswer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuestionFormUiTest {
  @get:Rule val compose = createComposeRule()

  private val card =
    PromptCard(
      promptId = "q-toolu_1",
      type = "ask_user_question",
      title = "Pick a color?",
      message = "Question 1 of 2",
      toolName = "AskUserQuestion",
      command = null,
      options = emptyList(),
      selectedIndex = 0,
      questions =
        listOf(
          PromptQuestion(
            "Color",
            "Pick a color?",
            false,
            listOf(PromptOption("opt-0", "Red", "warm"), PromptOption("opt-1", "Blue", "cool")),
          ),
          PromptQuestion(
            "Pets",
            "Which pets?",
            true,
            listOf(
              PromptOption("opt-0", "Cat", "indep"),
              PromptOption("opt-1", "Dog", null),
              PromptOption("opt-2", "Fish", null),
            ),
          ),
        ),
    )

  @Test
  fun composerRetainsTextWhilePendingDisablesSendAndConfirmsSlashCommand() {
    val submissions = mutableListOf<Pair<String, Boolean>>()
    // One composition: pending flips through state so the composer instance (and its draft)
    // survives.
    val pending = mutableStateOf(false)
    compose.setContent {
      CappuccinoTheme {
        PromptInputBar(
          onSend = { text, confirmed -> submissions += text to confirmed },
          enabled = true,
          pending = pending.value,
        )
      }
    }
    compose.onNodeWithTag("promptInput").performTextInput("hello")
    compose.onNodeWithContentDescription("Send").performClick()
    assertEquals(listOf("hello" to false), submissions)
    compose.onNodeWithTag("promptInput").assertTextEquals("hello")

    compose.runOnUiThread { pending.value = true }
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    compose.onNodeWithText("Continue").assertIsNotEnabled()
    compose.runOnUiThread { pending.value = false }
    compose.waitForIdle()

    compose.onNodeWithContentDescription("Clear draft").performClick()
    compose.onNodeWithTag("promptInput").performTextInput("/compact")
    compose.onNodeWithContentDescription("Send").performClick()
    compose.onNodeWithText("Run /compact?").assertExists()
    compose.onNodeWithText("Run /compact").performClick()
    assertEquals("/compact" to true, submissions.last())
  }

  @Test
  fun everyQuestionIsAnsweredBeforeOneSubmission() {
    val submitted = mutableListOf<List<QuestionAnswer>>()
    var canceled = 0
    compose.setContent {
      CappuccinoTheme {
        QuestionFormCard(card, onSubmit = { submitted += it }, onCancel = { canceled++ })
      }
    }
    compose.onNodeWithText("Question 1 of 2").assertExists()
    // Descriptions show with their options.
    compose.onNodeWithText("warm").performScrollTo()
    compose.onNodeWithText("Submit").assertIsNotEnabled()

    compose.onNodeWithText("Red").performClick()
    compose.onNodeWithText("Blue").performClick()
    compose.onNodeWithText("Submit").assertIsNotEnabled()
    compose.onNodeWithText("Cat").performScrollTo().performClick().assertIsOn()
    compose.onNodeWithText("Fish").performScrollTo().performClick()
    compose.onNodeWithText("Cat").performClick().assertIsOff()
    compose.onNodeWithText("Submit").assertIsEnabled().performClick()
    assertEquals(
      listOf(QuestionAnswer(listOf(1), null), QuestionAnswer(listOf(2), null)),
      submitted.single(),
    )

    compose.onNodeWithText("Cancel").performClick()
    assertEquals(1, canceled)
  }

  @Test
  fun ownTextReplacesASingleChoice() {
    val submitted = mutableListOf<List<QuestionAnswer>>()
    compose.setContent {
      CappuccinoTheme { QuestionFormCard(card, onSubmit = { submitted += it }, onCancel = {}) }
    }
    compose.onNodeWithText("Red").performClick().assertIsSelected()
    // The first "Other" field belongs to the first question; typing there clears its choice.
    compose.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextInput(" Teal ")
    compose.onNodeWithText("Red").performScrollTo().assertIsNotSelected()
    compose.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextInput("a bird")
    compose.onNodeWithText("Submit").performClick()
    assertEquals(
      listOf(QuestionAnswer(emptyList(), "Teal"), QuestionAnswer(emptyList(), "a bird")),
      submitted.single(),
    )
  }
}
