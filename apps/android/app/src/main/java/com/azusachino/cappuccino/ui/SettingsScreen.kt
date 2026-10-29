package com.azusachino.cappuccino.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.R

internal data class SettingsConnection(val id: String, val label: String, val status: String)

@Composable
internal fun SettingsScreen(
  themeMode: ThemeMode,
  onSelectTheme: (ThemeMode) -> Unit,
  onReloadMachines: () -> Unit,
  connections: List<SettingsConnection>,
  onConnectionDetails: (String) -> Unit,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  scrollState: ScrollState = rememberScrollState(),
) {
  val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
  var helpError by rememberSaveable { mutableStateOf<String?>(null) }
  var showingThemePicker by rememberSaveable { mutableStateOf(false) }

  if (showingThemePicker) {
    AlertDialog(
      onDismissRequest = { showingThemePicker = false },
      title = { Text("Theme") },
      text = {
        Column(Modifier.selectableGroup()) {
          ThemeMode.entries.forEach { mode ->
            val label = mode.name.lowercase().replaceFirstChar { it.uppercase() }
            val isSelected = themeMode == mode
            Row(
              Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .selectable(
                  selected = isSelected,
                  role = Role.RadioButton,
                  onClick = {
                    onSelectTheme(mode)
                    showingThemePicker = false
                  },
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              RadioButton(selected = isSelected, onClick = null)
              Text(label, style = MaterialTheme.typography.bodyLarge)
            }
          }
        }
      },
      confirmButton = { TextButton(onClick = { showingThemePicker = false }) { Text("Cancel") } },
    )
  }

  Column(
    Modifier.fillMaxWidth()
      .verticalScroll(scrollState)
      .padding(
        top = contentPadding.calculateTopPadding(),
        bottom = contentPadding.calculateBottomPadding() + 12.dp,
      ),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
      stringResource(R.string.settings),
      Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
      style = MaterialTheme.typography.headlineLarge,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.appColors.text,
    )
    SettingsCard("Appearance") {
      val themeLabel =
        when (themeMode) {
          ThemeMode.SYSTEM -> "System"
          ThemeMode.LIGHT -> "Light"
          ThemeMode.DARK -> "Dark"
        }
      SettingRow("Theme", value = themeLabel) { showingThemePicker = true }
    }
    SettingsCard("Notifications") { NotificationSettings() }
    SettingsCard("Connections") {
      if (connections.isEmpty()) SettingNote("No machines added")
      connections.forEachIndexed { index, connection ->
        if (index > 0) SettingsDivider()
        androidx.compose.runtime.key(connection.id) {
          SettingRow(connection.label, value = connection.status) {
            onConnectionDetails(connection.id)
          }
        }
      }
    }
    SettingsCard("Help & About") {
      SettingRow("Setup guide", value = "GitHub") {
        helpError =
          runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino#readme") }
            .exceptionOrNull()
            ?.let { "Couldn't open the guide. Visit github.com/azusachino/cappuccino." }
      }
      SettingsDivider()
      SettingRow("Herdr", value = "herdr.dev") {
        helpError =
          runCatching { uriHandler.openUri("https://herdr.dev") }
            .exceptionOrNull()
            ?.let { "Couldn't open herdr.dev." }
      }
      SettingsDivider()
      SettingRow("Source", value = "GitHub") {
        helpError =
          runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino") }
            .exceptionOrNull()
            ?.let { "Couldn't open github.com/azusachino/cappuccino." }
      }
      SettingsDivider()
      SettingRow("License", value = "GPL-3.0", onClick = null)
      SettingsDivider()
      SettingRow("Version", value = BuildConfig.VERSION_NAME, onClick = null)
    }
    helpError?.let { SettingNote(it, warning = true) }
  }
}

/** 0.5dp hairline between rows inside a group card. */
@Composable
private fun SettingsDivider() {
  HorizontalDivider(
    Modifier.padding(horizontal = 16.dp),
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
  )
}

/** Uppercase section header, latte-style: quiet, letterspaced, above the card. */
@Composable
private fun SettingsSectionHeader(title: String) {
  Text(
    title.uppercase(),
    Modifier.padding(start = 28.dp, end = 24.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
    color = MaterialTheme.colorScheme.primary,
  )
}

/** One settings group: an elevated liquid glass card holding its rows. */
@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
  SettingsSectionHeader(title)
  Column(
    Modifier.fillMaxWidth()
      .padding(horizontal = 16.dp)
      .glassSurface(shape = RoundedCornerShape(16.dp), isElevated = true)
      .padding(vertical = 4.dp)
  ) {
    content()
  }
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
      .heightIn(min = 48.dp)
      .then(
        if (onClick != null)
          Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
        else Modifier
      )
      .padding(horizontal = 16.dp, vertical = 8.dp),
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
