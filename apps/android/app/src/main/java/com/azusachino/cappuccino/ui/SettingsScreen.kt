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

internal data class SettingsConnection(val id: String, val label: String, val status: String)

@Composable
internal fun SettingsScreen(
  themeMode: ThemeMode,
  onSelectTheme: (ThemeMode) -> Unit,
  onManageMachines: () -> Unit,
  connections: List<SettingsConnection>,
  onConnectionDetails: (String) -> Unit,
) {
  var choosingTheme by rememberSaveable { mutableStateOf(false) }
  Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
    SettingsHeading("Appearance")
    PreferenceRow(
      "Theme",
      themeMode.name.lowercase().replaceFirstChar { it.uppercase() },
      R.drawable.ic_settings,
    ) {
      choosingTheme = true
    }
    SettingsHeading("Machines")
    PreferenceRow(
      "Manage machines",
      "Names, connections and attached agents",
      R.drawable.ic_machine,
      onManageMachines,
    )
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
    PreferenceRow("App version", BuildConfig.VERSION_NAME, R.drawable.ic_chat)
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
      trailingContent =
        if (onClick != null) ({ Text("›", style = MaterialTheme.typography.titleLarge) }) else null,
    )
  }
  if (onClick != null) Surface(onClick = onClick, content = content) else content()
}
