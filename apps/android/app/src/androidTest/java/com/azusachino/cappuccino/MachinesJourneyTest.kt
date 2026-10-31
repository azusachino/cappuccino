package com.azusachino.cappuccino

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineConnection
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.TransportBadge
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MachinesJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()
  private val STATUS_TEXT = "Connection error · iroh relayed"

  @Test
  fun failedAddStaysOpenAndSuccessfulVerificationDismissesDialog() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "synthetic-profile",
        "Synthetic bridge",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val state = mutableStateOf(ConnectedUiState())
    var attempts = 0
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme {
        CappuccinoScreen(state.value, actions) { _, _ ->
          if (attempts++ == 0) {
            state.value =
              state.value.copy(
                connection = ConnectionState.Error("Synthetic connection failed"),
                addError = "Synthetic connection failed",
              )
          } else {
            state.value =
              state.value.copy(
                profiles = listOf(profile),
                addedProfileIds = setOf(profile.id),
                activeProfileId = profile.id,
                connection = ConnectionState.Connected,
              )
          }
        }
      }
    }
    openMachines()
    compose.onNodeWithContentDescription("Add machine").performClick()
    compose.onNodeWithText("Enter bridge address instead").performClick()
    compose.onNodeWithText("Label (optional)").performTextInput("Synthetic bridge")
    compose.onNodeWithText("Bridge address").performTextInput("https://bridge.example")
    compose.onNodeWithText("Connect").performClick()
    assertTrue(
      compose.onAllNodesWithText("Synthetic connection failed").fetchSemanticsNodes().isNotEmpty()
    )
    compose.onNodeWithText("Bridge address").assertIsDisplayed()
    compose.onNodeWithText("Connect").performClick()
    compose.waitForIdle()
    assertEquals(profile, state.value.profiles.single())
    assertTrue(compose.onAllNodesWithText("Connect").fetchSemanticsNodes().isEmpty())
  }

  @Test
  fun retryIsExplicitAndRemoveClearsLocalProfile() {
    val profile =
      MachineProfile(
        "synthetic-profile",
        "Synthetic bridge",
        Endpoint.parse("https://bridge.example"),
        UUID.randomUUID(),
      )
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          connection = ConnectionState.Error("Synthetic offline"),
        )
      )
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme {
        val s by state
        CappuccinoScreen(s, actions) { _, _ -> }
      }
    }
    openMachines()
    compose.waitForIdle()
    // The errored row sits under the floating header, not beneath it.
    val header =
      compose.onNode(hasText("Machines") and !hasClickAction()).fetchSemanticsNode().boundsInRoot
    val row =
      compose
        .onNodeWithText("Synthetic bridge")
        .assertIsDisplayed()
        .fetchSemanticsNode()
        .boundsInRoot
    assertTrue(
      "Machine row sits under the header: row=$row header=$header",
      row.top > header.bottom,
    )
    compose.onNodeWithContentDescription("Retry Synthetic bridge").performClick()
    // The injected click is delivered after waitForIdle can return; wait for its effect, then
    // require exactly one call so a double-fire still fails.
    compose.waitUntil(timeoutMillis = 5_000) { actions.refreshCalls > 0 }
    compose.waitForIdle()
    assertEquals(1, actions.refreshCalls)
    compose.onNodeWithContentDescription("Machine actions").performClick()
    compose.onNodeWithText("Remove").performClick()
    compose.onNodeWithText("No machines").assertIsDisplayed()
    assertTrue(state.value.profiles.isEmpty())
  }

  @Test
  fun retryShowsOnlyOnErroredRowAndStatusStaysOneLine() {
    setFailingAndHealthy()
    openMachines()
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Retry Failing bridge").assertIsDisplayed()
    compose.onNodeWithContentDescription("Retry Healthy bridge").assertDoesNotExist()
    compose.onNodeWithContentDescription("Disconnect Failing bridge").assertDoesNotExist()
    // The status wraps to at most two lines (21330f8) and is never ellipsized. Line count depends
    // on device font metrics, so only the wrap bound and the full text are asserted.
    val layout = statusLayout()
    assertTrue("lines=${layout.lineCount}", layout.lineCount in 1..2)
    assertEquals(STATUS_TEXT.length, layout.getLineEnd(layout.lineCount - 1))
  }

  @Test
  fun narrowMachineStatusWrapsWithoutTruncating() {
    setFailingAndHealthy(width = 280.dp)
    openMachines()
    compose.waitForIdle()
    // At ~280dp the status wraps under the name; the full text is laid out, not ellipsized.
    val layout = statusLayout()
    assertTrue("lines=${layout.lineCount}", layout.lineCount in 1..3)
    assertFalse(layout.hasVisualOverflow)
    assertEquals(STATUS_TEXT.length, layout.getLineEnd(layout.lineCount - 1))
  }

  private fun statusLayout(): TextLayoutResult {
    // Match the status Text itself: the merged tree resolves to the clickable row, whose layout is
    // not the status.
    val lines = mutableListOf<TextLayoutResult>()
    compose
      .onNodeWithText(STATUS_TEXT, useUnmergedTree = true)
      .assertIsDisplayed()
      .fetchSemanticsNode()
      .config[SemanticsActions.GetTextLayoutResult]
      .action
      ?.invoke(lines)
    return lines.single()
  }

  private fun setFailingAndHealthy(width: Dp? = null) {
    val failing =
      MachineProfile(
        "failing",
        "Failing bridge",
        Endpoint.parse("https://a.example"),
        UUID.randomUUID(),
      )
    val healthy =
      MachineProfile(
        "healthy",
        "Healthy bridge",
        Endpoint.parse("https://b.example"),
        UUID.randomUUID(),
      )
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(failing, healthy),
          activeProfileId = failing.id,
          connection = ConnectionState.Error("Synthetic offline"),
          machines = mapOf(healthy.id to MachineConnection(ConnectionState.Connected)),
          machineBadges = mapOf(failing.id to TransportBadge.IROH_RELAYED),
        )
      )
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme {
        val s by state
        if (width == null) {
          CappuccinoScreen(s, actions) { _, _ -> }
        } else {
          Box(Modifier.requiredWidth(width)) { CappuccinoScreen(s, actions) { _, _ -> } }
        }
      }
    }
  }

  @Test
  fun dockLabelsAreAlwaysDisplayedAndSelectionKeepsTabWidths() {
    val state = mutableStateOf(ConnectedUiState())
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
    }
    compose.waitForIdle()
    val labels = listOf("Machines", "History", "Settings")
    val widthsBefore = labels.associateWith { dockTabWidth(it) }
    labels.forEach { compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
    labels.forEach { label ->
      compose.onNode(hasContentDescription(label) and hasClickAction()).performClick()
      compose.waitForIdle()
      compose.onNode(hasContentDescription(label) and hasClickAction()).assertIsSelected()
      labels.forEach { compose.onNode(hasText(it) and hasClickAction()).assertIsDisplayed() }
      labels.forEach { assertEquals(widthsBefore.getValue(it), dockTabWidth(it)) }
    }
  }

  private fun dockTabWidth(label: String): Float =
    compose
      .onNode(hasContentDescription(label) and hasClickAction())
      .fetchSemanticsNode()
      .size
      .width
      .toFloat()

  @Test
  fun machineTreeOpensAgentDirectlyAndPreservesExpansionOnBack() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("tree", "Synthetic machine", Endpoint.parse("https://bridge.example"), machine)
    val agent =
      AgentRow(
        machine,
        "private-pane-id",
        "private-pane-id",
        "Synthetic Pi",
        "pi",
        "idle",
        false,
        null,
      )
    val foreign = agent.copy(machineId = UUID.randomUUID(), label = "Foreign agent")
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          agents = listOf(agent, foreign),
          connection = ConnectionState.Connected,
        )
      )
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
    }
    compose.onNodeWithText("Synthetic machine").assertIsDisplayed()
    compose.onNodeWithText("Synthetic Pi").assertIsDisplayed().performClick()
    compose.waitForIdle()
    assertEquals(agent, state.value.selectedAgent)
    compose.onNodeWithText("private-pane-id").assertDoesNotExist()
    compose.onNodeWithContentDescription("Back").performClick()
    compose.onNodeWithText("Synthetic Pi").assertIsDisplayed()
    compose.onNodeWithText("Foreign agent").assertDoesNotExist()
    compose.onNodeWithText("Synthetic machine").assertIsDisplayed()
    compose.onNodeWithText("Select a machine to view its agents").assertDoesNotExist()
    compose.onNodeWithText("1 saved").assertDoesNotExist()
  }

  @Test
  fun lateLoadedActiveMachineAlwaysShowsAgentsWithoutAnExpansionTap() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "late-tree",
        "Loaded machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val agent = AgentRow(machine, "pane", "pane", "Loaded Pi", "pi", "idle", false, null)
    val state = mutableStateOf(ConnectedUiState())
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
    }
    compose.runOnIdle {
      state.value =
        state.value.copy(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          agents = listOf(agent),
          connection = ConnectionState.Connected,
        )
    }
    compose.onNodeWithText("Loaded Pi").assertIsDisplayed()
    compose.onNodeWithText("Loaded machine").performClick()
    compose.onNodeWithText("Loaded Pi").assertIsDisplayed()
    compose.runOnIdle {
      state.value = state.value.copy(themeMode = com.azusachino.cappuccino.ui.ThemeMode.DARK)
    }
    compose.onNodeWithText("Loaded Pi").assertIsDisplayed()
  }

  @Test
  fun connectionDetailsShowVersionsAndNeverExposeMachineIds() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "diagnostics",
        "Synthetic machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val metadata =
      com.azusachino.cappuccino.io.BridgeDetails(
        machine,
        "0.1.0",
        1,
        mapOf("live_output" to true, "prompt_input" to false),
      )
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          connection = ConnectionState.Connected,
          bridgeDetails = mapOf(profile.id to metadata),
        )
      )
    val actions = TestActions(state)
    compose.activity.setContent {
      CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
    }
    compose.onNodeWithContentDescription("Machine actions").performClick()
    compose.onNodeWithText("Connection details").performClick()
    compose.onNodeWithText("Bridge version").assertIsDisplayed()
    compose.onNode(hasText("Bridge version") and hasText("0.1.0(v1)")).assertIsDisplayed()
    compose.onNodeWithText("App version").assertIsDisplayed()
    compose.onNodeWithText("Technical details").assertDoesNotExist()
    compose.onNodeWithText("Protocol").assertDoesNotExist()
    compose.onNodeWithText(machine.toString(), substring = true).assertDoesNotExist()
    compose.onNodeWithText("Machine ID", substring = true).assertDoesNotExist()
  }

  private fun openMachines() {
    compose.onNode(hasContentDescription("Machines") and hasClickAction()).performClick()
  }

  private class TestActions(private val state: MutableState<ConnectedUiState>) : ConnectedActions {
    var refreshCalls = 0

    override fun addMachine(label: String, url: String) = Unit

    override fun renameProfile(id: String, label: String) {
      state.value =
        state.value.copy(
          profiles = state.value.profiles.map { if (it.id == id) it.copy(label = label) else it }
        )
    }

    override fun selectProfile(id: String) = Unit

    override fun disconnectProfile() {
      state.value =
        state.value.copy(
          activeProfileId = null,
          selectedAgent = null,
          connection = ConnectionState.Disconnected,
        )
    }

    override fun refresh() = Unit

    override fun selectAgent(agent: AgentRow?) {
      state.value = state.value.copy(selectedAgent = agent)
    }

    override fun removeProfile(id: String) {
      state.value =
        state.value.copy(
          profiles = state.value.profiles.filterNot { it.id == id },
          activeProfileId = null,
          connection = ConnectionState.Disconnected,
        )
    }

    override fun refreshProfile(id: String) {
      refreshCalls++
    }

    override fun retry() = Unit

    override fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
      state.value = state.value.copy(themeMode = mode)
    }

    override fun submitPrompt(text: String, confirmed: Boolean) = Unit

    override fun answerPrompt(
      promptId: String,
      optionIndex: Int?,
      optionId: String?,
      action: String?,
    ) = Unit

    override fun loadConversation() = Unit
  }
}
