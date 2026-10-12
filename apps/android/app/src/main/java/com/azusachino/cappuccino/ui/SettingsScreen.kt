package com.azusachino.cappuccino.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.BuildConfig
import kotlinx.coroutines.launch

internal data class SettingsConnection(val id: String, val label: String, val status: String)

@Composable
internal fun SettingsScreen(
  themeMode: ThemeMode,
  onSelectTheme: (ThemeMode) -> Unit,
  onReloadMachines: () -> Unit,
  connections: List<SettingsConnection>,
  onConnectionDetails: (String) -> Unit,
  appPalette: AppPalette = AppPalette.CATPPUCCIN,
  onOpenAppearance: (() -> Unit)? = null,
  savedConversations: List<com.azusachino.cappuccino.io.SavedConversationInfo> = emptyList(),
  savedMachineNames: Map<java.util.UUID, String> = emptyMap(),
  offlineBytes: Long? = null,
  offlineProblem: String? = null,
  onOpenSaved: ((java.util.UUID, String) -> Unit)? = null,
  timeZone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
) {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  var helpError by rememberSaveable { mutableStateOf<String?>(null) }
  var choosingTheme by rememberSaveable { mutableStateOf(false) }
  var confirmingClear by rememberSaveable { mutableStateOf(false) }
  var choosingSaved by rememberSaveable { mutableStateOf(false) }
  var clearing by androidx.compose.runtime.remember { mutableStateOf(false) }
  var cacheBytes by androidx.compose.runtime.remember { mutableStateOf<Long?>(null) }
  var cacheResult by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
  val context = androidx.compose.ui.platform.LocalContext.current
  val scope = androidx.compose.runtime.rememberCoroutineScope()
  androidx.compose.runtime.LaunchedEffect(Unit) {
    cacheBytes =
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { com.azusachino.cappuccino.io.temporaryCacheBytes(context.cacheDir) }
          .getOrNull()
      }
  }
  val formatBytes = { bytes: Long ->
    android.text.format.Formatter.formatShortFileSize(context, bytes)
  }
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    SettingsHeading("Appearance")
    SettingRow(
      "Theme",
      value =
        "${appPalette.label} · ${themeMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
    ) {
      if (onOpenAppearance != null) onOpenAppearance() else choosingTheme = true
    }
    SettingsHeading("Notifications")
    NotificationSettings()
    SettingsHeading("Storage")
    SettingRow(
      "Saved conversations",
      value =
        offlineBytes?.let { "${savedConversations.size} · ${formatBytes(it)}" } ?: "Checking…",
      onClick =
        if (savedConversations.isNotEmpty() && onOpenSaved != null) ({ choosingSaved = true })
        else null,
    )
    offlineProblem?.let { SettingNote(it, warning = true) }
    SettingRow(
      "Temporary cache",
      value =
        when {
          clearing -> "Clearing…"
          else -> cacheBytes?.let(formatBytes) ?: "Unavailable"
        },
      onClick = if (clearing || cacheBytes == 0L) null else ({ confirmingClear = true }),
    )
    cacheResult?.let { SettingNote(it) }
    SettingNote("Keeps the 20 most recent conversations, up to 32 MB, read-only.")
    SettingsHeading("Connections")
    if (connections.isEmpty()) SettingNote("No machines added")
    connections.forEach { connection ->
      androidx.compose.runtime.key(connection.id) {
        SettingRow(connection.label, value = connection.status) {
          onConnectionDetails(connection.id)
        }
      }
    }
    SettingsHeading("About")
    SettingRow(
      "Version",
      value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
      onClick = null,
    )
    SettingRow("Setup guide", value = "GitHub") {
      helpError =
        runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino#readme") }
          .exceptionOrNull()
          ?.let { "Couldn't open the guide. Visit github.com/azusachino/cappuccino." }
    }
    helpError?.let { SettingNote(it, warning = true) }
    SettingNote("Agent sessions stay on your machines; this app only reads and relays them.")
  }
  if (choosingSaved && onOpenSaved != null) {
    AlertDialog(
      onDismissRequest = { choosingSaved = false },
      title = { Text("Saved conversations") },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          savedConversations.forEach { saved ->
            SettingRow(
              saved.agent.label,
              detail =
                "${savedMachineNames[saved.agent.machineId] ?: saved.agent.agent} · Saved ${formatConversationTimestamp(java.time.Instant.ofEpochMilli(saved.savedAt).toString(), timeZone)}",
            ) {
              choosingSaved = false
              onOpenSaved(saved.agent.machineId, saved.conversationId)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { choosingSaved = false }) { Text("Close") } },
    )
  }
  if (confirmingClear) {
    AlertDialog(
      onDismissRequest = { confirmingClear = false },
      title = { Text("Clear temporary cache?") },
      text = {
        Text(
          "Delete temporary files and refresh machine lists? Saved conversations, machines, drafts and reading preferences stay. Remote sessions are unchanged."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingClear = false
            clearing = true
            scope.launch {
              try {
                cacheBytes =
                  kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.azusachino.cappuccino.io.temporaryCacheBytes(context.cacheDir, clear = true)
                    com.azusachino.cappuccino.io.temporaryCacheBytes(context.cacheDir)
                  }
                onReloadMachines()
                cacheResult = "Temporary files cleared."
              } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                cacheResult = "Couldn't clear all temporary files. Try again."
              } finally {
                clearing = false
              }
            }
          }
        ) {
          Text("Clear cache")
        }
      },
      dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text("Cancel") } },
    )
  }
  if (choosingTheme) {
    AlertDialog(
      onDismissRequest = { choosingTheme = false },
      title = { Text("Theme") },
      text = {
        Column {
          ThemeMode.entries.forEach { mode ->
            Surface(
              color = androidx.compose.ui.graphics.Color.Transparent,
              onClick = {
                onSelectTheme(mode)
                choosingTheme = false
              },
            ) {
              ListItem(
                colors =
                  androidx.compose.material3.ListItemDefaults.colors(
                    containerColor = androidx.compose.ui.graphics.Color.Transparent
                  ),
                headlineContent = {
                  Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                },
                leadingContent = { RadioButton(selected = themeMode == mode, onClick = null) },
              )
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { choosingTheme = false }) { Text("Cancel") } },
    )
  }
}

@Composable
private fun SettingsHeading(title: String) {
  Text(
    title,
    Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics {
      heading()
    },
    style = MaterialTheme.typography.labelMedium,
    color = MaterialTheme.appColors.accent,
  )
}

/** A short explanation under a group, only where a row needs one. */
@Composable
internal fun SettingNote(text: String, warning: Boolean = false) {
  Text(
    text,
    Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    style = MaterialTheme.typography.bodySmall,
    color = if (warning) MaterialTheme.appColors.warn else MaterialTheme.appColors.muted,
  )
}

/**
 * One setting: its name, an optional line about it, and its current value or control at the end.
 */
@Composable
internal fun SettingRow(
  title: String,
  value: String? = null,
  detail: String? = null,
  warning: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)?,
) {
  val colors = MaterialTheme.appColors
  androidx.compose.foundation.layout.Row(
    Modifier.fillMaxWidth()
      .heightIn(min = 52.dp)
      .then(
        if (onClick != null)
          Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        else Modifier
      )
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
  ) {
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.text)
      detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
    }
    value?.let {
      Text(
        it,
        Modifier.widthIn(max = 200.dp),
        style = MaterialTheme.typography.bodySmall,
        color = if (warning) colors.warn else colors.muted,
        maxLines = 2,
        textAlign = androidx.compose.ui.text.style.TextAlign.End,
      )
    }
    trailing?.invoke()
    if (onClick != null && trailing == null)
      Text("›", style = MaterialTheme.typography.bodyMedium, color = colors.faint)
  }
}
