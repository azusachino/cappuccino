package com.azusachino.cappuccino.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeTest {
  private val dynamicLight = lightColorScheme()
  private val dynamicDark = darkColorScheme()
  private val fallbackLight = lightColorScheme(primary = androidx.compose.ui.graphics.Color.Red)
  private val fallbackDark = darkColorScheme(primary = androidx.compose.ui.graphics.Color.Blue)

  @Test
  fun selectsWallpaperColorsOnApi31AndSystemAppearance() {
    assertSame(
      dynamicLight,
      selectColorScheme(31, false, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
    )
    assertSame(
      dynamicDark,
      selectColorScheme(37, true, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
    )
  }

  @Test
  fun selectsLightAndDarkFallbackBeforeApi31() {
    assertSame(
      fallbackLight,
      selectColorScheme(26, false, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
    )
    assertSame(
      fallbackDark,
      selectColorScheme(30, true, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
    )
  }
}
