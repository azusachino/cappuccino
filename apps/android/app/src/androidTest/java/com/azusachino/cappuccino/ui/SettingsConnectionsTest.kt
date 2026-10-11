package com.azusachino.cappuccino.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsConnectionsTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun eachMachineKeepsItsOwnStatusAndDetailsTarget() {
    var target: String? = null
    compose.activity.setContent {
      CappuccinoTheme(useDynamicColor = false) {
        SettingsScreen(
          themeMode = ThemeMode.SYSTEM,
          onSelectTheme = {},
          onReloadMachines = {},
          connections =
            listOf(
              SettingsConnection("mac", "MacMini", "Connected"),
              SettingsConnection("linux", "Linux mini", "Not connected"),
            ),
          onConnectionDetails = { target = it },
        )
      }
    }
    compose.onNodeWithText("MacMini").assertIsDisplayed()
    compose.onNodeWithText("Linux mini").assertIsDisplayed()
    compose.onNodeWithText("Connected").assertIsDisplayed()
    compose.onNodeWithText("Not connected").assertIsDisplayed()
    compose.onNodeWithText("Linux mini").performClick()
    compose.runOnIdle { assertEquals("linux", target) }
    compose.onNodeWithText("MacMini").performClick()
    compose.runOnIdle { assertEquals("mac", target) }
  }
}
