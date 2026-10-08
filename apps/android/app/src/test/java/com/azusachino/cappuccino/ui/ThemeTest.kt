package com.azusachino.cappuccino.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeTest {
  private val dynamicLight = lightColorScheme()
  private val dynamicDark = darkColorScheme()
  private val fallbackLight = lightColorScheme(primary = androidx.compose.ui.graphics.Color.Red)
  private val fallbackDark = darkColorScheme(primary = androidx.compose.ui.graphics.Color.Blue)

  @Test
  fun resolvesThemeModeCorrectly() {
    assertTrue(resolveIsDark(ThemeMode.DARK, systemDark = false))
    assertFalse(resolveIsDark(ThemeMode.LIGHT, systemDark = true))
    assertTrue(resolveIsDark(ThemeMode.SYSTEM, systemDark = true))
    assertFalse(resolveIsDark(ThemeMode.SYSTEM, systemDark = false))
  }

  @Test
  fun selectsFallbackColorSchemesByDefaultEvenOnApi31() {
    assertSame(
      fallbackLight,
      selectColorScheme(
        31,
        dark = false,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = false,
      ),
    )
    assertSame(
      fallbackDark,
      selectColorScheme(
        34,
        dark = true,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = false,
      ),
    )
  }

  @Test
  fun selectsWallpaperColorsWhenDynamicColorExplicitlyEnabled() {
    assertSame(
      dynamicLight,
      selectColorScheme(
        31,
        dark = false,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = true,
      ),
    )
    assertSame(
      dynamicDark,
      selectColorScheme(
        37,
        dark = true,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = true,
      ),
    )
  }

  @Test
  fun selectsLightAndDarkFallbackBeforeApi31() {
    assertSame(
      fallbackLight,
      selectColorScheme(
        26,
        dark = false,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = true,
      ),
    )
    assertSame(
      fallbackDark,
      selectColorScheme(
        30,
        dark = true,
        dynamicLight,
        dynamicDark,
        fallbackLight,
        fallbackDark,
        useDynamic = true,
      ),
    )
  }
}
