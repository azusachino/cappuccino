package com.azusachino.cappuccino.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.Tone
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.ToolKind
import com.azusachino.cappuccino.core.activityDuration
import com.azusachino.cappuccino.core.activityState
import com.azusachino.cappuccino.core.activitySummary
import com.azusachino.cappuccino.core.cleanToolOutput
import com.azusachino.cappuccino.core.compactDuration
import com.azusachino.cappuccino.core.stepOutcome
import com.azusachino.cappuccino.core.stepWords
import com.azusachino.cappuccino.core.toolKind

// Transcript grammar after pi-pocket (docs/licenses/pi-pocket): prose is plain text, a person's
// message is a filled row with a prompt mark, and work between words is one line that opens into
// a timeline.

/** A person's message: right-aligned Telegram-style bubble with time at bottom-end. */
@Composable
internal fun UserPromptRow(
  body: @Composable () -> Unit,
  time: String?,
  timeDescription: String?,
  modifier: Modifier = Modifier,
  shape: RoundedCornerShape =
    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
) {
  val colors = MaterialTheme.appColors
  Box(
    modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    contentAlignment = Alignment.CenterEnd,
  ) {
    Surface(
      shape = shape,
      color = colors.accent.copy(alpha = 0.12f).compositeOver(colors.card2),
      border = BorderStroke(0.6.dp, colors.accent.copy(alpha = 0.35f)),
      modifier = Modifier.widthIn(min = 60.dp, max = 340.dp).padding(start = 40.dp),
    ) {
      Column(
        Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        body()
        if (time != null) {
          Text(
            time,
            Modifier.align(Alignment.End).semantics {
              if (timeDescription != null) contentDescription = timeDescription
            },
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontFamily = JetBrainsMono,
              ),
            color = colors.muted.copy(alpha = 0.8f),
          )
        }
      }
    }
  }
}

/**
 * An agent's response message: left-aligned Telegram-style card with optional label and
 * bottom-right time.
 */
@Composable
internal fun AgentResponseRow(
  body: @Composable () -> Unit,
  assistantLabel: String,
  time: String?,
  timeDescription: String?,
  modifier: Modifier = Modifier,
  showLabel: Boolean = true,
  shape: RoundedCornerShape = RoundedCornerShape(12.dp),
) {
  val colors = MaterialTheme.appColors
  Box(
    modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    contentAlignment = Alignment.CenterStart,
  ) {
    Surface(
      shape = shape,
      color = colors.card,
      border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.45f)),
      modifier = Modifier.fillMaxWidth().padding(end = 24.dp),
    ) {
      Column(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        if (showLabel) {
          Text(
            assistantLabel,
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
              ),
            color = colors.accent,
            modifier = Modifier.padding(bottom = 2.dp),
          )
        }
        body()
        if (time != null) {
          Text(
            time,
            Modifier.align(Alignment.End).semantics {
              if (timeDescription != null) contentDescription = timeDescription
            },
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontFamily = JetBrainsMono,
              ),
            color = colors.faint,
          )
        }
      }
    }
  }
}

/**
 * Pi's thinking and tool calls between its words as a clean collapsible header. Open in the newest
 * turn and closed before, unless toggled manually.
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
  Column(
    Modifier.fillMaxWidth().padding(vertical = 2.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Surface(
      onClick = {
        if (!expanded) onInspect()
        chosen = !expanded
      },
      shape = RoundedCornerShape(12.dp),
      color = colors.card2.copy(alpha = 0.7f),
      border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.45f)),
      modifier =
        Modifier.padding(horizontal = 8.dp).semantics {
          stateDescription = if (expanded) "Expanded" else "Collapsed"
        },
    ) {
      Row(
        Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        val chevronRotation by
          animateFloatAsState(
            targetValue = if (expanded) 0f else -90f,
            animationSpec = tween(200, easing = FastOutSlowInEasing),
            label = "activityGroupChevron",
          )
        Icon(
          painterResource(R.drawable.ic_expand_more),
          contentDescription = null,
          modifier = Modifier.size(13.dp).rotate(chevronRotation),
          tint = colors.muted,
        )
        Text(
          summary,
          style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
          color = colors.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (duration != null) {
          Text(
            duration,
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontFamily = JetBrainsMono,
              ),
            color = colors.faint,
            maxLines = 1,
            softWrap = false,
          )
        }
      }
    }
    AnimatedVisibility(
      visible = expanded,
      enter =
        expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
          fadeIn(animationSpec = tween(180)),
      exit =
        shrinkVertically(animationSpec = tween(180, easing = FastOutSlowInEasing)) +
          fadeOut(animationSpec = tween(140)),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
      Column(
        Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        parts.forEach { ActivityStep(it, onInspect) }
      }
    }
  }
}

/** One timeline step: formatted cleanly in Pico collapsible tool/thinking style. */
@Composable
private fun ActivityStep(part: ConversationPart, onInspect: () -> Unit) {
  val colors = MaterialTheme.appColors
  var open by rememberSaveable { mutableStateOf(false) }
  when (part) {
    is ConversationPart.Thinking -> {
      Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable {
              if (!open) onInspect()
              open = !open
            }
            .padding(vertical = 3.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text("✦", style = MaterialTheme.typography.labelSmall, color = colors.accent)
          Text(
            "Thinking",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.muted,
          )
          val thinkingChevron by
            animateFloatAsState(
              targetValue = if (open) 0f else -90f,
              animationSpec = tween(200, easing = FastOutSlowInEasing),
              label = "thinkingChevron",
            )
          Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            modifier = Modifier.size(12.dp).rotate(thinkingChevron),
            tint = colors.muted,
          )
        }
        AnimatedVisibility(
          visible = open,
          enter =
            expandVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
              fadeIn(animationSpec = tween(160)),
          exit =
            shrinkVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
              fadeOut(animationSpec = tween(120)),
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.card2,
            border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
          ) {
            Box(Modifier.padding(10.dp)) { StepBody(part) }
          }
        }
      }
    }
    is ConversationPart.ToolCall -> {
      val words = remember(part) { stepWords(part) }
      val label = words.first
      val subject = words.second
      val outcome = remember(part) { stepOutcome(part) }
      val failed = part.activityState == ToolActivityState.FAILED
      Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(
          Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(role = Role.Button) {
              if (!open) onInspect()
              open = !open
            }
            .padding(vertical = 3.dp, horizontal = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Box(
            Modifier.size(6.dp)
              .background(
                if (failed) colors.err else colors.ok.copy(alpha = 0.7f),
                CircleShape,
              )
          )
          Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
            maxLines = 1,
          )
          if (subject.isNotBlank()) {
            Text(
              subject,
              Modifier.weight(1f),
              style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
              color = colors.muted,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          } else {
            Spacer(Modifier.weight(1f))
          }
          outcome?.let {
            Text(
              it.text,
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
          val toolChevron by
            animateFloatAsState(
              targetValue = if (open) 0f else -90f,
              animationSpec = tween(200, easing = FastOutSlowInEasing),
              label = "toolChevron",
            )
          Icon(
            painterResource(R.drawable.ic_expand_more),
            contentDescription = null,
            modifier = Modifier.size(12.dp).rotate(toolChevron),
            tint = colors.muted,
          )
        }
        AnimatedVisibility(
          visible = open,
          enter =
            expandVertically(animationSpec = tween(200, easing = FastOutSlowInEasing)) +
              fadeIn(animationSpec = tween(160)),
          exit =
            shrinkVertically(animationSpec = tween(160, easing = FastOutSlowInEasing)) +
              fadeOut(animationSpec = tween(120)),
        ) {
          Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.codeBackground,
            border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp),
          ) {
            Box(Modifier.padding(10.dp)) { StepBody(part) }
          }
        }
      }
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> Unit
  }
}

