package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.StepOutcome
import com.azusachino.cappuccino.core.Tone
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.ToolKind
import com.azusachino.cappuccino.core.activityDuration
import com.azusachino.cappuccino.core.activityState
import com.azusachino.cappuccino.core.activitySummary
import com.azusachino.cappuccino.core.compactDuration
import com.azusachino.cappuccino.core.stepOutcome
import com.azusachino.cappuccino.core.stepWords
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
      .padding(start = 4.dp, end = 32.dp, bottom = 2.dp)
      .background(colors.card, RoundedCornerShape(14.dp))
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
      Text(
        "You",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.accent,
      )
      body()
      if (time != null)
        Text(
          time,
          Modifier.align(Alignment.End).semantics {
            if (timeDescription != null) contentDescription = timeDescription
          },
          style = MaterialTheme.typography.labelSmall,
          color = colors.muted,
        )
    }
  }
}

/**
 * Pi's thinking and tool calls between its words as one line: what they did, and for how long. It
 * opens into a timeline, a line per call. Open in the newest turn and closed before, unless opened
 * or closed by hand (pi-pocket's `ActivityGroup`).
 */
@Composable
internal fun ActivityGroup(
  parts: List<ConversationPart>,
  newest: Boolean = false,
  onInspect: () -> Unit = {},
) {
  val colors = MaterialTheme.appColors
  var chosen by rememberSaveable { mutableStateOf<Boolean?>(null) }
  val expanded = chosen ?: newest
  val summary = remember(parts) { activitySummary(parts) }
  val duration = remember(parts) { activityDuration(parts)?.let(::compactDuration) }
  Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
    Surface(
      onClick = {
        if (!expanded) onInspect()
        chosen = !expanded
      },
      shape = RoundedCornerShape(50),
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
      modifier =
        Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
    ) {
      Row(
        Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          painterResource(R.drawable.ic_expand_more),
          contentDescription = null,
          modifier = Modifier.size(14.dp).rotate(if (expanded) 0f else -90f),
          tint = colors.muted,
        )
        Text(
          summary,
          style = MaterialTheme.typography.labelMedium,
          color = colors.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (duration != null)
          Text(duration, style = MaterialTheme.typography.labelSmall, color = colors.muted)
      }
    }
    if (expanded) {
      val rule = colors.line
      Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp).drawBehind {
          drawLine(rule, Offset(0f, 0f), Offset(0f, size.height), strokeWidth = 1.dp.toPx())
        },
        verticalArrangement = Arrangement.spacedBy(1.dp),
      ) {
        parts.forEach { ActivityStep(it, onInspect) }
      }
    }
  }
}

