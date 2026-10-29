package com.azusachino.cappuccino

import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MachinesJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

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
    compose.onNodeWithText("Label").performTextInput("Synthetic bridge")
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
    compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
    // The injected click is delivered after waitForIdle can return; wait for its effect, then
    // require exactly one call so a double-fire still fails.
    compose.waitUntil { actions.retryCalls > 0 }
    compose.waitForIdle()
    assertEquals(1, actions.retryCalls)
    compose.onNodeWithContentDescription("Machine actions").performClick()
    compose.onNodeWithText("Remove").performClick()
    compose.onNodeWithText("No machines configured").assertIsDisplayed()
    assertTrue(state.value.profiles.isEmpty())
  }

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
    compose.onNodeWithText("Protocol").assertDoesNotExist()
    compose.onNodeWithText("Technical details").performClick()
    compose.onNodeWithText("Bridge version").assertIsDisplayed()
    check(compose.onAllNodesWithText("0.1.0").fetchSemanticsNodes().isNotEmpty())
    compose.onNodeWithText("Protocol version").assertIsDisplayed()
    compose.onNodeWithText("1").assertIsDisplayed()
    compose.onNodeWithContentDescription("Back").performClick()
    compose.onNodeWithText("Bridge version").assertIsDisplayed()
    compose.onNodeWithText(machine.toString(), substring = true).assertDoesNotExist()
    compose.onNodeWithText("Machine ID", substring = true).assertDoesNotExist()
  }

  private fun openMachines() {
    compose.onNode(hasContentDescription("Machines") and hasClickAction()).performClick()
  }

  private class TestActions(private val state: MutableState<ConnectedUiState>) : ConnectedActions {
    var retryCalls = 0

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

    override fun retry() {
      retryCalls++
    }

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