/** The transcript's last line while something is happening: a styled pill with square indicator. */
@Composable
internal fun LiveStatusLine(color: Color, detail: String? = null) {
  val colors = MaterialTheme.appColors
  Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
    Surface(
      shape = CircleShape,
      color = colors.card2.copy(alpha = 0.85f),
      border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.45f)),
      modifier = Modifier.semantics(mergeDescendants = true) {},
    ) {
      Row(
        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        StatusSquare(color)
        Text(
          detail ?: "Working…",
          style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
          color = colors.text,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
internal fun StatusSquare(color: Color, modifier: Modifier = Modifier) {
  Box(modifier.size(6.dp).background(color, CircleShape).clearAndSetSemantics {})
}

/** The way back to the newest output, shown only away from the bottom (pi-pocket's `.jump`). */
@Composable
internal fun JumpToLatest(newOutput: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
  val colors = MaterialTheme.appColors
  Box(
    modifier
      .size(40.dp)
      .shadow(2.dp, CircleShape)
      .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape)
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
    if (newOutput) {
      Box(
        Modifier.align(Alignment.TopEnd)
          .size(10.dp)
          .background(colors.accent, CircleShape)
          .clearAndSetSemantics {}
      )
    }
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
      .background(colors.codeBackground, RoundedCornerShape(8.dp))
      .border(1.dp, colors.line.copy(alpha = 0.8f), RoundedCornerShape(8.dp))
      .clip(RoundedCornerShape(8.dp))
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 12.dp, vertical = 10.dp)
  ) {
    Text(
      if (clipped) text.subSequence(0, STEP_TEXT_PREVIEW) else text,
      style =
        MaterialTheme.typography.bodySmall.copy(
          fontFamily = JetBrainsMono,
          fontSize = 11.sp,
          lineHeight = 16.sp,
        ),
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
        ToolKind.COMMAND -> {
          val cmd = field("CommandLine", "commandLine", "command_line", "command", "cmd")
          val summary = field("toolSummary")
          if (summary != null) StepBlock(summary, colors.muted)
          if (cmd != null) StepBlock(cmd, colors.ok)
        }
        ToolKind.EDIT -> {
          val edits = buildList {
            val old = field("Target", "old_string", "oldText")
            if (old != null) add(old to (field("Replacement", "new_string", "newText") ?: ""))
            (args?.get("edits") as? kotlinx.serialization.json.JsonArray)?.forEach { edit ->
              val o = edit as? kotlinx.serialization.json.JsonObject ?: return@forEach
              fun text(vararg keys: String) = keys.firstNotNullOfOrNull {
                (o[it] as? kotlinx.serialization.json.JsonPrimitive)?.content
              }
              add(
                (text("Target", "old_string", "oldText") ?: "") to
                  (text("Replacement", "new_string", "newText") ?: "")
              )
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
        ToolKind.WRITE -> field("Content", "content")?.let { StepBlock(it, colors.body) }
        ToolKind.READ -> field("Path", "path", "file_path")?.let { StepBlock(it, colors.muted) }
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
      val output = part.output?.let(::cleanToolOutput)
      // A successful edit's diff already says what changed.
      if (!output.isNullOrEmpty() && !(kind == ToolKind.EDIT && !failed))
        StepBlock(output, if (failed) colors.err else colors.body)
      if (failed && output.isNullOrEmpty()) StepBlock("No error text reported", colors.err)
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> Unit
  }
}
