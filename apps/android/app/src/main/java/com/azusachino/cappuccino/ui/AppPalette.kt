package com.azusachino.cappuccino.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import com.azusachino.cappuccino.ui.palette.EditorPalettes
import com.azusachino.cappuccino.ui.palette.MihonDefaultPalette

/** Stable preference IDs, independent of brightness mode and list order. */
enum class AppPalette(val label: String) {
  DEFAULT("Default"),
  DYNAMIC("Dynamic"),
  CATPPUCCIN("Catppuccin"),
  GITHUB("GitHub"),
  ONE_DARK("One Dark"),
}

internal fun parseAppPalette(value: String?): AppPalette =
  AppPalette.entries.firstOrNull { it.name == value } ?: AppPalette.CATPPUCCIN

internal fun appColorScheme(
  palette: AppPalette,
  dark: Boolean,
  apiLevel: Int,
  dynamicLight: ColorScheme,
  dynamicDark: ColorScheme,
): ColorScheme =
  when {
    palette == AppPalette.GITHUB ->
      if (dark) EditorPalettes.githubDark else EditorPalettes.githubLight
    palette == AppPalette.ONE_DARK -> if (dark) EditorPalettes.oneDark else EditorPalettes.oneLight
    palette == AppPalette.CATPPUCCIN ->
      if (dark) FallbackDarkColorScheme else FallbackLightColorScheme
    palette == AppPalette.DYNAMIC && apiLevel >= Build.VERSION_CODES.S ->
      if (dark) dynamicDark else dynamicLight
    else -> if (dark) MihonDefaultPalette.darkScheme else MihonDefaultPalette.lightScheme
  }
