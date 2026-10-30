package com.azusachino.cappuccino.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.R

internal data class SettingsConnection(
  val id: String,
  val label: String,
  val status: String,
  val bridgeVersion: String? = null,
  val protocol: Long? = null,
)

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
  val context = LocalContext.current
  val uriHandler = LocalUriHandler.current
  var helpError by rememberSaveable { mutableStateOf<String?>(null) }
  var showingThemePicker by rememberSaveable { mutableStateOf(false) }
  var showingAboutGuide by rememberSaveable { mutableStateOf(false) }

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
      SettingRow(
        "Theme",
        value = themeLabel,
        icon = {
          Icon(
            painterResource(R.drawable.ic_settings),
            contentDescription = null,
            tint = MaterialTheme.appColors.accent,
            modifier = Modifier.size(20.dp),
          )
        },
      ) {
        showingThemePicker = true
      }
    }
    SettingsCard("Notifications") { NotificationSettings() }
    SettingsCard("Connections") {
      if (connections.isEmpty()) SettingNote("No machines added")
      connections.forEachIndexed { index, connection ->
        if (index > 0) SettingsDivider()
        androidx.compose.runtime.key(connection.id) {
          val isConflict = connection.status == "Protocol conflict"
          val detailText =
            when {
              isConflict ->
                if (connection.protocol != null && connection.protocol > 1)
                  "Bridge uses v${connection.protocol}; app requires v1. Update Cappuccino."
                else if (connection.protocol != null && connection.protocol < 1)
                  "Bridge uses v${connection.protocol}; app requires v1. Update cappuccino-bridge."
                else "App requires bridge API v1. Update required."
              connection.bridgeVersion != null -> "Bridge v${connection.bridgeVersion}"
              else -> null
            }
          SettingRow(
            title = connection.label.takeUnless { it == "iroh.invalid" } ?: "Machine",
            value = connection.status,
            detail = detailText,
            warning = isConflict,
            icon = {
              Icon(
                painterResource(R.drawable.ic_machine),
                contentDescription = null,
                tint =
                  if (isConflict) MaterialTheme.appColors.err else MaterialTheme.appColors.accent,
                modifier = Modifier.size(20.dp),
              )
            },
          ) {
            onConnectionDetails(connection.id)
          }
        }
      }
    }
    SettingsCard("About") {
      SettingRow(
        title = "About Cappuccino",
        detail = "Overview and basic guide",
        onClick = { showingAboutGuide = true },
      )
      SettingsDivider(indented = false)
      SettingRow(
        title = "Version",
        value = BuildConfig.VERSION_NAME,
        showChevron = false,
        onClick = {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Version", BuildConfig.VERSION_NAME))
          Toast.makeText(context, "Copied version", Toast.LENGTH_SHORT).show()
        },
      )
      SettingsDivider(indented = false)
      SettingRow(
        title = "Bridge API",
        value = "v1",
        showChevron = false,
        onClick = {
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Bridge API", "v1"))
          Toast.makeText(context, "Copied Bridge API version", Toast.LENGTH_SHORT).show()
        },
      )
      SettingsDivider(indented = false)
      SettingRow(
        title = "Documentation",
        value = "GitHub",
        onClick = {
          helpError =
            runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino#readme") }
              .exceptionOrNull()
              ?.let { "Couldn't open documentation." }
        },
      )
      SettingsDivider(indented = false)
      SettingRow(
        title = "Iroh setup",
        value = "Docs",
        onClick = {
          helpError =
            runCatching {
              uriHandler.openUri(
                "https://github.com/azusachino/cappuccino/blob/main/docs/iroh-setup.md"
              )
            }
              .exceptionOrNull()
              ?.let { "Couldn't open iroh setup guide." }
        },
      )
      SettingsDivider(indented = false)
      SettingRow(
        title = "Source",
        value = "GitHub",
        onClick = {
          helpError =
            runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino") }
              .exceptionOrNull()
              ?.let { "Couldn't open GitHub." }
        },
      )
    }
    helpError?.let { SettingNote(it, warning = true) }
  }

  if (showingAboutGuide) {
    AboutCappuccinoSheet(onDismiss = { showingAboutGuide = false })
  }
}

