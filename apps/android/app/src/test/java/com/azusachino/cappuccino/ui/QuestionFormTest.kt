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
      "q-1",
      "H",
      "Q?",
      multi,
      listOf(PromptOption("opt-0", "A", null), PromptOption("opt-1", "B", null)),
    )

  private fun answer(selected: List<String>, text: String?) = QuestionAnswer("q-1", selected, text)

  @Test
  fun singleSelectTakesExactlyOneChoiceOrText() {
    val single = question(multi = false)
    assertTrue(answerComplete(single, answer(listOf("opt-1"), null)))
    assertTrue(answerComplete(single, answer(emptyList(), "own")))
    assertFalse(answerComplete(single, answer(emptyList(), null)))
    assertFalse(answerComplete(single, answer(listOf("opt-0"), "own")))
    assertFalse(answerComplete(single, answer(listOf("opt-0", "opt-1"), null)))
  }

  @Test
  fun multiSelectTakesAtLeastOne() {
    val multi = question(multi = true)
    assertTrue(answerComplete(multi, answer(listOf("opt-0", "opt-1"), "own")))
    assertTrue(answerComplete(multi, answer(emptyList(), "own")))
    assertFalse(answerComplete(multi, answer(emptyList(), null)))
  }
}
