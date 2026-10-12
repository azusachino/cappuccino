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
import androidx.compose.runtime.SideEffect
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

// Stock Material 3 fallback schemes. The preview contract keeps component defaults
// authoritative and forbids hand-mixed scheme slots (android-material3.md rev 2).
val FallbackLightColorScheme = lightColorScheme()
val FallbackDarkColorScheme = darkColorScheme()

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
  // Optional activity-owned sink for the actually resolved theme (e.g. system-bar
  // appearance). Resolution stays pure here; the platform window mutation lives in the
  // caller (android-material3.md repair ledger, review finding 2).
  onResolvedDarkChanged: ((Boolean) -> Unit)? = null,
  content: @Composable () -> Unit,
) {
  val context = LocalContext.current
  val isDark = resolveIsDark(themeMode, isSystemInDarkTheme())
  // Reports whenever the resolved theme changes, whether from the preference or the
  // system mode; idempotent per composition, no extra observer lifecycle.
  SideEffect { onResolvedDarkChanged?.invoke(isDark) }
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
