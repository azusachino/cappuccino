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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode {
  SYSTEM,
  LIGHT,
  DARK,
}

val HerdrAmber = Color(0xFFF0A830)
val HerdrAmberHover = Color(0xFFF6BB55)
val HerdrBgDark = Color(0xFF12100E)
val HerdrPanelDark = Color(0xFF181613)
val HerdrElevatedDark = Color(0xFF211E1A)
val HerdrBorderDark = Color(0xFF2D2924)
val HerdrBorderStrongDark = Color(0xFF3E3830)
val HerdrTextDark = Color(0xFFD8D0C3)
val HerdrTextDimDark = Color(0xFF9B9183)
val HerdrTextStrongDark = Color(0xFFF2EBDF)

val HerdrStatusWorking = Color(0xFF6CB8D6)
val HerdrStatusIdle = Color(0xFF9B9183)
val HerdrStatusBlocked = Color(0xFFFF7B70)
val HerdrStatusDone = Color(0xFF93C36B)

val HerdrDarkColorScheme =
  darkColorScheme(
    primary = HerdrAmber,
    onPrimary = Color(0xFF1B1407),
    primaryContainer = HerdrElevatedDark,
    onPrimaryContainer = HerdrTextStrongDark,
    background = HerdrBgDark,
    onBackground = HerdrTextDark,
    surface = HerdrPanelDark,
    onSurface = HerdrTextDark,
    surfaceVariant = HerdrElevatedDark,
    onSurfaceVariant = HerdrTextDimDark,
    outline = HerdrBorderDark,
    outlineVariant = HerdrBorderStrongDark,
    error = HerdrStatusBlocked,
    onError = Color(0xFF1B1407),
  )

val HerdrLightColorScheme =
  lightColorScheme(
    primary = Color(0xFF8C5000),
    onPrimary = Color(0xFFFFFDF9),
    primaryContainer = Color(0xFFF2EEE6),
    onPrimaryContainer = Color(0xFF16120D),
    background = Color(0xFFEEEAE2),
    onBackground = Color(0xFF2A251F),
    surface = Color(0xFFFAF8F3),
    onSurface = Color(0xFF2A251F),
    surfaceVariant = Color(0xFFF2EEE6),
    onSurfaceVariant = Color(0xFF685E52),
    outline = Color(0xFFDCD4C6),
    outlineVariant = Color(0xFFC5BAA8),
    error = Color(0xFFA82323),
    onError = Color.White,
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
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val isDark = resolveIsDark(themeMode, isSystemInDarkTheme())
  val lightFallback = HerdrLightColorScheme
  val darkFallback = HerdrDarkColorScheme
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
