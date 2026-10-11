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

// Semantic agent-status colors remain separate from application accent roles.
val HerdrStatusWorking = Color(0xFF6CB8D6)
val HerdrStatusIdle = Color(0xFF9B9183)
val HerdrStatusBlocked = Color(0xFFFF7B70)
val HerdrStatusDone = Color(0xFF93C36B)

// Owner-selected Cappuccino identity: cream, espresso and caramel. Code highlighting
// retains its independent GitHub palette; wallpaper colors are not the app default.
val FallbackLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF785039),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDCC2),
    onPrimaryContainer = Color(0xFF2B170C),
    secondary = Color(0xFF705B4D),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF9DECA),
    onSecondaryContainer = Color(0xFF291C13),
    tertiary = Color(0xFF59613C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE6B8),
    onTertiaryContainer = Color(0xFF181F05),
    background = Color(0xFFFFF8F2),
    onBackground = Color(0xFF2E211B),
    surface = Color(0xFFFFF8F2),
    onSurface = Color(0xFF2E211B),
    surfaceVariant = Color(0xFFE5D2C2),
    onSurfaceVariant = Color(0xFF66554A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF0E5),
    surfaceContainer = Color(0xFFF5E7DC),
    surfaceContainerHigh = Color(0xFFEDDDD0),
    surfaceContainerHighest = Color(0xFFE5D2C2),
    surfaceDim = Color(0xFFE5D2C2),
    surfaceBright = Color(0xFFFFF8F2),
    outline = Color(0xFF857469),
    outlineVariant = Color(0xFFD6C3B5),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceTint = Color(0xFF785039),
    inverseSurface = Color(0xFF372A22),
    inverseOnSurface = Color(0xFFFFF0E3),
    inversePrimary = Color(0xFFE8B38E),
  )
val FallbackDarkColorScheme =
  darkColorScheme(
    primary = Color(0xFFE8B38E),
    onPrimary = Color(0xFF442A18),
    primaryContainer = Color(0xFF5F3E27),
    onPrimaryContainer = Color(0xFFFFDBC0),
    secondary = Color(0xFFDAC1AE),
    onSecondary = Color(0xFF3D2D22),
    secondaryContainer = Color(0xFF554235),
    onSecondaryContainer = Color(0xFFF9DECA),
    tertiary = Color(0xFFC1CA9D),
    onTertiary = Color(0xFF2B3316),
    tertiaryContainer = Color(0xFF414A2A),
    onTertiaryContainer = Color(0xFFDDE6B8),
    background = Color(0xFF1B1410),
    onBackground = Color(0xFFF2E4D8),
    surface = Color(0xFF1B1410),
    onSurface = Color(0xFFF2E4D8),
    surfaceVariant = Color(0xFF44352C),
    onSurfaceVariant = Color(0xFFD3BFAF),
    surfaceContainerLowest = Color(0xFF140E0B),
    surfaceContainerLow = Color(0xFF241B16),
    surfaceContainer = Color(0xFF2D221C),
    surfaceContainerHigh = Color(0xFF382B23),
    surfaceContainerHighest = Color(0xFF44352C),
    surfaceDim = Color(0xFF1B1410),
    surfaceBright = Color(0xFF44352C),
    outline = Color(0xFFA08B7C),
    outlineVariant = Color(0xFF534338),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceTint = Color(0xFFE8B38E),
    inverseSurface = Color(0xFFF2E4D8),
    inverseOnSurface = Color(0xFF372A22),
    inversePrimary = Color(0xFF785039),
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
  useDynamic: Boolean = false,
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
  useDynamicColor: Boolean = false,
  onResolvedDarkChanged: ((Boolean) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val isDark = resolveIsDark(themeMode, isSystemInDarkTheme())
  // Stream/discovery recompositions must not rewrite window appearance flags.
  val themeSink by rememberUpdatedState(onResolvedDarkChanged)
  LaunchedEffect(isDark) { themeSink?.invoke(isDark) }
  val dynamicLight =
    if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      dynamicLightColorScheme(context)
    else FallbackLightColorScheme
  val dynamicDark =
    if (useDynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
      dynamicDarkColorScheme(context)
    else FallbackDarkColorScheme
  MaterialTheme(
    colorScheme =
      selectColorScheme(
        Build.VERSION.SDK_INT,
        isDark,
        dynamicLight,
        dynamicDark,
        FallbackLightColorScheme,
        FallbackDarkColorScheme,
        useDynamic = useDynamicColor,
      ),
    content = content,
  )
}
