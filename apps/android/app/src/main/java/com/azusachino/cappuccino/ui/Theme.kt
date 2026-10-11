package com.azusachino.cappuccino.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

// Semantic agent-status colors: they label bridge-reported state on tonal pills and small
// status dots and are deliberately not Material color-scheme slots (android-material3.md).
val HerdrStatusWorking = Color(0xFF6CB8D6)
val HerdrStatusIdle = Color(0xFF9B9183)
val HerdrStatusBlocked = Color(0xFFFF7B70)
val HerdrStatusDone = Color(0xFF93C36B)

// Fixed fallback for older Android versions and deterministic previews.
// Supported devices use Android's wallpaper-derived Material colors by default.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF0969DA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDF4FF),
    onPrimaryContainer = Color(0xFF0550AE),
    secondary = Color(0xFF57606A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEFF4),
    onSecondaryContainer = Color(0xFF24292F),
    tertiary = Color(0xFF1A7F37),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDAFBE1),
    onTertiaryContainer = Color(0xFF116329),
    background = Color.White,
    onBackground = Color(0xFF24292F),
    surface = Color.White,
    onSurface = Color(0xFF24292F),
    surfaceVariant = Color(0xFFF6F8FA),
    onSurfaceVariant = Color(0xFF57606A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF6F8FA),
    surfaceContainer = Color(0xFFF6F8FA),
    surfaceContainerHigh = Color(0xFFEAEFF4),
    surfaceContainerHighest = Color(0xFFE1E7ED),
    surfaceDim = Color(0xFFEAEFF4),
    surfaceBright = Color.White,
    outline = Color(0xFF6E7781),
    outlineVariant = Color(0xFFD0D7DE),
    error = Color(0xFFCF222E),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBE9),
    onErrorContainer = Color(0xFFA40E26),
    surfaceTint = Color(0xFF0969DA),
    inverseSurface = Color(0xFF24292F),
    inverseOnSurface = Color(0xFFF6F8FA),
    inversePrimary = Color(0xFF58A6FF),
  )
val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFF58A6FF),
    onPrimary = Color(0xFF0D1117),
    primaryContainer = Color(0xFF163356),
    onPrimaryContainer = Color(0xFFCAE8FF),
    secondary = Color(0xFF8B949E),
    onSecondary = Color(0xFF0D1117),
    secondaryContainer = Color(0xFF21262D),
    onSecondaryContainer = Color(0xFFE6EDF3),
    tertiary = Color(0xFF3FB950),
    onTertiary = Color(0xFF0D1117),
    tertiaryContainer = Color(0xFF143D24),
    onTertiaryContainer = Color(0xFFAFF5B4),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFE6EDF3),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFE6EDF3),
    surfaceVariant = Color(0xFF161B22),
    onSurfaceVariant = Color(0xFF8B949E),
    surfaceContainerLowest = Color(0xFF010409),
    surfaceContainerLow = Color(0xFF0D1117),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF21262D),
    surfaceContainerHighest = Color(0xFF30363D),
    surfaceDim = Color(0xFF010409),
    surfaceBright = Color(0xFF30363D),
    outline = Color(0xFF8B949E),
    outlineVariant = Color(0xFF30363D),
    error = Color(0xFFFF7B72),
    onError = Color(0xFF0D1117),
    errorContainer = Color(0xFF490202),
    onErrorContainer = Color(0xFFFFDCD7),
    surfaceTint = Color(0xFF58A6FF),
    inverseSurface = Color(0xFFE6EDF3),
    inverseOnSurface = Color(0xFF24292F),
    inversePrimary = Color(0xFF0969DA),
  )

fun resolveIsDark(themeMode: ThemeMode, systemDark: Boolean): Boolean =
  when (themeMode) {
    ThemeMode.SYSTEM -> systemDark
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
  }

fun selectColorScheme(
  apiLevel: Int,
  dark: Boolean,
  dynamicLight: ColorScheme,
  dynamicDark: ColorScheme,
  fallbackLight: ColorScheme,
  fallbackDark: ColorScheme,
  useDynamic: Boolean = true,
): ColorScheme =
  when {
    useDynamic && apiLevel >= Build.VERSION_CODES.S && dark -> dynamicDark
    useDynamic && apiLevel >= Build.VERSION_CODES.S -> dynamicLight
    dark -> fallbackDark
    else -> fallbackLight
  }

@Composable
fun CappuccinoTheme(
  themeMode: ThemeMode = ThemeMode.SYSTEM,
  useDynamicColor: Boolean = true,
  // Optional activity-owned sink for the actually resolved theme (e.g. system-bar
  // appearance). Resolution stays pure here; the platform window mutation lives in the
  // caller (android-material3.md repair ledger, review finding 2).
  onResolvedDarkChanged: ((Boolean) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val isDark = resolveIsDark(themeMode, isSystemInDarkTheme())
  // Stream/discovery recompositions must not rewrite window appearance flags.
  val themeSink by rememberUpdatedState(onResolvedDarkChanged)
  LaunchedEffect(isDark) { themeSink?.invoke(isDark) }
  val lightFallback = FallbackLightColorScheme
  val darkFallback = FallbackDarkColorScheme
  val dynamicLight =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicLightColorScheme(context)
    else lightFallback
  val dynamicDark =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(context)
    else darkFallback
  MaterialTheme(
    colorScheme =
      selectColorScheme(
        Build.VERSION.SDK_INT,
        isDark,
        dynamicLight,
        dynamicDark,
        lightFallback,
        darkFallback,
        useDynamic = useDynamicColor,
      ),
    content = content,
  )
}
