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

// The app palette is cappuccino: milk-foam cream for light mode, deep
// espresso for dark mode, caramel as the primary and the artwork's cheek
// rose (#F2B7C4) as tertiary. Status hues stay semantic (blue working,
// amber idle, green done) so meaning never depends on the drink.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF8C5A32),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF2E1C8),
    onPrimaryContainer = Color(0xFF3B2B1A),
    secondary = Color(0xFF7A5D38),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFE3CF),
    onSecondaryContainer = Color(0xFF4A3A22),
    tertiary = Color(0xFFB05A70),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFBDEE5),
    onTertiaryContainer = Color(0xFF4E2230),
    background = Color(0xFFF5EEE3),
    onBackground = Color(0xFF3B2B20),
    surface = Color(0xFFF5EEE3),
    onSurface = Color(0xFF3B2B20),
    surfaceVariant = Color(0xFFEAE0D0),
    onSurfaceVariant = Color(0xFF645643),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F1E7),
    surfaceContainer = Color(0xFFF1E9DC),
    surfaceContainerHigh = Color(0xFFEAE1D2),
    surfaceContainerHighest = Color(0xFFE2D7C4),
    surfaceDim = Color(0xFFE2D7C4),
    surfaceBright = Color(0xFFF5EEE3),
    outline = Color(0xFF84755F),
    outlineVariant = Color(0xFFDCCEB9),
    error = Color(0xFFB3424E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBDEE5),
    onErrorContainer = Color(0xFF4E2230),
    surfaceTint = Color(0xFF8C5A32),
    inverseSurface = Color(0xFF3B2B20),
    inverseOnSurface = Color(0xFFF5EEE3),
    inversePrimary = Color(0xFFD9A868),
  )

val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFD9A868),
    onPrimary = Color(0xFF241A10),
    primaryContainer = Color(0xFF4A3520),
    onPrimaryContainer = Color(0xFFF2DFC4),
    secondary = Color(0xFFB99B72),
    onSecondary = Color(0xFF1F1811),
    secondaryContainer = Color(0xFF3A2F22),
    onSecondaryContainer = Color(0xFFEBDEC9),
    tertiary = Color(0xFFE8A9B8),
    onTertiary = Color(0xFF2E141B),
    tertiaryContainer = Color(0xFF4A2E38),
    onTertiaryContainer = Color(0xFFFBDEE5),
    background = Color(0xFF1C1712),
    onBackground = Color(0xFFEFE6DA),
    surface = Color(0xFF1C1712),
    onSurface = Color(0xFFEFE6DA),
    surfaceVariant = Color(0xFF2C251D),
    onSurfaceVariant = Color(0xFFCFC3B2),
    surfaceContainerLowest = Color(0xFF171310),
    surfaceContainerLow = Color(0xFF221B15),
    surfaceContainer = Color(0xFF2C251D),
    surfaceContainerHigh = Color(0xFF322A21),
    surfaceContainerHighest = Color(0xFF3A3126),
    surfaceDim = Color(0xFF171310),
    surfaceBright = Color(0xFF3A3126),
    outline = Color(0xFF9A8C79),
    outlineVariant = Color(0xFF453A2D),
    error = Color(0xFFF09CA8),
    onError = Color(0xFF2E1015),
    errorContainer = Color(0xFF4E2530),
    onErrorContainer = Color(0xFFFBDEE5),
    surfaceTint = Color(0xFFD9A868),
    inverseSurface = Color(0xFFEFE6DA),
    inverseOnSurface = Color(0xFF1C1712),
    inversePrimary = Color(0xFF8C5A32),
  )

internal data class AgentStatusStyle(
  val background: Color,
  val foreground: Color,
  val label: String,
)

internal fun agentStatusStyle(status: String, dark: Boolean): AgentStatusStyle {
  val (background, label) =
    when (status.lowercase()) {
      "working" -> (if (dark) Color(0xFF8FB2E0) else Color(0xFF3A66AE)) to "Working"
      "idle" -> (if (dark) Color(0xFFD9A868) else Color(0xFF8F5D26)) to "Idle"
      "done" -> (if (dark) Color(0xFFA3D9B0) else Color(0xFF33774A)) to "Done"
      "blocked" -> (if (dark) Color(0xFFF09CA8) else Color(0xFFB3424E)) to "Blocked"
      else -> (if (dark) Color(0xFF3A3126) else Color(0xFFE2D7C4)) to "Unknown"
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
