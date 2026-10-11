package com.azusachino.cappuccino.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
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
  fun catppuccinCanvasesAndNormalTextHaveReadableContrast() {
    assertEquals(Color(0xFFEFF1F5), FallbackLightColorScheme.background)
    assertEquals(Color(0xFF1E1E2E), FallbackDarkColorScheme.background)
    for (scheme in listOf(FallbackLightColorScheme, FallbackDarkColorScheme)) {
      for ((foreground, background) in
        listOf(
          scheme.onBackground to scheme.background,
          scheme.onSurface to scheme.surfaceContainerHigh,
          scheme.onSurfaceVariant to scheme.surfaceContainerHigh,
          scheme.onPrimary to scheme.primary,
          scheme.onPrimaryContainer to scheme.primaryContainer,
          scheme.onSecondary to scheme.secondary,
          scheme.onSecondaryContainer to scheme.secondaryContainer,
          scheme.onTertiary to scheme.tertiary,
          scheme.onTertiaryContainer to scheme.tertiaryContainer,
          scheme.onError to scheme.error,
          scheme.onErrorContainer to scheme.errorContainer,
          scheme.onSurfaceVariant to scheme.surfaceContainerHighest,
          scheme.onSurface to scheme.surface,
          scheme.onSurface to scheme.surfaceContainerLow,
          scheme.onSurface to scheme.surfaceContainer,
          scheme.inverseOnSurface to scheme.inverseSurface,
        )) {
        val a = foreground.luminance()
        val b = background.luminance()
        assertTrue(
          "Text contrast must be at least 4.5:1",
          (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f,
        )
      }
    }
  }

  @Test
  fun statusBadgesUseDistinctCatppuccinColorsAndReadableLabels() {
    for (dark in listOf(false, true)) {
      val badges = listOf("working", "idle", "done", "blocked").map { agentStatusStyle(it, dark) }
      assertEquals(4, badges.map { it.background }.distinct().size)
      assertEquals(listOf("Working", "Idle", "Done", "Blocked"), badges.map { it.label })
      assertEquals(Color(if (dark) 0xFF89B4FA else 0xFF1E66F5), badges.first().background)
      for (badge in badges + agentStatusStyle("unreported", dark)) {
        val a = badge.foreground.luminance()
        val b = badge.background.luminance()
        assertTrue(
          "Status label contrast must be at least 4.5:1",
          (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f) >= 4.5f,
        )
      }
      assertEquals("Unknown", agentStatusStyle("unreported", dark).label)
      assertEquals(badges.first(), agentStatusStyle("WORKING", dark))
    }
  }

  @Test
  fun agentIconsAreDistinctAndUnknownAgentsHaveANeutralFallback() {
    assertTrue(agentIconResource("pi") != agentIconResource("agy"))
    assertTrue(agentIconResource("claude") != agentIconResource("codex"))
    assertEquals(agentIconResource("agy"), agentIconResource("antigravity-cli"))
    assertEquals(com.azusachino.cappuccino.R.drawable.ic_machine, agentIconResource("unknown"))
  }

  @Test
  fun resolvesThemeModeCorrectly() {
    assertTrue(resolveIsDark(ThemeMode.DARK, systemDark = false))
    assertFalse(resolveIsDark(ThemeMode.LIGHT, systemDark = true))
    assertTrue(resolveIsDark(ThemeMode.SYSTEM, systemDark = true))
    assertFalse(resolveIsDark(ThemeMode.SYSTEM, systemDark = false))
  }

  @Test
  fun explicitFixedPaletteRemainsAvailableEvenOnApi31() {
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
  fun catppuccinColorsAreTheDefaultOnSupportedDevices() {
    for (dark in listOf(false, true)) {
      assertSame(
        if (dark) fallbackDark else fallbackLight,
        selectColorScheme(31, dark, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
      )
      assertSame(
        if (dark) fallbackDark else fallbackLight,
        selectColorScheme(30, dark, dynamicLight, dynamicDark, fallbackLight, fallbackDark),
      )
    }
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
