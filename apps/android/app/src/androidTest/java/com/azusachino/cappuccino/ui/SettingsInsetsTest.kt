package com.azusachino.cappuccino.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.MainActivity
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import kotlinx.coroutines.runBlocking
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
    val scrollState = ScrollState(0)
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
            scrollState = scrollState,
          )
        }
      }
    }
    compose.waitForIdle()
    val first = compose.onNodeWithText("APPEARANCE").fetchSemanticsNode().boundsInRoot
    assertTrue("first item top ${first.top} < header bottom $headerPx", first.top >= headerPx)

    runBlocking { scrollState.scrollTo(scrollState.maxValue) }
    compose.waitForIdle()
    val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
    val last = compose.onNodeWithText("Connection logs").fetchSemanticsNode().boundsInRoot
    val dockTop = root.bottom - dockPx
    assertTrue("last item bottom ${last.bottom} > dock top $dockTop", last.bottom <= dockTop)
  }

  @Test
  fun slimBarTitleAppearsAfterScrollingPastLargeTitle() {
    val state = ConnectedUiState()
    val actions =
      object : ConnectedActions {
        override fun addMachine(label: String, url: String) = Unit

        override fun renameProfile(id: String, label: String) = Unit

        override fun selectProfile(id: String) = Unit

        override fun disconnectProfile() = Unit

        override fun refresh() = Unit

        override fun selectAgent(agent: AgentRow?) = Unit

        override fun removeProfile(id: String) = Unit

        override fun retry() = Unit

        override fun setThemeMode(mode: ThemeMode) = Unit

        override fun setDraft(key: String, text: String) = Unit

        override fun submitPrompt(text: String, confirmed: Boolean) = Unit

        override fun answerPrompt(
          promptId: String,
          optionIndex: Int?,
          optionId: String?,
          action: String?,
        ) = Unit

        override fun loadConversation() = Unit

        override fun controlAgent(action: String) = Unit
      }
    compose.activity.setContent {
      CappuccinoTheme {
        CappuccinoScreen(
          state = state,
          actions = actions,
          requestConnect = { _, _ -> },
        )
      }
    }
    compose.waitForIdle()

    // Switch to Settings tab
    compose.onNode(hasContentDescription("Settings") and hasClickAction()).performClick()
    compose.waitForIdle()

    // Initially at top: slim bar title is not shown
    compose.onNodeWithTag("slimBarTitle").assertDoesNotExist()

    // Swipe up to scroll past the large title
    compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.7f, endY = top * 0.2f) }
    compose.waitForIdle()

    // Scrolled down: slim bar title appears
    compose.onNodeWithTag("slimBarTitle").assertIsDisplayed()

    // Swipe down to scroll back to top
    compose.onRoot().performTouchInput { swipeDown(startY = top * 0.2f, endY = bottom * 0.7f) }
    compose.waitForIdle()

    // Back at top: slim bar title disappears
    compose.onNodeWithTag("slimBarTitle").assertDoesNotExist()
  }
}
