package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.R
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
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    SettingsHeading("Appearance")
    PreferenceRow(
      "Theme",
      "${appPalette.label} · ${themeMode.name.lowercase().replaceFirstChar { it.uppercase() }}",
      R.drawable.ic_settings,
    ) {
      if (onOpenAppearance != null) onOpenAppearance() else choosingTheme = true
    }
    SettingsHeading("Storage")
    PreferenceRow(
      "Saved conversations",
      offlineBytes?.let {
        "${android.text.format.Formatter.formatShortFileSize(context, it)} on this device · ${savedConversations.size} conversations"
      } ?: "Checking saved storage…",
      R.drawable.ic_chat,
      if (savedConversations.isNotEmpty() && onOpenSaved != null) ({ choosingSaved = true })
      else null,
    )
    Text(
      "Up to 20 recent conversations, limited to 32 MiB. Saved history is read-only and separate from temporary cache.",
      Modifier.padding(horizontal = 16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    offlineProblem?.let {
      Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
    PreferenceRow(
      "Clear cache",
      if (clearing) "Clearing temporary files…"
      else
        cacheBytes?.let {
          "Temporary files · ${android.text.format.Formatter.formatShortFileSize(context, it)}"
        } ?: "Temporary file size unavailable",
      R.drawable.ic_settings,
      if (clearing) null else ({ confirmingClear = true }),
    )
    cacheResult?.let {
      Text(it, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
    SettingsHeading("Connections")
    if (connections.isEmpty()) {
      Text(
        "No machines added",
        Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    connections.forEach { connection ->
      androidx.compose.runtime.key(connection.id) {
        PreferenceRow(connection.label, connection.status, R.drawable.ic_machine) {
          onConnectionDetails(connection.id)
        }
      }
    }
    SettingsHeading("About")
    PreferenceRow(
      "Cappuccino",
      "Read your agents' conversations and tool activity across your machines.",
      R.drawable.ic_chat,
    )
    PreferenceRow(
      "App version",
      "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.BUILD_TYPE}",
      R.drawable.ic_chat,
    )
    Text(
      "Your agent sessions stay on your machines.",
      Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PreferenceRow("Setup & troubleshooting", "Open the GitHub guide", R.drawable.ic_chat) {
      helpError =
        runCatching {
          uriHandler.openUri("https://github.com/azusachino/cappuccino#readme")
        }
          .exceptionOrNull()
          ?.let { "Couldn't open the guide. Visit github.com/azusachino/cappuccino." }
    }
    helpError?.let { Text(it, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
  }
  if (choosingSaved && onOpenSaved != null) {
    AlertDialog(
      onDismissRequest = { choosingSaved = false },
      title = { Text("Saved conversations") },
      text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
          savedConversations.forEach { saved ->
            PreferenceRow(
              saved.agent.label,
              "${savedMachineNames[saved.agent.machineId] ?: saved.agent.agent} · Saved ${formatConversationTimestamp(java.time.Instant.ofEpochMilli(saved.savedAt).toString(), timeZone)}",
              R.drawable.ic_chat,
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
    Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

@Composable
private fun PreferenceRow(
  title: String,
  summary: String,
  icon: Int,
  onClick: (() -> Unit)? = null,
) {
  val content: @Composable () -> Unit = {
    ListItem(
      headlineContent = { Text(title) },
      supportingContent = { Text(summary) },
      leadingContent = { Icon(painterResource(icon), contentDescription = null) },
    )
  }
  if (onClick != null) Surface(onClick = onClick, content = content) else content()
}
