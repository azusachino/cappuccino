package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.COMMON_SLASH_COMMANDS
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.busyAgentCommandReason
import com.azusachino.cappuccino.core.disallowedSlashCommandReason
import com.azusachino.cappuccino.core.matchingSlashCommands
import com.azusachino.cappuccino.core.slashCommand
import com.azusachino.cappuccino.core.slashCommandWarning
import com.azusachino.cappuccino.io.SessionStats

@Composable
fun PromptCardView(
  prompt: PromptCard,
  onSelectOption: (Int, String) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
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
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (prompt.options.isNotEmpty()) {
        val options = prompt.options
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          options.forEachIndexed { index, option ->
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant,
              modifier =
                Modifier.fillMaxWidth().clickable(enabled = enabled) {
                  onSelectOption(index, option.id)
                },
            ) {
              Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
              ) {
                Text(
                  text = option.label,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.Medium,
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

/**
 * Pico-style unified composer card.
 *
 * Encapsulates the multi-line prompt text field, action shortcuts (+ slash commands, history),
 * circular send/stop button, and embedded bottom toolbar (model pill, effort pill, and token
 * usage).
 */
@Composable
fun PromptInputBar(
  onSend: (String, Boolean) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  placeholder: String = "Message agent…",
  onStop: (() -> Unit)? = null,
  model: String? = null,
  agent: String? = null,
  thinkingLevel: String? = null,
  draft: String = "",
  onDraftChange: ((String) -> Unit)? = null,
  pending: Boolean = false,
  agentWorking: Boolean = false,
  outcome: String? = null,
  stats: SessionStats? = null,
) {
  var input by rememberSaveable(draft) { mutableStateOf(draft) }
  LaunchedEffect(draft) { if (input != draft) input = draft }
  var commandsOpen by remember { mutableStateOf(false) }
  var historyOpen by remember { mutableStateOf(false) }
  var modelMenuOpen by remember { mutableStateOf(false) }
  var effortMenuOpen by remember { mutableStateOf(false) }
  var isFocused by remember { mutableStateOf(false) }
  val focusRequester = remember { FocusRequester() }
  var promptHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
  var confirmingCommand by rememberSaveable { mutableStateOf<String?>(null) }

  confirmingCommand?.let { command ->
    val disallowedReason =
      disallowedSlashCommandReason(command) ?: busyAgentCommandReason(command, agentWorking)
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

  // Context token metrics for toolbar
  val tokens = stats?.contextTokens
  val effectiveModel = stats?.model ?: model
  val window = stats?.contextWindow ?: defaultContextWindow(effectiveModel, agent)
  val percent =
    when {
      tokens != null -> (tokens * 100 / window).coerceIn(0, 100).toInt()
      else ->
        stats?.rateLimits?.windows?.firstOrNull { it.window == "session" }?.usedPercent?.toInt()
    }

  Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
    if (pending || outcome != null) {
      Text(
        if (pending) "Sending; not yet confirmed by the agent." else outcome.orEmpty(),
        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = colors.muted,
      )
    }

    // 1. Slash command suggestions dropdown overlay
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

    // 2. Pico unified composer card
    val cardShape = RoundedCornerShape(20.dp)
    Surface(
      shape = cardShape,
      color = colors.card,
      border =
        BorderStroke(
          1.dp,
          if (isFocused) colors.accent.copy(alpha = 0.5f) else colors.line.copy(alpha = 0.45f),
        ),
      shadowElevation = if (isFocused) 3.dp else 1.dp,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Column {
        // Multi-line prompt text field
        Row(
          Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
          verticalAlignment = Alignment.Top,
        ) {
          androidx.compose.foundation.text.BasicTextField(
            value = input,
            onValueChange = {
              input = it
              onDraftChange?.invoke(it)
            },
            modifier =
              Modifier.weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { isFocused = it.isFocused }
                .testTag("promptInput"),
            enabled = enabled,
            maxLines = 6,
            textStyle =
              MaterialTheme.typography.bodyMedium.copy(color = colors.text, lineHeight = 20.sp),
            cursorBrush = SolidColor(colors.accent),
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
              modifier = Modifier.size(24.dp).padding(top = 2.dp),
            ) {
              Icon(
                painterResource(R.drawable.ic_close),
                contentDescription = "Clear draft",
                modifier = Modifier.size(15.dp),
                tint = colors.muted,
              )
            }
          }
        }

        // Action controls row (+ / history on left, stop / send on right)
        Row(
          Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, bottom = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Pico + button for commands / actions
          Box {
            IconButton(
              onClick = { commandsOpen = true },
              modifier = Modifier.size(34.dp),
            ) {
              Icon(
                painterResource(R.drawable.ic_add),
                contentDescription = "Commands",
                modifier = Modifier.size(20.dp),
                tint = colors.muted,
              )
            }
            DropdownMenu(
              expanded = commandsOpen,
              onDismissRequest = { commandsOpen = false },
              properties = PopupProperties(focusable = false),
            ) {
              if (enabled && !pending) {
                DropdownMenuItem(
                  text = { Text("Continue") },
                  onClick = {
                    commandsOpen = false
                    onSend("continue", false)
                  },
                )
              }
              COMMON_SLASH_COMMANDS.forEach { item ->
                DropdownMenuItem(
                  enabled = !(agentWorking && item.command == "/new"),
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
                        if (agentWorking && item.command == "/new") "Stop the agent first"
                        else item.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                      )
                    }
                  },
                  onClick = {
                    commandsOpen = false
                    if (item.command == "/compact") {
                      confirmingCommand = "/compact"
                    } else {
                      // Adding a command prefix must not replace the user's recoverable draft.
                      input = "${item.command} "
                      focusRequester.requestFocus()
                    }
                  },
                )
              }
            }
          }

          // Prompt history button
          if (promptHistory.isNotEmpty()) {
            Box {
              IconButton(
                onClick = { historyOpen = true },
                modifier = Modifier.size(34.dp),
              ) {
                Icon(
                  painterResource(R.drawable.ic_history),
                  contentDescription = "Prompt history",
                  modifier = Modifier.size(18.dp),
                  tint = colors.muted,
                )
              }
              DropdownMenu(
                expanded = historyOpen,
                onDismissRequest = { historyOpen = false },
                properties = PopupProperties(focusable = false),
              ) {
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
                      focusRequester.requestFocus()
                    },
                  )
                }
              }
            }
          }

          Spacer(Modifier.weight(1f))

          // Stop agent action button
          if (onStop != null) {
            IconButton(onClick = onStop, modifier = Modifier.size(36.dp).testTag("stopAgent")) {
              Box(
                Modifier.size(20.dp)
                  .background(MaterialTheme.colorScheme.error.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center,
              ) {
                Box(
                  Modifier.size(10.dp)
                    .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                    .semantics { contentDescription = "Stop" }
                )
              }
            }
          }

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

          // Send / Pending button
          if (pending) {
            Box(
              Modifier.size(36.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .testTag("pendingIndicator")
                .semantics { contentDescription = "Sending" },
              contentAlignment = Alignment.Center,
            ) {
              Box(
                Modifier.size(12.dp)
                  .background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
              )
            }
          } else {
            Box(
              Modifier.size(36.dp)
                .background(
                  if (canSend) MaterialTheme.colorScheme.primary else colors.card2,
                  CircleShape,
                )
                .clickable(enabled = canSend) { send() }
                .testTag("sendPrompt"),
              contentAlignment = Alignment.Center,
            ) {
              Icon(
                painterResource(R.drawable.ic_arrow_downward),
                modifier = Modifier.size(18.dp).rotate(180f),
                contentDescription = "Send",
                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else colors.faint,
              )
            }
          }
        }

        // 3. Subtle horizontal divider
        Box(Modifier.fillMaxWidth().height(0.6.dp).background(colors.line.copy(alpha = 0.35f)))

        // 4. Pico bottom toolbar row (Model pill, Effort pill, Context tokens)
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          // Model selector pill
          Box {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = colors.card2.copy(alpha = 0.7f),
              modifier = Modifier.clickable { modelMenuOpen = true },
            ) {
              Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  shortModel(model ?: "Default"),
                  modifier = Modifier.testTag("composerModelLabel"),
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                  color = colors.text,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Icon(
                  painterResource(R.drawable.ic_expand_more),
                  contentDescription = null,
                  modifier = Modifier.size(12.dp),
                  tint = colors.muted,
                )
              }
            }
            DropdownMenu(
              expanded = modelMenuOpen,
              onDismissRequest = { modelMenuOpen = false },
              properties = PopupProperties(focusable = false),
            ) {
              DropdownMenuItem(
                text = {
                  Text(model ?: "Default model", style = MaterialTheme.typography.bodySmall)
                },
                onClick = { modelMenuOpen = false },
              )
            }
          }

          Spacer(Modifier.width(6.dp))

          // Effort / Thinking pill
          Box {
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = colors.card2.copy(alpha = 0.7f),
              modifier = Modifier.clickable { effortMenuOpen = true },
            ) {
              Row(
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
              ) {
                Text(
                  thinkingLevel ?: "High",
                  style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                  color = colors.text,
                  maxLines = 1,
                )
                Icon(
                  painterResource(R.drawable.ic_expand_more),
                  contentDescription = null,
                  modifier = Modifier.size(12.dp),
                  tint = colors.muted,
                )
              }
            }
            DropdownMenu(
              expanded = effortMenuOpen,
              onDismissRequest = { effortMenuOpen = false },
              properties = PopupProperties(focusable = false),
            ) {
              listOf("Off", "Low", "Medium", "High").forEach { level ->
                DropdownMenuItem(
                  text = { Text(level, style = MaterialTheme.typography.bodySmall) },
                  onClick = { effortMenuOpen = false },
                )
              }
            }
          }

          Spacer(Modifier.weight(1f))

          // Context usage / tokens
          if (percent != null) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              modifier = Modifier.padding(end = 4.dp),
            ) {
              Box(
                Modifier.size(width = 28.dp, height = 3.dp)
                  .background(colors.card2, RoundedCornerShape(1.5.dp))
                  .clip(RoundedCornerShape(1.5.dp))
              ) {
                Box(
                  Modifier.fillMaxHeight()
                    .fillMaxWidth(percent / 100f)
                    .background(
                      when {
                        percent >= 85 -> MaterialTheme.colorScheme.error
                        percent >= 70 -> colors.warn
                        else -> colors.accent
                      }
                    )
                )
              }
              Text(
                if (tokens != null) "$percent% · ${formatTokens(tokens)} / ${formatTokens(window)}"
                else "$percent%",
                style =
                  MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                  ),
                color = colors.muted,
              )
            }
          }
        }
      }
    }
  }
}

/** A model id as people say it: "opus 5.5" for `claude-opus-5-5`. */
internal fun shortModel(model: String): String =
  model
    .removePrefix("claude-")
    .replace("gemini-", "Gemini ")
    .replace(Regex("-(\\d+)-(\\d+)$"), " $1.$2")
