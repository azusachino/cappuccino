package com.azusachino.cappuccino

import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
              state.value.copy(connection = ConnectionState.Error("Synthetic connection failed"))
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
    compose.onNodeWithText("Add machine").performClick()
    compose.onNodeWithText("Label (optional)").performTextInput("Synthetic bridge")
    compose.onNodeWithText("Private bridge URL").performTextInput("https://bridge.example")
    compose.onNodeWithText("Connect").performClick()
    assertTrue(
      compose.onAllNodesWithText("Synthetic connection failed").fetchSemanticsNodes().isNotEmpty()
    )
    compose.onNodeWithText("Private bridge URL").assertIsDisplayed()
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
      CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
    }
    openMachines()
    compose.onNodeWithText("Retry connection").performClick()
    assertEquals(1, actions.retryCalls)
    compose.onNodeWithText("Remove").performClick()
    compose.onNodeWithText("No machines added").assertIsDisplayed()
    assertTrue(state.value.profiles.isEmpty())
  }

  private fun openMachines() {
    compose.onAllNodesWithText("Machines")[1].performClick()
  }

  private class TestActions(private val state: MutableState<ConnectedUiState>) : ConnectedActions {
    var retryCalls = 0

    override fun addMachine(label: String, url: String) = Unit

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

    override fun selectAgent(agent: AgentRow?) = Unit

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

    override fun submitPrompt(text: String) = Unit

    override fun answerPrompt(promptId: String, optionIndex: Int?, optionId: String?, action: String?) = Unit

    override fun loadConversation() = Unit
  }
}
