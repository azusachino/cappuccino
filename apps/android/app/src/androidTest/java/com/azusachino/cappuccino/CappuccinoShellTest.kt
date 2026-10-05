package com.azusachino.cappuccino

import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CappuccinoShellTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun disconnectedJourneyAndRecreation() {
    compose.onNodeWithText("No agents attached").assertIsDisplayed()
    compose.onNodeWithText("Send").assertIsNotEnabled()
    compose.onNodeWithText("Message").assertIsNotEnabled()
    compose.onNodeWithText("Follow-up").assertIsSelected()
    compose.onNodeWithText("Nudge").performClick().assertIsSelected()
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithText("Nudge").assertIsSelected()
    compose.onNodeWithText("Send").assertIsNotEnabled()
    capture("Chats")

    compose.onNode(hasText("Attention") and hasClickAction()).performClick()
    compose.onNodeWithText("No pending requests").assertIsDisplayed()
    capture("Attention")
    compose.onNode(hasText("Machines") and hasClickAction()).performClick()
    compose.onNodeWithText("No machines added").assertIsDisplayed()
    capture("Machines")

    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithText("No machines added").assertIsDisplayed()
    compose.onNode(hasText("Chats") and hasClickAction()).performClick()
    compose.onNodeWithText("Nudge").assertIsSelected()
    compose.onNodeWithText("Send").assertIsNotEnabled()
  }

  private fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      // Gradle collects test storage before uninstalling the debug app.
      PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }
}
