package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.ToolKind
import com.azusachino.cappuccino.core.activityDuration
import com.azusachino.cappuccino.core.activityState
import com.azusachino.cappuccino.core.activitySummary
import com.azusachino.cappuccino.core.compactDuration
import com.azusachino.cappuccino.core.toolDuration
import com.azusachino.cappuccino.core.toolKind

// Transcript grammar after pi-pocket (docs/licenses/pi-pocket): prose is plain text, a person's
// message is a filled row with a prompt mark, and work between words is one line that opens into
// a timeline.

/** A person's message: who and when over what they wrote, behind a `›` mark. */
@Composable
internal fun UserPromptRow(
  body: @Composable () -> Unit,
  time: String?,
  timeDescription: String?,
  modifier: Modifier = Modifier,
) {
  val colors = MaterialTheme.appColors
  Row(
    modifier
      .fillMaxWidth()
      .background(colors.card)
      .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      "›",
      Modifier.clearAndSetSemantics {},
      color = colors.accent,
      fontWeight = FontWeight.Bold,
      style = MaterialTheme.typography.bodyMedium,
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          "You",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = colors.accent,
        )
        if (time != null)
          Text(
            time,
            Modifier.semantics {
              if (timeDescription != null) contentDescription = timeDescription
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
          )
      }
      body()
    }
  }
}

/** Consecutive thinking and tool calls as one summary line ("Ran 2 commands · 3.1s"). */
@Composable
internal fun ActivityGroup(parts: List<ConversationPart>, onInspect: () -> Unit = {}) {
  val colors = MaterialTheme.appColors
  val tools = parts.filterIsInstance<ConversationPart.ToolCall>()
  val failures = tools.count { it.activityState == ToolActivityState.FAILED }
  var expanded by rememberSaveable { mutableStateOf(failures > 0) }
  val summary = remember(parts) { activitySummary(parts) }
  val duration = remember(parts) { activityDuration(parts)?.let(::compactDuration) }
  Column(Modifier.fillMaxWidth()) {
    Row(
      Modifier.fillMaxWidth()
        .heightIn(min = 36.dp)
        .clickable(role = Role.Button) {
          if (!expanded) onInspect()
          expanded = !expanded
        }
        .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        painterResource(R.drawable.ic_expand_more),
        contentDescription = null,
        modifier = Modifier.size(16.dp).rotate(if (expanded) 0f else -90f),
        tint = colors.muted,
      )
      Text(
        summary,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelMedium,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (failures > 0)
        Text("$failures failed", style = MaterialTheme.typography.labelSmall, color = colors.err)
      if (duration != null)
        Text(duration, style = MaterialTheme.typography.labelSmall, color = colors.faint)
    }
    if (expanded) {
      val rule = colors.line
      Column(
        Modifier.fillMaxWidth().padding(start = 7.dp).drawBehind {
          drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        }
      ) {
        parts.forEach { ActivityStep(it, onInspect) }
      }
    }
  }
}

private val ToolKind.glyph: String
  get() =
    when (this) {
      ToolKind.READ -> "≡"
      ToolKind.SEARCH -> "/"
      ToolKind.EDIT -> "~"
      ToolKind.WRITE -> "+"
      ToolKind.COMMAND -> "$"
      ToolKind.OTHER -> "·"
    }

/** One timeline line: glyph, verb, what it touched, and only what the record proves. */
@Composable
private fun ActivityStep(part: ConversationPart, onInspect: () -> Unit) {
  val colors = MaterialTheme.appColors
  var open by rememberSaveable { mutableStateOf(false) }
  val glyph: String
  val glyphColor: Color
  val label: String
  val subject: String
  var meta: String? = null
  var metaColor = colors.muted
  when (part) {
    is ConversationPart.Thinking -> {
      glyph = "✦"
      glyphColor = MaterialTheme.colorScheme.tertiary
      label = "Thought"
      subject = part.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }
    is ConversationPart.ToolCall -> {
      val kind = toolKind(part.name)
      glyph = kind.glyph
      glyphColor = colors.muted
      label = part.name
      subject = remember(part.input) { toolSummary(part.input).lineSequence().first() }
      when (part.activityState) {
        ToolActivityState.FAILED -> {
          meta = "failed"
          metaColor = colors.err
        }
        // A missing result is not a failure or a running call; say only that none is recorded.
        ToolActivityState.RESULT_NOT_RECORDED -> {
          meta = "no result"
          metaColor = colors.faint
        }
        ToolActivityState.RESULT_AVAILABLE -> meta = toolDuration(part)?.let(::compactDuration)
      }
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> return
  }
  Column(Modifier.fillMaxWidth().padding(start = 10.dp)) {
    Row(
      Modifier.fillMaxWidth()
        .heightIn(min = 32.dp)
        .clickable(role = Role.Button) {
          if (!open) onInspect()
          open = !open
        }
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        glyph,
        Modifier.width(12.dp).clearAndSetSemantics {},
        style = MaterialTheme.typography.labelMedium,
        color = glyphColor,
      )
      Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = colors.text,
        maxLines = 1,
      )
      Text(
        subject,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelMedium,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      meta?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = metaColor) }
    }
    if (open)
      Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 2.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        ConversationPartBody(part, onInspect)
      }
  }
}

/** The transcript's last line while something is happening: a colored square and what it is. */
@Composable
internal fun LiveStatusLine(label: String, color: Color, detail: String? = null) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusSquare(color)
    Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    if (!detail.isNullOrBlank())
      Text(
        detail,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.appColors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
  }
}

@Composable
internal fun StatusSquare(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.size(8.dp).background(color).clearAndSetSemantics {})
}
