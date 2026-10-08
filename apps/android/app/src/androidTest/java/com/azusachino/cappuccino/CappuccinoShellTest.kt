package com.azusachino.cappuccino

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.StreamEntry
import com.azusachino.cappuccino.core.StreamState
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CappuccinoShellTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun disconnectedJourneyAndRecreation() {
    compose
      .onNodeWithText("Select or add a bridge in Machines to discover agents.")
      .assertIsDisplayed()
    compose.onNodeWithText("Not connected · 0 agents").assertIsDisplayed()
    capture("Chats")

    compose.onNode(hasText("Attention") and hasClickAction()).performClick()
    compose.onNodeWithText("No Pending Approvals").assertIsDisplayed()
    compose
      .onNodeWithText(
        "All agents are running smoothly. Any tool approval or ask_question prompts will appear here."
      )
      .assertIsDisplayed()
    capture("Attention")

    compose.onNode(hasText("Machines") and hasClickAction()).performClick()
    compose.onNodeWithText("No machines configured").assertIsDisplayed()
    compose.onNodeWithContentDescription("Add machine").assertIsDisplayed()
    capture("Machines")

    compose.onNodeWithContentDescription("Add machine").performClick()
    compose.onNodeWithText("Private bridge URL").assertIsDisplayed()
    compose.onNodeWithText("Connect").assertIsDisplayed()
    compose.onNodeWithText("Private bridge URL").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Connect").assertIsDisplayed()
    capture("AddMachineIme")
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithText("No machines configured").assertIsDisplayed()
    compose.onNode(hasText("Chats") and hasClickAction()).performClick()
    compose
      .onNodeWithText("Select or add a bridge in Machines to discover agents.")
      .assertIsDisplayed()
  }

  @Test
  fun selectedOutputFollowsNewRowsAndShowsTransportError() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "synthetic",
        "Synthetic machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val agent = AgentRow(machine, "synthetic-session", "pane-1", "pi", "pi", "idle", false, "main")
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          agents = listOf(agent),
          selectedAgent = agent,
          stream =
            StreamState(
              "synthetic-session",
              1,
              (1L..30L).map { StreamEntry("e$it", it, "text", "Synthetic output $it", "main") },
            ),
          connection = ConnectionState.Connected,
        )
      )
    val actions = StateActions(state)
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme {
          val s by state
          CappuccinoScreen(s, actions, requestConnect = { _, _ -> })
        }
      }
    }
    compose.onNodeWithText("Recent agent output · Read-only").assertIsDisplayed()
    compose.onNodeWithText("Synthetic output 30").assertIsDisplayed()
    capture("SelectedOutput")

    compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
    compose.runOnUiThread {
      state.value =
        state.value.copy(
          stream =
            state.value.stream.copy(
              entries =
                state.value.stream.entries +
                  StreamEntry("e31", 31, "text", "Synthetic output 31", "main")
            )
        )
    }
    compose.waitForIdle()
    compose.onNodeWithText("Jump to latest").assertIsDisplayed()
    compose.onNodeWithText("Jump to latest").performClick()
    compose.onNodeWithText("Synthetic output 31").assertIsDisplayed()

    compose.runOnUiThread {
      state.value =
        state.value.copy(connection = ConnectionState.Error("Synthetic transport error"))
    }
    compose.onNodeWithText("Synthetic transport error").assertIsDisplayed()
    capture("ConnectionError")
    compose.onNodeWithContentDescription("Back").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("synthetic-session", substring = true).assertIsDisplayed()
  }

  @Test
  fun structuredConversationFollowsGrowthButPreservesReadingPositionAndMachineIdentity() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("conversation", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent = AgentRow(machine, "same-locator", "pane", "pi", "pi", "idle", false, "main")
    val tail = (1..40).joinToString("\n\n") { "Tail paragraph $it" } + "\n\nFINAL-TAIL"
    val turns =
      (1..30)
        .map { ConversationTurn("t$it", "assistant", null, "Synthetic turn $it", emptyList()) }
        .dropLast(1) +
        ConversationTurn("t30", "assistant", null, null, listOf(ConversationPart.Text(tail)))
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          selectedAgent = agent,
          conversationTurns = turns,
          connection = ConnectionState.Connected,
          stream =
            StreamState(
              "same-locator",
              1,
              (1L..90L).map { StreamEntry("e$it", it, "text", "Raw output $it", "main") },
            ),
        )
      )
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme {
          val s by state
          CappuccinoScreen(s, StateActions(state)) { _, _ -> }
        }
      }
    }
    compose.onNodeWithText("FINAL-TAIL").assertIsDisplayed()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          conversationTurns =
            state.value.conversationTurns.dropLast(1) +
              state.value.conversationTurns
                .last()
                .copy(parts = listOf(ConversationPart.Text(tail + "\n\nSame-count update")))
        )
    }
    compose.onNodeWithText("Same-count update").assertIsDisplayed()
    compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
    compose.waitForIdle()
    val before =
      compose
        .onNodeWithTag("recentOutputList")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          conversationTurns =
            state.value.conversationTurns +
              ConversationTurn("t31", "assistant", null, "Newest structured response", emptyList())
        )
    }
    compose.onNodeWithText("Jump to latest").assertIsDisplayed()
    val after =
      compose
        .onNodeWithTag("recentOutputList")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
    assertEquals(before, after, 0.01f)
    compose.onNodeWithText("Jump to latest").performClick()
    compose.onNodeWithText("Newest structured response").assertIsDisplayed()
    capture("ConversationLatest")
    compose.runOnIdle {
      val otherMachine = UUID.randomUUID()
      val otherProfile = profile.copy(id = "other-machine", machineId = otherMachine)
      state.value =
        state.value.copy(
          profiles = listOf(otherProfile),
          activeProfileId = otherProfile.id,
          selectedAgent = agent.copy(machineId = otherMachine),
          conversationTurns =
            listOf(ConversationTurn("t31", "assistant", null, "Other machine latest", emptyList())),
        )
    }
    compose.onNodeWithText("Other machine latest").assertIsDisplayed()
    compose.onNodeWithText("Jump to latest").assertDoesNotExist()
  }

  @Test
  fun visibleTimestampTracksDeviceTimezoneChangesWithoutReloadingConversation() {
    // This OS-setting journey is emulator-only; never mutate a physical phone's timezone.
    assertEquals("1", shell("getprop ro.kernel.qemu").trim())
    val originalZone = ZoneId.of(shell("getprop persist.sys.timezone").trim()).id
    fun setZone(zone: String) {
      require(zone.matches(Regex("[A-Za-z0-9_+/:.-]+")))
      shell("cmd alarm set-timezone $zone")
      assertEquals(zone, shell("getprop persist.sys.timezone").trim())
    }
    try {
      setZone("UTC")
      val machine = UUID.randomUUID()
      val profile =
        MachineProfile("timezone", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
      val agent = AgentRow(machine, "timezone-agent", "pane", "pi", "pi", "idle", false, "main")
      val state =
        mutableStateOf(
          ConnectedUiState(
            profiles = listOf(profile),
            activeProfileId = profile.id,
            selectedAgent = agent,
            conversationTurns =
              listOf(
                ConversationTurn(
                  "tz-turn",
                  "assistant",
                  "2026-10-07T16:02:03Z",
                  "Timezone reply",
                  emptyList(),
                )
              ),
            connection = ConnectionState.Connected,
          )
        )
      compose.activity.runOnUiThread {
        compose.activity.setContent {
          CappuccinoTheme {
            val s by state
            CappuccinoScreen(s, StateActions(state), timeZone = compose.activity.deviceTimeZone) {
              _,
              _ ->
            }
          }
        }
      }
      compose.onNodeWithText("2026-10-07 16:02:03").assertIsDisplayed()
      setZone("Asia/Tokyo")
      compose.waitUntil(timeoutMillis = 5000) {
        compose.onAllNodesWithText("2026-10-08 01:02:03").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("2026-10-08 01:02:03").assertIsDisplayed()
      compose.onNodeWithText("Timezone reply").assertIsDisplayed()
      capture("DeviceTimezoneChanged")
    } finally {
      setZone(originalZone)
      compose.waitUntil(timeoutMillis = 5000) { ZoneId.systemDefault() == ZoneId.of(originalZone) }
    }
  }

  private fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
      )
      .bufferedReader()
      .use { it.readText() }

  private class StateActions(private val state: MutableState<ConnectedUiState>) : ConnectedActions {
    override fun addMachine(label: String, url: String) = Unit

    override fun selectProfile(id: String) = Unit

    override fun disconnectProfile() = Unit

    override fun refresh() = Unit

    override fun selectAgent(agent: AgentRow?) {
      state.value = state.value.copy(selectedAgent = agent)
    }

    override fun removeProfile(id: String) = Unit

    override fun retry() = Unit

    override fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
      state.value = state.value.copy(themeMode = mode)
    }

    override fun submitPrompt(text: String) = Unit

    override fun answerPrompt(
      promptId: String,
      optionIndex: Int?,
      optionId: String?,
      action: String?,
    ) = Unit

    override fun loadConversation() = Unit
  }

  private fun capture(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
      PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
      }
    } finally {
      bitmap.recycle()
    }
  }
}
