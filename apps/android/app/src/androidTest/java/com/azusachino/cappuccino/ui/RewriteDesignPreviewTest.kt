package com.azusachino.cappuccino.ui

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native, synthetic design captures only; not attachment or live-scroll acceptance. */
@RunWith(AndroidJUnit4::class)
class RewriteDesignPreviewTest {
  @get:Rule val compose = createComposeRule()

  @Test
  fun captureMihonShellLight() {
    compose.setContent { RewriteDesignPrototype() }
    compose.onNodeWithText("Bridge maintenance").assertIsDisplayed()
    capture("RewriteShellLight")
  }

  @Test
  fun captureTerminalSessionDarkAndTools() {
    compose.setContent { RewriteDesignPrototype(initialPage = "Session", initialDark = true) }
    compose.onNodeWithText("Following").assertIsDisplayed()
    capture("RewriteSessionDark")
    compose.onNodeWithText("Tools").performClick()
    compose.onNodeWithText("Loaded operations").assertIsDisplayed()
    capture("RewriteToolsDark")
  }

  @Test
  fun captureAppearanceAndPaletteChange() {
    compose.setContent { RewriteDesignPrototype(initialPage = "Appearance") }
    compose.onNodeWithText("Selected palette: Catppuccin", substring = true).assertIsDisplayed()
    capture("RewriteAppearanceLight")
    compose.onNodeWithText("Default").performClick()
    compose.onNodeWithText("Selected palette: Default", substring = true).assertIsDisplayed()
    capture("RewriteAppearanceMaterial")
  }

  @Test
  fun captureTerminalSessionLargeFont() {
    compose.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.3f)) {
        RewriteDesignPrototype(initialPage = "Session")
      }
    }
    compose.onNodeWithText("Following").assertIsDisplayed()
    capture("RewriteSessionLargeFont")
  }

  private fun capture(name: String) {
    compose.waitForIdle()
    val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
    PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
  }
}
