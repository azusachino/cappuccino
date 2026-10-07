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
import androidx.compose.ui.platform.LocalContext

fun selectColorScheme(
  apiLevel: Int,
  dark: Boolean,
  dynamicLight: ColorScheme,
  dynamicDark: ColorScheme,
  fallbackLight: ColorScheme,
  fallbackDark: ColorScheme,
): ColorScheme =
  when {
    apiLevel >= Build.VERSION_CODES.S && dark -> dynamicDark
    apiLevel >= Build.VERSION_CODES.S -> dynamicLight
    dark -> fallbackDark
    else -> fallbackLight
  }

@Composable
fun CappuccinoTheme(content: @Composable () -> Unit) {
  val context = LocalContext.current
  val dark = isSystemInDarkTheme()
  val lightFallback = lightColorScheme()
  val darkFallback = darkColorScheme()
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
        dark,
        dynamicLight,
        dynamicDark,
        lightFallback,
        darkFallback,
      ),
    content = content,
  )
}
