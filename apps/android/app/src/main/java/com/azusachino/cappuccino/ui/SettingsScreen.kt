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
) {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  var helpError by rememberSaveable { mutableStateOf<String?>(null) }
  var choosingTheme by rememberSaveable { mutableStateOf(false) }
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
      value = BuildConfig.VERSION_NAME,
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
