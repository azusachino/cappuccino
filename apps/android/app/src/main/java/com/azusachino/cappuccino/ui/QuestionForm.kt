package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.PromptQuestion
import com.azusachino.cappuccino.core.QuestionAnswer

/** Whether one question's answer is complete: one choice or text alone, or at least one of many. */
internal fun answerComplete(question: PromptQuestion, answer: QuestionAnswer): Boolean {
  val text = !answer.other.isNullOrBlank()
  return if (question.multiSelect) answer.selected.isNotEmpty() || text
  else (answer.selected.size == 1) != text
}

/**
 * Claude's AskUserQuestion as a form: every question with its options and descriptions, a radio or
 * checkbox per option, and a line of own text in place of the terminal's "Type something". Answers
 * go together, as the terminal form submits them.
 */
@Composable
fun QuestionFormCard(
  prompt: PromptCard,
  onSubmit: (List<QuestionAnswer>) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  val colors = MaterialTheme.appColors
  val selected = remember(prompt.promptId) { prompt.questions.map { mutableStateListOf<Int>() } }
  val other = remember(prompt.promptId) { mutableStateListOf(*Array(prompt.questions.size) { "" }) }
  val answers =
    prompt.questions.indices.map { index ->
      QuestionAnswer(selected[index].toList(), other[index].trim().ifEmpty { null })
    }
  val complete =
    prompt.questions.zip(answers).all { (question, answer) -> answerComplete(question, answer) }
  ElevatedCard(modifier.fillMaxWidth().testTag("questionForm")) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Column(
        Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
      ) {
        prompt.questions.forEachIndexed { index, question ->
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (question.header.isNotBlank())
              Text(
                question.header,
                style = MaterialTheme.typography.labelSmall,
                color = colors.accent,
                fontWeight = FontWeight.SemiBold,
              )
            Text(question.question, style = MaterialTheme.typography.titleSmall)
            question.options.forEachIndexed { optionIndex, option ->
              val checked = optionIndex in selected[index]
              val toggle: () -> Unit = {
                if (question.multiSelect) {
                  if (checked) selected[index].remove(optionIndex)
                  else selected[index].add(optionIndex)
                } else {
                  selected[index].clear()
                  selected[index].add(optionIndex)
                  other[index] = ""
                }
              }
              Row(
                Modifier.fillMaxWidth()
                  .then(
                    // The whole row is the control, so its state is what a screen reader hears.
                    if (question.multiSelect)
                      Modifier.toggleable(checked, enabled = enabled, role = Role.Checkbox) {
                        toggle()
                      }
                    else
                      Modifier.selectable(
                        checked,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = toggle,
                      )
                  )
                  .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                if (question.multiSelect) Checkbox(checked, onCheckedChange = null)
                else RadioButton(checked, onClick = null)
                Column(Modifier.padding(start = 8.dp)) {
                  Text(option.label, style = MaterialTheme.typography.bodyMedium)
                  option.description?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted)
                  }
                }
              }
            }
            OutlinedTextField(
              value = other[index],
              onValueChange = { text ->
                // One line: a newline would submit the terminal form early.
                other[index] = text.replace('\n', ' ')
                if (!question.multiSelect && text.isNotBlank()) selected[index].clear()
              },
              enabled = enabled,
              singleLine = true,
              label = { Text("Other") },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }
      if (!enabled) Text("This bridge can't accept answers.")
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
      ) {
        TextButton(onClick = onCancel, enabled = enabled) { Text("Cancel") }
        Button(onClick = { onSubmit(answers) }, enabled = enabled && complete) { Text("Submit") }
      }
    }
  }
}
