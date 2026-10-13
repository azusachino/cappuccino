package com.azusachino.cappuccino.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPaletteTest {
  @Test
  fun catalogHasExactlyTheFiveOwnerChoices() {
    assertEquals(
      listOf("Default", "Dynamic", "Catppuccin", "GitHub", "One Dark"),
      AppPalette.entries.map { it.label },
    )
  }

  @Test
  fun missingOrUnknownPreferencePreservesExistingCatppuccinAppearance() {
    assertEquals(AppPalette.CATPPUCCIN, parseAppPalette(null))
    assertEquals(AppPalette.CATPPUCCIN, parseAppPalette("retired-palette"))
    AppPalette.entries.forEach { assertEquals(it, parseAppPalette(it.name)) }
  }

  @Test
  fun defaultSelectsMaterialBaselineSchemesInBothModes() {
    assertEquals(BaselineLightColorScheme, scheme(AppPalette.DEFAULT, false))
    assertEquals(BaselineDarkColorScheme, scheme(AppPalette.DEFAULT, true))
  }

  @Test
  fun catppuccinRetainsExistingSchemesInBothModes() {
    assertEquals(FallbackLightColorScheme, scheme(AppPalette.CATPPUCCIN, false))
    assertEquals(FallbackDarkColorScheme, scheme(AppPalette.CATPPUCCIN, true))
  }

  @Test
  fun dynamicSelectsNativeSchemeOnlyWhenSupported() {
    assertEquals(FallbackLightColorScheme, scheme(AppPalette.DYNAMIC, false))
    assertEquals(FallbackDarkColorScheme, scheme(AppPalette.DYNAMIC, true))
    assertEquals(BaselineLightColorScheme, scheme(AppPalette.DYNAMIC, false, 30))
    assertEquals(BaselineDarkColorScheme, scheme(AppPalette.DYNAMIC, true, 30))
  }

  @Test
  fun fixedPalettesHaveReadableNormalTextInBothModes() {
    listOf(AppPalette.DEFAULT, AppPalette.CATPPUCCIN, AppPalette.GITHUB, AppPalette.ONE_DARK)
      .forEach { palette ->
        listOf(false, true).forEach { dark ->
          val colors = scheme(palette, dark)
          listOf(
              colors.onSurface to colors.surface,
              colors.onSurfaceVariant to colors.surface,
              colors.onPrimary to colors.primary,
              colors.onPrimaryContainer to colors.primaryContainer,
            )
            .forEach { (foreground, background) ->
              assertTrue(
                "$palette dark=$dark: contrast ${contrast(foreground, background)}",
                contrast(foreground, background) >= 4.5f,
              )
            }
        }
      }
  }

  private fun scheme(palette: AppPalette, dark: Boolean, api: Int = 36) =
    appColorScheme(palette, dark, api, FallbackLightColorScheme, FallbackDarkColorScheme)

  private fun contrast(a: Color, b: Color): Float =
    (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)
}
