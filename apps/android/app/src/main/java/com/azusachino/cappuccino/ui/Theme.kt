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
import androidx.compose.ui.graphics.luminance

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

// The app palette is derived from the launcher artwork: dark navy backdrop
// (#1B1E2B), periwinkle hair (#A9B2E8), eye blue (#3563B0) and cheek rose
// (#F2B7C4). Surface roles use navy tints in dark mode and lavender-tinted
// whites in light mode.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF4A5AA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDDE1F8),
    onPrimaryContainer = Color(0xFF262F63),
    secondary = Color(0xFF5674B4),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE6F8),
    onSecondaryContainer = Color(0xFF273A5C),
    tertiary = Color(0xFFB05A70),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDEE5),
    onTertiaryContainer = Color(0xFF4E2230),
    background = Color(0xFFF3F4FA),
    onBackground = Color(0xFF262A3A),
    surface = Color(0xFFF3F4FA),
    onSurface = Color(0xFF262A3A),
    surfaceVariant = Color(0xFFE1E3F0),
    onSurfaceVariant = Color(0xFF565C74),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF0F1F8),
    surfaceContainer = Color(0xFFE9EBF5),
    surfaceContainerHigh = Color(0xFFE1E3F0),
    surfaceContainerHighest = Color(0xFFD5D9EA),
    surfaceDim = Color(0xFFD5D9EA),
    surfaceBright = Color(0xFFF3F4FA),
    outline = Color(0xFF6B7290),
    outlineVariant = Color(0xFFC9CDE0),
    error = Color(0xFFB3424E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDEE5),
    onErrorContainer = Color(0xFF4E2230),
    surfaceTint = Color(0xFF4A5AA8),
    inverseSurface = Color(0xFF262A3A),
    inverseOnSurface = Color(0xFFF3F4FA),
    inversePrimary = Color(0xFFA9B2E8),
  )
val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFA9B2E8),
    onPrimary = Color(0xFF1B2030),
    primaryContainer = Color(0xFF39406B),
    onPrimaryContainer = Color(0xFFDDE1F8),
    secondary = Color(0xFF7FA0D6),
    onSecondary = Color(0xFF10131F),
    secondaryContainer = Color(0xFF2C3A56),
    onSecondaryContainer = Color(0xFFD6E2F6),
    tertiary = Color(0xFFF2B7C4),
    onTertiary = Color(0xFF2E141B),
    tertiaryContainer = Color(0xFF4A2E38),
    onTertiaryContainer = Color(0xFFFBDEE5),
    background = Color(0xFF1B1E2B),
    onBackground = Color(0xFFE8EAF4),
    surface = Color(0xFF1B1E2B),
    onSurface = Color(0xFFE8EAF4),
    surfaceVariant = Color(0xFF2A2F42),
    onSurfaceVariant = Color(0xFFB9BFDA),
    surfaceContainerLowest = Color(0xFF161926),
    surfaceContainerLow = Color(0xFF1E2230),
    surfaceContainer = Color(0xFF262B3D),
    surfaceContainerHigh = Color(0xFF2A2F42),
    surfaceContainerHighest = Color(0xFF323852),
    surfaceDim = Color(0xFF161926),
    surfaceBright = Color(0xFF323852),
    outline = Color(0xFF8C93B0),
    outlineVariant = Color(0xFF3A4059),
    error = Color(0xFFF09CA8),
    onError = Color(0xFF2E1015),
    errorContainer = Color(0xFF4E2530),
    onErrorContainer = Color(0xFFFBDEE5),
    surfaceTint = Color(0xFFA9B2E8),
    inverseSurface = Color(0xFFE8EAF4),
    inverseOnSurface = Color(0xFF1B1E2B),
    inversePrimary = Color(0xFF4A5AA8),
  )

internal data class AgentStatusStyle(
  val background: Color,
  val foreground: Color,
  val label: String,
)

internal fun agentStatusStyle(status: String, dark: Boolean): AgentStatusStyle {
  val (background, label) =
    when (status.lowercase()) {
      "working" -> (if (dark) Color(0xFF7FA0D6) else Color(0xFF3563B0)) to "Working"
      "idle" -> (if (dark) Color(0xFFE8C48A) else Color(0xFFB07A2E)) to "Idle"
      "done" -> (if (dark) Color(0xFFA3D9B0) else Color(0xFF3E8E55)) to "Done"
      "blocked" -> (if (dark) Color(0xFFF09CA8) else Color(0xFFB3424E)) to "Blocked"
      else -> (if (dark) Color(0xFF323852) else Color(0xFFD5D9EA)) to "Unknown"
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
