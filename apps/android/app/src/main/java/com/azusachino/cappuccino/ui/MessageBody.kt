package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

internal fun messagePreview(text: String): String {
  val lines = text.lineSequence().take(6).joinToString("\n")
  return lines.substring(
    0,
    lines.offsetByCodePoints(0, minOf(600, lines.codePointCount(0, lines.length))),
  )
}

// Native transcript budget: do not turn ordinary replies into six-line chat previews.
internal fun transcriptInlineText(text: String): String =
  text.substring(0, text.offsetByCodePoints(0, minOf(16_000, text.codePointCount(0, text.length))))

@Composable
internal fun MessageBody(
  text: String,
  modifier: Modifier = Modifier,
  markdown: Boolean = true,
  onInspect: () -> Unit = {},
  transcript: Boolean = false,
) {
  val preview =
    remember(text, transcript) {
      if (transcript) transcriptInlineText(text) else messagePreview(text)
    }
  var full by rememberSaveable { mutableStateOf(false) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (markdown) MarkdownText(preview, compact = true)
    else Text(preview, style = MaterialTheme.typography.bodyMedium)
    if (preview != text)
      TextButton(
        onClick = {
          onInspect()
          full = true
        }
      ) {
        Text("View full message")
      }
  }
  if (full)
    Dialog(onDismissRequest = { full = false }) {
      Surface(shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(16.dp)) {
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Message", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { full = false }) {
              Icon(
                painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
                contentDescription = "Close message",
              )
            }
          }
          SelectionContainer(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
              if (markdown) MarkdownText(text) else Text(text)
            }
          }
        }
      }
    }
}
