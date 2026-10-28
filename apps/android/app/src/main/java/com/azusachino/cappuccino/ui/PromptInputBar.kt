package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.COMMON_SLASH_COMMANDS
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.disallowedSlashCommandReason
import com.azusachino.cappuccino.core.matchingSlashCommands
import com.azusachino.cappuccino.core.slashCommand
import com.azusachino.cappuccino.core.slashCommandWarning

@Composable
fun PromptCardView(
  prompt: PromptCard,
  onSelectOption: (Int, String) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  // Approval stays prominent as a default ElevatedCard — no custom border (region 6).
  ElevatedCard(modifier = modifier.fillMaxWidth()) {
    Column(
      modifier =
        Modifier.padding(16.dp).heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = prompt.title, style = MaterialTheme.typography.titleMedium)
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = prompt.type.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          )
        }
      }

      prompt.message?.let { msg ->
        Text(
          text = msg,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      prompt.command?.let { cmd ->
        // Command on a tonal monospace block, no border.
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainer,
          modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp),
        ) {
          Text(
            text = cmd,
            fontFamily = JetBrainsMono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp).horizontalScroll(rememberScrollState()),
          )
        }
      }

      if (!enabled) Text("This bridge can't accept answers.")
      if (prompt.options.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          prompt.options.forEachIndexed { index, option ->
            FilledTonalButton(
              onClick = { onSelectOption(index, option.id) },
              enabled = enabled,
              modifier = Modifier.fillMaxWidth(),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.Start,
              ) {
                Text(
                  text = "${index + 1}. ${option.label}",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                option.description?.let { desc ->
                  Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }

      TextButton(
        onClick = onCancel,
        enabled = enabled,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text("Cancel")
      }
    }
  }
}

@Composable
fun PromptInputBar(
  /**
   * Sends the draft; the flag is true once the owner confirmed the agent command it starts with.
   */
  onSend: (String, Boolean) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  placeholder: String = "Message agent…",
  onStop: (() -> Unit)? = null,
  model: String? = null,
  thinkingLevel: String? = null,
  draft: String = "",
  onDraftChange: ((String) -> Unit)? = null,
  pending: Boolean = false,
  outcome: String? = null,
) {
  var input by rememberSaveable(draft) { mutableStateOf(draft) }
  LaunchedEffect(draft) { if (input != draft) input = draft }
  var commandsOpen by remember { mutableStateOf(false) }
  var historyOpen by remember { mutableStateOf(false) }
  var isFocused by remember { mutableStateOf(false) }
  var promptHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
  // An agent command can cost far more than a message; it waits here, draft kept, until confirmed.
  var confirmingCommand by rememberSaveable { mutableStateOf<String?>(null) }
  confirmingCommand?.let { command ->
    val disallowedReason = disallowedSlashCommandReason(command)
    if (disallowedReason != null) {
      AlertDialog(
        onDismissRequest = { confirmingCommand = null },
        title = { Text("Command Not Supported") },
        text = { Text(disallowedReason) },
        confirmButton = { TextButton(onClick = { confirmingCommand = null }) { Text("OK") } },
      )
    } else {
      AlertDialog(
        onDismissRequest = { confirmingCommand = null },
        title = { Text("Run $command?") },
        text = { Text(slashCommandWarning(command)) },
        confirmButton = {
          TextButton(
            enabled = !pending,
            onClick = {
              val textToSend =
                if (input.isNotBlank() && slashCommand(input) == command) input else command
              if (promptHistory.firstOrNull() != textToSend) {
                promptHistory =
                  (listOf(textToSend) + promptHistory.filter { it != textToSend }).take(20)
              }
              onSend(textToSend, true)
              input = textToSend
              confirmingCommand = null
            },
          ) {
            Text("Run $command")
          }
        },
        dismissButton = { TextButton(onClick = { confirmingCommand = null }) { Text("Cancel") } },
      )
    }
  }
  val colors = MaterialTheme.appColors
  val canSend = enabled && !pending && input.isNotBlank()
  val slashSuggestions = remember(input) { matchingSlashCommands(input) }

  Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
    if (pending || outcome != null)
      Text(
        if (pending) "Sending; not yet confirmed by the agent." else outcome.orEmpty(),
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
      )
    // 1. Slash command suggestions overlay (when typing '/')
    if (slashSuggestions.isNotEmpty() && isFocused) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.line),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
      ) {
        Column(
          Modifier.padding(vertical = 4.dp)
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState())
        ) {
          slashSuggestions.forEach { item ->
            Row(
              Modifier.fillMaxWidth()
                .clickable {
                  input = "${item.command} "
                  onDraftChange?.invoke(input)
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                item.command,
                style =
                  MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.accent,
              )
              Spacer(Modifier.widthIn(min = 8.dp))
              Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }

    // 2. The input pill: one quiet glass surface; content first, chrome underneath.
    val pillShape = RoundedCornerShape(24.dp)
    Column(
      Modifier.fillMaxWidth()
        .glassSurface(
          shape = pillShape,
          rimStroke = if (isFocused) 0.dp else GlassDefaults.RimStrokeWidth,
        )
        .then(
          if (isFocused) Modifier.border(1.dp, colors.accent.copy(alpha = 0.5f), pillShape)
          else Modifier
        )
        .clip(pillShape)
    ) {
      Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.foundation.text.BasicTextField(
          value = input,
          onValueChange = {
            input = it
            onDraftChange?.invoke(it)
          },
          modifier =
            Modifier.weight(1f).onFocusChanged { isFocused = it.isFocused }.testTag("promptInput"),
          enabled = enabled,
          maxLines = 6,
          textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
          cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
          decorationBox = { field ->
            Box {
              if (input.isEmpty())
                Text(
                  if (enabled) placeholder else "Not connected",
                  style = MaterialTheme.typography.bodyMedium,
                  color = colors.faint,
                )
              field()
            }
          },
        )
        if (input.isNotEmpty()) {
          IconButton(
            onClick = {
              input = ""
              onDraftChange?.invoke("")
            },
            modifier = Modifier.size(28.dp),
          ) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
              contentDescription = "Clear draft",
              modifier = Modifier.size(16.dp),
              tint = colors.muted,
            )
          }
        }
      }

      Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Commands (slash picker, one-tap continue/compact, new session)
        Box {
          IconButton(onClick = { commandsOpen = true }, modifier = Modifier.size(36.dp)) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_grid),
              contentDescription = "Commands",
              modifier = Modifier.size(18.dp),
              tint = colors.muted,
            )
          }
          DropdownMenu(expanded = commandsOpen, onDismissRequest = { commandsOpen = false }) {
            if (enabled && !pending) {
              DropdownMenuItem(
                text = { Text("Continue") },
                onClick = {
                  commandsOpen = false
                  onSend("continue", false)
                },
              )
              DropdownMenuItem(
                text = { Text("Compact context") },
                onClick = {
                  commandsOpen = false
                  confirmingCommand = "/compact"
                },
              )
            }
            COMMON_SLASH_COMMANDS.forEach { item ->
              DropdownMenuItem(
                text = {
                  Column {
                    Text(
                      item.command,
                      style =
                        MaterialTheme.typography.labelMedium.copy(
                          fontFamily = FontFamily.Monospace
                        ),
                      color = colors.accent,
                    )
                    Text(
                      item.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = colors.muted,
                    )
                  }
                },
                onClick = {
                  commandsOpen = false
                  input = "${item.command} "
                },
              )
            }
          }
        }

        // Prompt history
        if (promptHistory.isNotEmpty()) {
          Box {
            IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(36.dp)) {
              Icon(
                painterResource(com.azusachino.cappuccino.R.drawable.ic_history),
                contentDescription = "Prompt history",
                modifier = Modifier.size(18.dp),
                tint = colors.muted,
              )
            }
            DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
              Text(
                "Recent prompts",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
              )
              promptHistory.take(8).forEach { item ->
                DropdownMenuItem(
                  text = {
                    Text(
                      item,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  },
                  onClick = {
                    historyOpen = false
                    input = item
                    onDraftChange?.invoke(item)
                  },
                )
              }
            }
          }
        }

        if (model != null)
          Text(
            listOfNotNull(shortModel(model), thinkingLevel).joinToString(" · "),
            Modifier.padding(horizontal = 6.dp).widthIn(max = 160.dp).testTag("composerModelLabel"),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

        Spacer(Modifier.weight(1f))

        val send: () -> Unit = {
          if (input.isNotBlank()) {
            val trimmed = input.trim()
            if (promptHistory.firstOrNull() != trimmed) {
              promptHistory = (listOf(trimmed) + promptHistory.filter { it != trimmed }).take(20)
            }
            val command = slashCommand(input)
            if (command != null) {
              confirmingCommand = command
            } else {
              onSend(input, false)
            }
          }
        }
        if (pending) {
          Box(
            Modifier.size(40.dp)
              .background(MaterialTheme.colorScheme.primary, CircleShape)
              .then(if (onStop != null) Modifier.clickable { onStop() } else Modifier)
              .testTag("pendingIndicator"),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              Modifier.size(14.dp)
                .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(3.dp))
                .semantics { contentDescription = "Stop" }
            )
          }
        } else {
          Box(
            Modifier.size(40.dp)
              .background(
                if (canSend) MaterialTheme.colorScheme.primary else colors.card2,
                CircleShape,
              )
              .clickable(enabled = canSend) { send() }
              .testTag("sendPrompt"),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_arrow_downward),
              modifier = Modifier.size(18.dp).rotate(180f),
              contentDescription = "Send",
              tint = if (canSend) MaterialTheme.colorScheme.onPrimary else colors.faint,
            )
          }
        }
      }
    }
  }
}

/** A model id as people say it: "opus 5.5" for `claude-opus-5-5`. */
internal fun shortModel(model: String): String =
  model.removePrefix("claude-").replace(Regex("-(\\d+)-(\\d+)$"), " $1.$2")

/** Under the message box: how full the context is and active rate limit quotas. */
