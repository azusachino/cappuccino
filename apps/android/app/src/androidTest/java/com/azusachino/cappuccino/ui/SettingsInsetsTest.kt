package com.azusachino.cappuccino.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsInsetsTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun firstItemClearsHeaderAndLastItemClearsDock() {
    val headerHeight = 100.dp
    val dockHeight = 120.dp
    var headerPx = 0f
    var dockPx = 0f
    compose.activity.setContent {
      val density = LocalDensity.current
      headerPx = with(density) { headerHeight.toPx() }
      dockPx = with(density) { dockHeight.toPx() }
      CappuccinoTheme {
        Box(Modifier.fillMaxSize()) {
          SettingsScreen(
            themeMode = ThemeMode.SYSTEM,
            onSelectTheme = {},
            onReloadMachines = {},
            connections = listOf(SettingsConnection("mac", "MacMini", "Connected")),
            onConnectionDetails = {},
            contentPadding = PaddingValues(top = headerHeight, bottom = dockHeight),
          )
        }
      }
    }
    compose.waitForIdle()
    val first = compose.onNodeWithText("APPEARANCE").fetchSemanticsNode().boundsInRoot
    assertTrue("first item top ${first.top} < header bottom $headerPx", first.top >= headerPx)

    // Fling to the end so the list is scrolled as far as it goes, not just until the node shows.
    repeat(4) { compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.9f, endY = top) } }
    compose.waitForIdle()
    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
    val last = compose.onNodeWithText("GPL-3.0").fetchSemanticsNode().boundsInRoot
    val dockTop = root.bottom - dockPx
    assertTrue("last item bottom ${last.bottom} > dock top $dockTop", last.bottom <= dockTop)
  }
}