/** One timeline line: its node on the rule, glyph, tool, what it touched, and what it came to. */
@Composable
private fun ActivityStep(part: ConversationPart, onInspect: () -> Unit) {
  val colors = MaterialTheme.appColors
  var open by rememberSaveable { mutableStateOf(false) }
  val glyph: String
  val glyphColor: Color
  val label: String
  val subject: String
  val outcome: StepOutcome?
  val failed: Boolean
  when (part) {
    is ConversationPart.Thinking -> {
      glyph = "✦"
      glyphColor = MaterialTheme.colorScheme.tertiary
      label = "Thought"
      subject = ""
      outcome = null
      failed = false
    }
    is ConversationPart.ToolCall -> {
      glyph = toolKind(part.name).glyph
      glyphColor = colors.muted
      val words = remember(part) { stepWords(part) }
      label = words.first
      subject = words.second
      outcome = remember(part) { stepOutcome(part) }
      failed = part.activityState == ToolActivityState.FAILED
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> return
  }
  // Done is a muted success color on the rule; only a recorded error is red.
  val node = if (failed) colors.err else colors.ok.copy(alpha = 0.4f).compositeOver(colors.card)
  Column(
    Modifier.fillMaxWidth().drawBehind {
      val side = 5.dp.toPx()
      drawRect(node, Offset(-3.dp.toPx(), 13.dp.toPx()), Size(side, side))
    }
  ) {
    Row(
      Modifier.fillMaxWidth()
        .padding(start = 14.dp)
        .heightIn(min = 30.dp)
        .clickable(role = Role.Button) {
          if (!open) onInspect()
          open = !open
        }
        .padding(horizontal = 8.dp, vertical = 3.dp)
        .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        glyph,
        Modifier.width(14.dp).clearAndSetSemantics {},
        style = MaterialTheme.typography.labelSmall,
        color = glyphColor,
        textAlign = TextAlign.Center,
      )
      Text(
        label,
        Modifier.widthIn(max = 120.dp),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        subject,
        Modifier.weight(1f),
        style = MaterialTheme.typography.labelMedium,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      outcome?.let {
        Text(
          it.text,
          Modifier.widthIn(max = 140.dp),
          style = MaterialTheme.typography.labelSmall,
          color =
            when (it.tone) {
              Tone.OK -> colors.ok
              Tone.ERR -> colors.err
              Tone.PLAIN -> colors.muted
            },
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (open)
      Column(
        Modifier.fillMaxWidth().padding(start = 44.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StepBody(part)
      }
  }
}

/** The transcript's last line while something is happening: a colored square and what it is. */
@Composable
internal fun LiveStatusLine(color: Color, detail: String? = null) {
  Row(
    Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {},
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    StatusSquare(color)
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

/** The way back to the newest output, shown only away from the bottom (pi-pocket's `.jump`). */
@Composable
internal fun JumpToLatest(newOutput: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val colors = MaterialTheme.appColors
  Box(
    modifier
      .size(40.dp)
      .background(MaterialTheme.colorScheme.background)
      .border(2.dp, if (newOutput) colors.accent else colors.lineStrong)
      .clickable(role = Role.Button, onClick = onClick)
      .semantics { contentDescription = if (newOutput) "Jump to new output" else "Jump to latest" }
      .testTag("jumpToLatest"),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      painterResource(R.drawable.ic_arrow_downward),
      contentDescription = null,
      modifier = Modifier.size(20.dp),
      tint = if (newOutput) colors.accent else colors.body,
    )
  }
}

// pi-pocket's pre.cmd / pre.output: small wrapped monospace on the code ground, a screen's
// 45% at most, scrolling inside. Very long text shows its start until asked for the rest.
private const val STEP_TEXT_PREVIEW = 20_000

@Composable
private fun StepBlock(text: AnnotatedString) {
  val colors = MaterialTheme.appColors
  var whole by rememberSaveable { mutableStateOf(false) }
  val clipped = !whole && text.length > STEP_TEXT_PREVIEW
  val maxHeight =
    with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * 0.45f).toDp() }
  Column(
    Modifier.fillMaxWidth()
      .heightIn(max = maxHeight)
      .background(colors.codeBackground)
      .border(1.dp, colors.line)
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 10.dp, vertical = 8.dp)
  ) {
    Text(
      if (clipped) text.subSequence(0, STEP_TEXT_PREVIEW) else text,
      style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 16.sp),
    )
    if (clipped)
      Text(
        "Show everything",
        Modifier.padding(top = 6.dp).clickable(role = Role.Button) { whole = true },
        style = MaterialTheme.typography.labelSmall,
        color = colors.accent,
      )
  }
}

@Composable
private fun StepBlock(text: String, color: Color) =
  StepBlock(AnnotatedString(text, SpanStyle(color = color)))

private val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

/** What a step shows when opened: what it ran or changed, then what it printed. */
@Composable
private fun StepBody(part: ConversationPart) {
  val colors = MaterialTheme.appColors
  when (part) {
    is ConversationPart.Thinking -> StepBlock(part.text, colors.muted)
    is ConversationPart.ToolCall -> {
      val failed = part.activityState == ToolActivityState.FAILED
      val args =
        remember(part.input) {
          try {
            prettyJson.parseToJsonElement(part.input) as? kotlinx.serialization.json.JsonObject
          } catch (_: Exception) {
            null
          }
        }
      fun field(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (args?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
          ?.takeIf { it.isString }
          ?.content
      }
      val kind = toolKind(part.name)
      when (kind) {
        ToolKind.COMMAND -> field("command", "cmd")?.let { StepBlock(it, colors.ok) }
        ToolKind.EDIT -> {
          val edits = buildList {
            val old = field("old_string", "oldText")
            if (old != null) add(old to (field("new_string", "newText") ?: ""))
            (args?.get("edits") as? kotlinx.serialization.json.JsonArray)?.forEach { edit ->
              val o = edit as? kotlinx.serialization.json.JsonObject ?: return@forEach
              fun text(vararg keys: String) = keys.firstNotNullOfOrNull {
                (o[it] as? kotlinx.serialization.json.JsonPrimitive)?.content
              }
              add((text("old_string", "oldText") ?: "") to (text("new_string", "newText") ?: ""))
            }
          }
          if (edits.isNotEmpty())
            StepBlock(
              buildAnnotatedString {
                edits.forEachIndexed { index, (old, new) ->
                  if (index > 0) append("\n")
                  withStyle(SpanStyle(color = colors.err)) {
                    append(old.lines().joinToString("\n") { "- $it" })
                  }
                  append("\n")
                  withStyle(SpanStyle(color = colors.ok)) {
                    append(new.lines().joinToString("\n") { "+ $it" })
                  }
                }
              }
            )
        }
        ToolKind.WRITE -> field("content")?.let { StepBlock(it, colors.body) }
        ToolKind.READ,
        ToolKind.SEARCH,
        ToolKind.LIST -> Unit
        ToolKind.OTHER ->
          if (args != null && args.isNotEmpty())
            StepBlock(
              prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), args),
              colors.body,
            )
      }
      val images = part.images ?: 0
      if (images > 0)
        StepBlock(
          "${if (images == 1) "1 image" else "$images images"} returned · not shown on the phone",
          colors.muted,
        )
      val output = part.output
      // A successful edit's diff already says what changed.
      if (!output.isNullOrEmpty() && !(kind == ToolKind.EDIT && !failed))
        StepBlock(output, if (failed) colors.err else colors.body)
      if (failed && output.isNullOrEmpty()) StepBlock("No error text reported", colors.err)
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> Unit
  }
}
