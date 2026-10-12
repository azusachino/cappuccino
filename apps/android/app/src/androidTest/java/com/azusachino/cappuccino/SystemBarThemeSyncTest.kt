package com.azusachino.cappuccino

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-path regression for the app-resolved theme to system-bar appearance sync
 * (android-material3.md repair ledger, review finding 2). The real MainActivity composition runs
 * untouched — setContent is never overridden here — and the theme is driven through the real
 * Settings journey. System night mode flips through the emulator's uiMode service; the task owner
 * restores the inherited host-side value after the run. No owner device, bridge or endpoint data.
 */
@RunWith(AndroidJUnit4::class)
class SystemBarThemeSyncTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun darkAppWhileSystemLightShowsLightStatusIcons() {
    setSystemNight(dark = false)
    selectTheme("Dark")
    assertBarsDrawLightIcons(expectLightIcons = true)
    capture("AppDarkSystemLightLightStatusIcons")
    selectTheme("System")
  }

  @Test
  fun lightAppWhileSystemDarkShowsDarkStatusIcons() {
    setSystemNight(dark = true)
    selectTheme("Light")
    assertBarsDrawLightIcons(expectLightIcons = false)
    selectTheme("System")
  }

  @Test
  fun systemPreferenceResyncsWhenSystemThemeChanges() {
    selectTheme("System")
    setSystemNight(dark = true)
    assertBarsDrawLightIcons(expectLightIcons = true)
    // No preference change in between: the sync must follow the system mode alone.
    setSystemNight(dark = false)
    assertBarsDrawLightIcons(expectLightIcons = false)
  }

  private fun selectTheme(label: String) {
    compose.onNode(hasText("Settings") and hasClickAction()).performClick()
    compose.waitForIdle()
    compose.onNodeWithText(label).performClick()
    compose.waitForIdle()
  }

  private fun assertBarsDrawLightIcons(expectLightIcons: Boolean) {
    compose.waitForIdle()
    val activity = compose.activity
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    assertEquals(
      expectLightIcons,
      !controller.isAppearanceLightStatusBars,
    )
    assertEquals(
      expectLightIcons,
      !controller.isAppearanceLightNavigationBars,
    )
  }

  private fun setSystemNight(dark: Boolean) {
    ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation()
          .uiAutomation
          .executeShellCommand("cmd uimode night ${if (dark) "yes" else "no"}")
      )
      .bufferedReader()
      .use { it.readText() }
    val expected = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    // A uiMode change recreates the activity; the predicate re-reads whichever instance
    // is current and tolerates the recreation gap.
    compose.waitUntil(timeoutMillis = 10_000) {
      runCatching {
          (compose.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            expected
        }
        .getOrDefault(false)
    }
    compose.waitForIdle()
  }

  private fun capture(name: String) {
    val bitmap =
      requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try {
      PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }
}
