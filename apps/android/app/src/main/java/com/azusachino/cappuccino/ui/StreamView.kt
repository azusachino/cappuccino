package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.OutputRow

internal fun collapseOutputRows(rows: List<OutputRow>): List<OutputRow> {
  if (rows.isEmpty()) return emptyList()
  val result = mutableListOf<OutputRow>()
  for (row in rows) {
    if (row.gap) {
      result += row
      continue
    }
    val currentParsed = TranscriptParser.parseLine(row.text)
    if (currentParsed.role == TranscriptRole.STATUS) {
      if (currentParsed.text.isBlank()) continue
      // Chrome/status lines (working · ..., [hh:mm:ss] ... running) represent transient TUI state.
      // Remove any previously recorded status lines anywhere in the transcript to keep only the
      // latest.
      result.removeAll {
        !it.gap && TranscriptParser.parseLine(it.text).role == TranscriptRole.STATUS
      }
      result += row
      continue
    }

    if (currentParsed.role == TranscriptRole.USER) {
      // If the last non-gap row was already the identical user prompt, collapse it
      val lastNonGap = result.lastOrNull { !it.gap }
      if (lastNonGap != null) {
        val lastParsed = TranscriptParser.parseLine(lastNonGap.text)
        if (lastParsed.role == TranscriptRole.USER && lastParsed.text == currentParsed.text) {
          result[result.lastIndexOf(lastNonGap)] = row
          continue
        }
      }
    }

    val last = result.lastOrNull()
    if (last != null && !last.gap && shouldCollapseStreamTails(last.text, row.text)) {
      result[result.lastIndex] = row
    } else {
      result += row
    }
  }
  return result
}

private fun shouldCollapseStreamTails(prevText: String, currentText: String): Boolean {
  if (prevText == currentText) return true
  val p = prevText.trim()
  val c = currentText.trim()
  if (p.isEmpty() || c.isEmpty()) return false
  if (c.startsWith(p) || p.startsWith(c)) return true
  val pParsed = TranscriptParser.parseLine(p)
  val cParsed = TranscriptParser.parseLine(c)
  if (pParsed.role == TranscriptRole.STATUS && cParsed.role == TranscriptRole.STATUS) return true
  if (pParsed.role == TranscriptRole.TOOL && cParsed.role == TranscriptRole.TOOL) {
    if (pParsed.toolName == cParsed.toolName && pParsed.toolName != null) return true
  }
  return false
}

@Composable
internal fun OutputItemRow(row: OutputRow, onInspect: () -> Unit = {}) {
  if (row.gap) {
    // Gap markers are plain centered lines on the background (region 5d).
    Text(
      row.text,
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
    return
  }

  val parsed = remember(row.text) { TranscriptParser.parseLine(row.text) }

  when (parsed.role) {
    TranscriptRole.USER -> {
      val colors = MaterialTheme.appColors
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd,
      ) {
        Surface(
          shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
          color = colors.card2,
          border = androidx.compose.foundation.BorderStroke(0.6.dp, colors.line.copy(alpha = 0.5f)),
        ) {
          Text(
            parsed.text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    TranscriptRole.TOOL -> {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          parsed.toolName ?: "tool",
          color = MaterialTheme.colorScheme.onSurface,
          fontFamily = JetBrainsMono,
          style = MaterialTheme.typography.bodySmall,
        )
        parsed.toolArg?.let { arg ->
          Text(
            "($arg)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = JetBrainsMono,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
          )
        }
      }
    }
    TranscriptRole.STATUS -> {
      if (parsed.text.isNotBlank()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Small semantic-color status dot; its visuals carry no semantics of their own.
          Box(
            modifier =
              Modifier.size(6.dp)
                .background(
                  agentStatusStyle(
                      "working",
                      MaterialTheme.colorScheme.background.luminance() < 0.5f,
                    )
                    .background,
                  CircleShape,
                )
                .clearAndSetSemantics {}
          )
          Text(
            parsed.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
          )
        }
      }
    }
    TranscriptRole.AGENT -> {
      if (parsed.text.isNotBlank()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
          MessageBody(parsed.text, markdown = false, onInspect = onInspect)
        }
      }
    }
  }
}
