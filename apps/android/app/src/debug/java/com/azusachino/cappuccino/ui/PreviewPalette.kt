package com.azusachino.cappuccino.ui

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.azusachino.cappuccino.ui.palette.MihonDefaultPalette

/** The owner-scoped three-choice catalog, debug design only. */
@Composable
internal fun previewPalette(name: String, dark: Boolean): ColorScheme =
  when {
    name == "Catppuccin" -> if (dark) FallbackDarkColorScheme else FallbackLightColorScheme
    name == "Dynamic" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
      val context = LocalContext.current
      if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    else -> if (dark) MihonDefaultPalette.darkScheme else MihonDefaultPalette.lightScheme
  }
