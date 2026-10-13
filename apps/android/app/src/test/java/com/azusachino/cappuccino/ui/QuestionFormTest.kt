package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.PromptOption
import com.azusachino.cappuccino.core.PromptQuestion
import com.azusachino.cappuccino.core.QuestionAnswer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestionFormTest {
  private fun question(multi: Boolean) =
    PromptQuestion(
      "H",
      "Q?",
      multi,
      listOf(PromptOption("opt-0", "A", null), PromptOption("opt-1", "B", null)),
    )

  @Test
  fun singleSelectTakesExactlyOneChoiceOrText() {
    val single = question(multi = false)
    assertTrue(answerComplete(single, QuestionAnswer(listOf(1), null)))
    assertTrue(answerComplete(single, QuestionAnswer(emptyList(), "own")))
    assertFalse(answerComplete(single, QuestionAnswer(emptyList(), null)))
    assertFalse(answerComplete(single, QuestionAnswer(listOf(0), "own")))
    assertFalse(answerComplete(single, QuestionAnswer(listOf(0, 1), null)))
  }

  @Test
  fun multiSelectTakesAtLeastOne() {
    val multi = question(multi = true)
    assertTrue(answerComplete(multi, QuestionAnswer(listOf(0, 1), "own")))
    assertTrue(answerComplete(multi, QuestionAnswer(emptyList(), "own")))
    assertFalse(answerComplete(multi, QuestionAnswer(emptyList(), null)))
  }
}
