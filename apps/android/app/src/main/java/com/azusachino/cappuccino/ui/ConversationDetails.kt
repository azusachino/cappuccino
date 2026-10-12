package com.azusachino.cappuccino.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.ConversationPart

// Tonal disclosure row per the native Material 3 contract: shapes token, no border,
// real expand affordance, expanded content inset in the same surface.
@Composable
internal fun ConversationDetails(part: ConversationPart) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  val chevronRotation by
    animateFloatAsState(
      targetValue = if (expanded) 180f else 0f,
      label = "disclosureChevron",
    )
  val title: String
  val summary: String
  when (part) {
    is ConversationPart.Thinking -> {
      title = "Thinking"
      summary = part.text
    }
    is ConversationPart.ToolCall -> {
      val status = if (part.output == null) "Awaiting result" else "Result available"
      title = "Tool: ${part.name} · $status"
      summary = part.input
    }
    is ConversationPart.Text -> return
  }
  Surface(
    shape = MaterialTheme.shapes.medium,
    color = MaterialTheme.colorScheme.surfaceContainer,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      TextButton(
        onClick = { expanded = !expanded },
        modifier =
          Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
          },
      ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(title, style = MaterialTheme.typography.labelLarge)
          summary
            .lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.let {
              Text(
                it.trim().take(120),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
        }
        Icon(
          painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_expand_more),
          contentDescription = null,
          modifier = Modifier.padding(start = 8.dp).rotate(chevronRotation).clearAndSetSemantics {},
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (expanded) {
        Column(
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          when (part) {
            is ConversationPart.Thinking -> MarkdownText(part.text)
            is ConversationPart.ToolCall -> {
              if (part.input.isNotBlank())
                Text(
                  part.input,
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodySmall,
                )
              part.output?.let {
                Text(
                  it,
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
            is ConversationPart.Text -> Unit
          }
        }
      }
    }
  }
}
