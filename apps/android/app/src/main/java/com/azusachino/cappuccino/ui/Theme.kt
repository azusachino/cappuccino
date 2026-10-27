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

// The palette is Chino (Kafū Chino): her light-blue hair is the primary,
// her deep eye blue anchors it, and her Rabbit House uniform gives the
// canvas — near-white in light mode, navy-charcoal in dark mode. One
// accent everywhere; rose (her hairpin) is the only second color. Status
// hues stay semantic so meaning never depends on the theme.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF35639F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE9F7),
    onPrimaryContainer = Color(0xFF1F3A61),
    secondary = Color(0xFF47648E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDFE9F5),
    onSecondaryContainer = Color(0xFF26374F),
    tertiary = Color(0xFFB05A70),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDEE5),
    onTertiaryContainer = Color(0xFF4E2230),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF2A3442),
    surface = Color(0xFFF7F8FA),
    onSurface = Color(0xFF2A3442),
    surfaceVariant = Color(0xFFE7EBF1),
    onSurfaceVariant = Color(0xFF5A6472),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF2F4F8),
    surfaceContainer = Color(0xFFEEF1F5),
    surfaceContainerHigh = Color(0xFFE7EBF1),
    surfaceContainerHighest = Color(0xFFDDE2EA),
    surfaceDim = Color(0xFFDDE2EA),
    surfaceBright = Color(0xFFF7F8FA),
    outline = Color(0xFF6E7885),
    outlineVariant = Color(0xFFDFE3EB),
    error = Color(0xFFB3424E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDEE5),
    onErrorContainer = Color(0xFF4E2230),
    surfaceTint = Color(0xFF35639F),
    inverseSurface = Color(0xFF2A3442),
    inverseOnSurface = Color(0xFFF7F8FA),
    inversePrimary = Color(0xFF9CC4EE),
  )

val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF9CC4EE),
    onPrimary = Color(0xFF16222F),
    primaryContainer = Color(0xFF2C405E),
    onPrimaryContainer = Color(0xFFD3E4F7),
    secondary = Color(0xFF8AA9CE),
    onSecondary = Color(0xFF141C26),
    secondaryContainer = Color(0xFF28374A),
    onSecondaryContainer = Color(0xFFD8E4F2),
    tertiary = Color(0xFFE8A9B8),
    onTertiary = Color(0xFF2E141B),
    tertiaryContainer = Color(0xFF4A2E38),
    onTertiaryContainer = Color(0xFFFBDEE5),
    background = Color(0xFF1B202B),
    onBackground = Color(0xFFE3E8EF),
    surface = Color(0xFF1B202B),
    onSurface = Color(0xFFE3E8EF),
    surfaceVariant = Color(0xFF272F3C),
    onSurfaceVariant = Color(0xFFB4BDC9),
    surfaceContainerLowest = Color(0xFF161B24),
    surfaceContainerLow = Color(0xFF20252F),
    surfaceContainer = Color(0xFF272F3C),
    surfaceContainerHigh = Color(0xFF2D3644),
    surfaceContainerHighest = Color(0xFF35404F),
    surfaceDim = Color(0xFF161B24),
    surfaceBright = Color(0xFF35404F),
    outline = Color(0xFF85909E),
    outlineVariant = Color(0xFF3A4351),
    error = Color(0xFFF09CA8),
    onError = Color(0xFF2E1015),
    errorContainer = Color(0xFF4E2530),
    onErrorContainer = Color(0xFFFBDEE5),
    surfaceTint = Color(0xFF9CC4EE),
    inverseSurface = Color(0xFFE3E8EF),
    inverseOnSurface = Color(0xFF1B202B),
    inversePrimary = Color(0xFF35639F),
  )

internal data class AgentStatusStyle(
  val background: Color,
  val foreground: Color,
  val label: String,
)

internal fun agentStatusStyle(status: String, dark: Boolean): AgentStatusStyle {
  val (background, label) =
    when (status.lowercase()) {
      "working" -> (if (dark) Color(0xFF9CC4EE) else Color(0xFF35639F)) to "Working"
      "idle" -> (if (dark) Color(0xFFE3C48E) else Color(0xFF8F5D26)) to "Idle"
      "done" -> (if (dark) Color(0xFFA3D9B0) else Color(0xFF33774A)) to "Done"
      "blocked" -> (if (dark) Color(0xFFF09CA8) else Color(0xFFB3424E)) to "Blocked"
      else -> (if (dark) Color(0xFF35404F) else Color(0xFFDDE2EA)) to "Unknown"
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
