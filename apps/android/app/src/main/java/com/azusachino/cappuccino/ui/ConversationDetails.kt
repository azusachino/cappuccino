package com.azusachino.cappuccino.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.activityState

// Tonal disclosure row per the native Material 3 contract: shapes token, no border,
// real expand affordance, expanded content inset in the same surface.
@Composable
internal fun ConversationDetails(part: ConversationPart, onInspect: () -> Unit = {}) {
  if (part is ConversationPart.ResponseStatus) {
    Column(
      Modifier.fillMaxWidth().padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          painterResource(
            if (part.status == "aborted") com.azusachino.cappuccino.R.drawable.ic_close
            else com.azusachino.cappuccino.R.drawable.ic_attention
          ),
          contentDescription = null,
          tint =
            if (part.status == "failed") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          if (part.status == "aborted") "Response aborted" else "Response failed",
          style = MaterialTheme.typography.labelLarge,
        )
      }
      part.detail
        ?.takeIf { it.isNotBlank() }
        ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    return
  }
  val failed = part is ConversationPart.ToolCall && part.activityState == ToolActivityState.FAILED
  var expanded by rememberSaveable { mutableStateOf(failed) }
  LaunchedEffect(failed) { if (failed) expanded = true }
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
      val status =
        when (part.activityState) {
          ToolActivityState.FAILED -> "Failed"
          ToolActivityState.AWAITING_RESULT -> "Awaiting result"
          ToolActivityState.RESULT_AVAILABLE -> "Result available"
        }
      title = "${part.name} · $status"
      summary = toolSummary(part.input)
    }
    is ConversationPart.Text,
    is ConversationPart.ResponseStatus -> return
  }
  Surface(
    shape = MaterialTheme.shapes.medium,
    color =
      if (failed) MaterialTheme.colorScheme.errorContainer
      else if (part is ConversationPart.Thinking) MaterialTheme.colorScheme.secondaryContainer
      else MaterialTheme.colorScheme.surfaceContainerHigh,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column {
      TextButton(
        onClick = {
          if (!expanded) onInspect()
          expanded = !expanded
        },
        modifier =
          Modifier.fillMaxWidth().semantics {
            stateDescription = if (expanded) "Expanded" else "Collapsed"
          },
      ) {
        Icon(
          painterResource(
            if (failed) com.azusachino.cappuccino.R.drawable.ic_attention
            else if (part is ConversationPart.Thinking)
              com.azusachino.cappuccino.R.drawable.ic_agent_spark
            else com.azusachino.cappuccino.R.drawable.ic_agent_code
          ),
          contentDescription = null,
          modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color =
              if (failed) MaterialTheme.colorScheme.onErrorContainer
              else MaterialTheme.colorScheme.onSurface,
          )
          if (part is ConversationPart.ToolCall) {
            val started = part.startedAt?.let { formatConversationTimestamp(it) }
            val finished = part.finishedAt?.let { formatConversationTimestamp(it) }
            val timing =
              listOfNotNull(started?.let { "Started $it" }, finished?.let { "Result received $it" })
                .joinToString(" · ")
            if (timing.isNotBlank())
              Text(
                timing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
          if (!expanded && summary.isNotBlank()) {
            Text(
              summary,
              style = MaterialTheme.typography.bodySmall,
              fontFamily = if (part is ConversationPart.ToolCall) FontFamily.Monospace else null,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 3,
              softWrap = true,
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
      if (!expanded && part is ConversationPart.ToolCall && part.output != null) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
          Text(if (failed) "Error" else "Result", style = MaterialTheme.typography.labelMedium)
          Text(
            if (failed) toolErrorPreview(part.output) else toolPreview(part.output),
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            maxLines = if (failed) 12 else 6,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      if (expanded) {
        Column(
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          when (part) {
            is ConversationPart.Thinking -> MarkdownText(part.text)
            is ConversationPart.ToolCall -> {
              if (part.input.isNotBlank()) {
                ToolDetailSection(
                  "Arguments",
                  part.input,
                  if (part.name == "bash") "bash" else "text",
                  onInspect,
                )
              }
              part.output?.let {
                ToolDetailSection(if (failed) "Error" else "Result", it, "text", onInspect)
              }
              if (failed && part.output == null) Text("No error text reported")
            }
            is ConversationPart.Text,
            is ConversationPart.ResponseStatus -> Unit
          }
        }
      }
    }
  }
}

/**
 * Compact inline preview plus a separate native reading surface, inspired by RikkaHub tool details.
 */
@Composable
private fun ToolDetailSection(
  title: String,
  source: String,
  language: String,
  onInspect: () -> Unit,
) {
  val shellCommand =
    remember(source, title) { if (title == "Arguments") toolCommand(source) else null }
  val command =
    remember(source, title) {
      if (title == "Arguments") toolSummary(source).takeIf { it != source } else null
    }
  val details =
    remember(source, command) {
      if (command != null) toolOtherArguments(source).orEmpty() else source
    }
  val content = remember(details, language) { formatToolContent(details, language) }
  val preview =
    remember(content, title) {
      if (title == "Error") toolErrorPreview(content.text) else toolPreview(content.text)
    }
  var readingFull by rememberSaveable { mutableStateOf(false) }
  Text(
    title,
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.colorScheme.secondary,
  )
  if (command != null) {
    if (shellCommand != null) NativeCodeBlock(toolPreview(command), shellCommand.language)
    else
      Text(
        toolPreview(command),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        softWrap = true,
      )
  }
  if (details.isEmpty()) {
    if (command == null) Text("No text output", style = MaterialTheme.typography.bodySmall)
  } else {
    MarkdownText(fencedToolContent(content.copy(text = preview)))
  }
  if (preview != content.text || (command != null && toolPreview(command) != command)) {
    TextButton(
      onClick = {
        onInspect()
        readingFull = true
      }
    ) {
      Icon(
        painterResource(com.azusachino.cappuccino.R.drawable.ic_more),
        contentDescription = null,
        modifier = Modifier.padding(end = 8.dp),
      )
      Text("View full ${title.lowercase()}")
    }
  }
  if (readingFull) {
    Dialog(onDismissRequest = { readingFull = false }) {
      Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp)) {
          androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
          ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.IconButton(onClick = { readingFull = false }) {
              Icon(
                painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
                contentDescription = "Close",
              )
            }
          }
          SelectionContainer(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
              if (command != null) {
                if (shellCommand != null) NativeCodeBlock(command, shellCommand.language)
                else Text(command, fontFamily = FontFamily.Monospace, softWrap = true)
              }
              if (details.isNotEmpty()) MarkdownText(fencedToolContent(content))
            }
          }
        }
      }
    }
  }
}