/** 0.5dp hairline between rows inside a group card, indented to match row text. */
@Composable
internal fun SettingsDivider(indented: Boolean = true) {
  HorizontalDivider(
    Modifier.padding(start = if (indented) 58.dp else 16.dp, end = 16.dp),
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
  showChevron: Boolean = true,
  icon: (@Composable () -> Unit)? = null,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)?,
) {
  val colors = MaterialTheme.appColors
  Row(
    Modifier.fillMaxWidth()
      .heightIn(min = 48.dp)
      .then(
        if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier
      )
      .padding(horizontal = 16.dp, vertical = 9.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (icon != null) {
      Box(
        Modifier.size(28.dp),
        contentAlignment = Alignment.Center,
      ) {
        icon()
      }
      Spacer(Modifier.width(14.dp))
    }
    Column(Modifier.weight(1f)) {
      Text(title, style = MaterialTheme.typography.bodyMedium, color = colors.text)
      detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.muted) }
    }
    Spacer(Modifier.width(12.dp))
    Row(
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (value != null) {
        Text(
          value,
          Modifier.widthIn(max = 200.dp),
          style = MaterialTheme.typography.bodySmall,
          color = if (warning) colors.warn else colors.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.End,
        )
      }
      trailing?.invoke()
      if (onClick != null && trailing == null && showChevron) {
        Text(
          "›",
          modifier = Modifier.padding(start = 6.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.faint,
        )
      }
    }
  }
}

/** Bottom sheet explaining the app, basic instructions, and resources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutCappuccinoSheet(onDismiss: () -> Unit) {
  val colors = MaterialTheme.appColors
  val uriHandler = LocalUriHandler.current

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      Modifier.fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp)
        .padding(bottom = 36.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          "Cappuccino",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold,
          color = colors.text,
        )
        Text(
          "Version ${BuildConfig.VERSION_NAME} • Bridge API v1",
          style = MaterialTheme.typography.bodySmall,
          color = colors.muted,
        )
      }

      Text(
        "Cappuccino is a companion app for coding agents running in Herdr terminal panes on your development machines.",
        style = MaterialTheme.typography.bodyMedium,
        color = colors.text,
        lineHeight = 20.sp,
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

      Text(
        "BASIC INSTRUCTIONS",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.5.sp,
      )

      AboutGuideItem(
        step = "1",
        title = "Pair your machine",
        description =
          "Run 'cappuccino-bridge pair' on your computer to show a pairing QR code, then tap + on the Machines tab to scan it. Or connect via your Tailnet address.",
      )
      AboutGuideItem(
        step = "2",
        title = "Monitor running agents",
        description =
          "See active agents across all paired machines. Tap any session to read live output, reasoning, and tool executions in real time.",
      )
      AboutGuideItem(
        step = "3",
        title = "Steer & approve",
        description =
          "Confirm pending tool approvals, reply to questions, or prompt your agent while away from your desk.",
      )
      AboutGuideItem(
        step = "4",
        title = "Always running",
        description =
          "Agents run inside Herdr on your host machine. Disconnecting or quitting Cappuccino never terminates your agents or loses session state.",
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

      Text(
        "RESOURCES",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 0.5.sp,
      )

      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        OutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino#readme") }
          },
        ) {
          Text("Docs")
        }
        OutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            runCatching {
              uriHandler.openUri(
                "https://github.com/azusachino/cappuccino/blob/main/docs/iroh-setup.md"
              )
            }
          },
        ) {
          Text("Iroh")
        }
        OutlinedButton(
          modifier = Modifier.weight(1f),
          onClick = {
            runCatching { uriHandler.openUri("https://github.com/azusachino/cappuccino") }
          },
        ) {
          Text("GitHub")
        }
      }
    }
  }
}

@Composable
private fun AboutGuideItem(step: String, title: String, description: String) {
  val colors = MaterialTheme.appColors
  Row(
    Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.primaryContainer,
      modifier = Modifier.size(24.dp),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Text(
          step,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
      }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        title,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = colors.text,
      )
      Text(
        description,
        style = MaterialTheme.typography.bodySmall,
        color = colors.muted,
        lineHeight = 18.sp,
      )
    }
  }
}
