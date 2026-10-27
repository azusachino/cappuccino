package com.azusachino.cappuccino.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

// Catppuccin palette 1.8.0: https://github.com/catppuccin/palette
// Material container roles use accent tints; small text uses the stronger text tone.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF8839EF),
    onPrimary = Color(0xFFEFF1F5),
    primaryContainer = Color(0xFF8839EF).copy(alpha = 0.12f).compositeOver(Color(0xFFEFF1F5)),
    onPrimaryContainer = Color(0xFF4C4F69),
    secondary = Color(0xFF7287FD),
    onSecondary = Color(0xFF11111B),
    secondaryContainer = Color(0xFFE6E9EF),
    onSecondaryContainer = Color(0xFF4C4F69),
    tertiary = Color(0xFF40A02B),
    onTertiary = Color(0xFF11111B),
    tertiaryContainer = Color(0xFFEFF1F5),
    onTertiaryContainer = Color(0xFF4C4F69),
    background = Color(0xFFEFF1F5),
    onBackground = Color(0xFF4C4F69),
    surface = Color(0xFFEFF1F5),
    onSurface = Color(0xFF4C4F69),
    surfaceVariant = Color(0xFFDCE0E8),
    onSurfaceVariant = Color(0xFF4C4F69),
    surfaceContainerLowest = Color(0xFFEFF1F5),
    surfaceContainerLow = Color(0xFFE6E9EF),
    surfaceContainer = Color(0xFFE6E9EF),
    surfaceContainerHigh = Color(0xFFDCE0E8),
    surfaceContainerHighest = Color(0xFFCCD0DA),
    surfaceDim = Color(0xFFCCD0DA),
    surfaceBright = Color(0xFFEFF1F5),
    outline = Color(0xFF6C6F85),
    outlineVariant = Color(0xFFBCC0CC),
    error = Color(0xFFD20F39),
    onError = Color.White,
    errorContainer = Color(0xFFD20F39).copy(alpha = 0.1f).compositeOver(Color(0xFFEFF1F5)),
    onErrorContainer = Color(0xFF4C4F69),
    surfaceTint = Color(0xFF8839EF),
    inverseSurface = Color(0xFF313244),
    inverseOnSurface = Color(0xFFCDD6F4),
    inversePrimary = Color(0xFFCBA6F7),
  )
val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFCBA6F7),
    onPrimary = Color(0xFF11111B),
    primaryContainer = Color(0xFFCBA6F7).copy(alpha = 0.15f).compositeOver(Color(0xFF1E1E2E)),
    onPrimaryContainer = Color(0xFFCDD6F4),
    secondary = Color(0xFFB4BEFE),
    onSecondary = Color(0xFF11111B),
    secondaryContainer = Color(0xFF313244),
    onSecondaryContainer = Color(0xFFCDD6F4),
    tertiary = Color(0xFFA6E3A1),
    onTertiary = Color(0xFF11111B),
    tertiaryContainer = Color(0xFF313244),
    onTertiaryContainer = Color(0xFFCDD6F4),
    background = Color(0xFF1E1E2E),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF45475A),
    onSurfaceVariant = Color(0xFFBAC2DE),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF313244),
    surfaceContainerHigh = Color(0xFF313244),
    surfaceContainerHighest = Color(0xFF45475A),
    surfaceDim = Color(0xFF181825),
    surfaceBright = Color(0xFF45475A),
    outline = Color(0xFF9399B2),
    outlineVariant = Color(0xFF585B70),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF11111B),
    errorContainer = Color(0xFFF38BA8).copy(alpha = 0.15f).compositeOver(Color(0xFF1E1E2E)),
    onErrorContainer = Color(0xFFCDD6F4),
    surfaceTint = Color(0xFFCBA6F7),
    inverseSurface = Color(0xFFEFF1F5),
    inverseOnSurface = Color(0xFF4C4F69),
    inversePrimary = Color(0xFF8839EF),
  )

internal data class AgentStatusStyle(
  val background: Color,
  val foreground: Color,
  val label: String,
)

internal fun agentStatusStyle(status: String, dark: Boolean): AgentStatusStyle {
  val (background, label) =
    when (status.lowercase()) {
      "working" -> (if (dark) Color(0xFF89B4FA) else Color(0xFF1E66F5)) to "Working"
      "idle" -> (if (dark) Color(0xFFFAB387) else Color(0xFFFE640B)) to "Idle"
      "done" -> (if (dark) Color(0xFFA6E3A1) else Color(0xFF40A02B)) to "Done"
      "blocked" -> (if (dark) Color(0xFFF38BA8) else Color(0xFFD20F39)) to "Blocked"
      else -> (if (dark) Color(0xFF313244) else Color(0xFFDCE0E8)) to "Unknown"
    }
  val foreground =
    if ((1.05f / (background.luminance() + 0.05f)) >= 4.5f) Color.White else Color(0xFF11111B)
  return AgentStatusStyle(background, foreground, label)
}

fun resolveIsDark(themeMode: ThemeMode, systemDark: Boolean): Boolean =
  when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

@Composable
fun CappuccinoTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  onResolvedDarkChanged: ((Boolean) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val isDark = resolveIsDark(themeMode, isSystemInDarkTheme())
  val themeSink by rememberUpdatedState(onResolvedDarkChanged)
  LaunchedEffect(isDark) { themeSink?.invoke(isDark) }
  val selected =
    androidx.compose.runtime.remember(isDark) {
      if (isDark) FallbackDarkColorScheme else FallbackLightColorScheme
    }
  val colors = androidx.compose.runtime.remember(selected, isDark) { appColors(selected, isDark) }
  val surfaceScheme =
    androidx.compose.runtime.remember(selected, isDark) { flatSurfaceScheme(selected, isDark) }
  androidx.compose.runtime.CompositionLocalProvider(LocalAppColors provides colors) {
    MaterialTheme(
      colorScheme = surfaceScheme,
      typography = AppTypography,
      shapes = AppShapes,
      content = content,
    )
  }
}
