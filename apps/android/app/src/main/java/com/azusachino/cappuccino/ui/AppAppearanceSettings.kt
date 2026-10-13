package com.azusachino.cappuccino.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppAppearanceSettings(
  mode: ThemeMode,
  palette: AppPalette,
  onMode: (ThemeMode) -> Unit,
  onPalette: (AppPalette) -> Unit,
) {
  val dark = resolveIsDark(mode, isSystemInDarkTheme())
  val context = LocalContext.current
  val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  val dynamicLight =
    if (dynamicSupported) dynamicLightColorScheme(context) else FallbackLightColorScheme
  val dynamicDark =
    if (dynamicSupported) dynamicDarkColorScheme(context) else FallbackDarkColorScheme
  Column(
    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Text("Theme", style = MaterialTheme.typography.labelLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
      ThemeMode.entries.forEachIndexed { index, value ->
        SegmentedButton(
          selected = mode == value,
          onClick = { onMode(value) },
          shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
        ) {
          Text(value.name.lowercase().replaceFirstChar { it.uppercase() })
        }
      }
    }
    Text("Palette", style = MaterialTheme.typography.labelLarge)
    AppPalette.entries.chunked(3).forEach { choices ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
          val scheme =
            appColorScheme(choice, dark, Build.VERSION.SDK_INT, dynamicLight, dynamicDark)
          Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MaterialTheme(colorScheme = scheme) {
              Card(
                onClick = { onPalette(choice) },
                enabled = choice != AppPalette.DYNAMIC || dynamicSupported,
                modifier =
                  Modifier.fillMaxWidth().semantics {
                    contentDescription = "${choice.label} palette"
                    role = Role.RadioButton
                    selected = palette == choice
                  },
                border =
                  BorderStroke(
                    if (palette == choice) 3.dp else 1.dp,
                    if (palette == choice) scheme.primary else scheme.outlineVariant,
                  ),
                colors = CardDefaults.cardColors(containerColor = scheme.surface),
              ) {
                Column(
                  Modifier.fillMaxWidth().heightIn(min = 156.dp).padding(8.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  Text(
                    if (palette == choice) "Pi   ✓" else "Pi",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                  )
                  HorizontalDivider()
                  Surface(color = scheme.surfaceContainerLow) {
                    Text(
                      "› You",
                      Modifier.fillMaxWidth().padding(8.dp),
                      style = MaterialTheme.typography.labelSmall,
                      color = scheme.onSurface,
                    )
                  }
                  Text(
                    "Session",
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                  )
                  Surface(color = scheme.primaryContainer) {
                    Text(
                      "Message",
                      Modifier.fillMaxWidth().padding(6.dp),
                      color = scheme.onPrimaryContainer,
                      style = MaterialTheme.typography.labelSmall,
                    )
                  }
                }
              }
            }
            Text(choice.label, style = MaterialTheme.typography.labelMedium)
          }
        }
        repeat(3 - choices.size) { Spacer(Modifier.weight(1f)) }
      }
    }
    if (palette == AppPalette.DYNAMIC && !dynamicSupported)
      Text(
        "Dynamic requires Android 12; Default is used on this device.",
        style = MaterialTheme.typography.bodySmall,
      )
  }
}
