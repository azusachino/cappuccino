package com.azusachino.cappuccino

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.PromptOption
import com.azusachino.cappuccino.core.PromptQuestion
import com.azusachino.cappuccino.core.StreamEntry
import com.azusachino.cappuccino.core.StreamState
import com.azusachino.cappuccino.io.BridgeDetails
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.agentDraftKey
import com.azusachino.cappuccino.io.canMutate
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.time.ZoneId
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CappuccinoShellTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()

  @Test
  fun disconnectedJourneyAndRecreation() {
    // The app opens on Machines; agents are reached through their machine, not a Chats tab.
    compose.onNodeWithText("No machines configured").assertIsDisplayed()
    compose.onNodeWithContentDescription("Add machine").assertIsDisplayed()
    capture("Machines")

    compose.onNodeWithContentDescription("Add machine").performClick()
    compose.onNodeWithText("Bridge address").assertIsDisplayed()
    compose.onNodeWithText("Connect").assertIsDisplayed()
    compose.onNodeWithText("Bridge address").performClick()
    compose.waitForIdle()
    compose.onNodeWithText("Connect").assertIsDisplayed()
    capture("AddMachineIme")
    compose.activityRule.scenario.recreate()
    compose.waitForIdle()
    compose.onNodeWithText("No machines configured").assertIsDisplayed()

    compose.onNode(hasText("Settings") and hasClickAction()).performClick()
    compose.onNodeWithText("Theme").assertIsDisplayed()
    capture("Settings")
    compose.onNode(hasText("Machines") and hasClickAction()).performClick()
    compose.onNodeWithText("No machines configured").assertIsDisplayed()
  }

  @Test
  fun stopWhileWorkingAndNewSessionOnlyWhenIdle() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        "synthetic",
        "Synthetic machine",
        Endpoint.parse("https://bridge.example"),
        machine,
      )
    val agent =
      AgentRow(
        machine,
        "synthetic-session",
        "pane-1",
        "claude",
        "claude",
        "working",
        false,
        "main",
        nativeSessionRef = "synthetic-native",
      )
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          agents = listOf(agent),
          selectedAgent = agent,
          connection = ConnectionState.Connected,
          streamLive = true,
          stream = StreamState(agent.sessionId, 1, agentStatus = "working"),
          bridgeDetails =
            mapOf(
              profile.id to
                com.azusachino.cappuccino.io.BridgeDetails(
                  machine,
                  "0.1.0",
                  1,
                  mapOf(
                    "prompt_input" to true,
                    "agent_control" to true,
                    "mutation_binding" to true,
                  ),
                )
            ),
          sessionStats =
            com.azusachino.cappuccino.io.SessionStats(
              "claude-opus-5-5",
              "high",
              76_000,
              200_000,
              90,
            ),
        )
      )
    val actions = StateActions(state)
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme {
          val s by state
          CappuccinoScreen(s, actions) { _, _ -> }
        }
      }
    }

    compose
      .onNodeWithTag("composerModelLabel")
      .assertTextEquals("opus 5.5 · high")
      .assertIsDisplayed()
    assertSendAndStopSitAtTheEnd()
    compose.onNodeWithText("38% · 76K / 200K").assertIsDisplayed()
    compose.onNodeWithText("cache 90%").assertIsDisplayed()
    compose.onNodeWithContentDescription("Stop").performClick()
    compose.onNodeWithContentDescription("Commands").performClick()
    compose.onNodeWithText("New session").assertIsNotEnabled()
    compose.onNodeWithText("New session").performClick()
    assertEquals(listOf("interrupt"), actions.controls)

    compose.activity.runOnUiThread {
      state.value =
        state.value.copy(
          selectedAgent = agent.copy(status = "idle"),
          stream = state.value.stream.copy(agentStatus = "idle"),
        )
    }
    compose.waitForIdle()
    compose.onNodeWithContentDescription("Stop").assertDoesNotExist()
    // The menu stayed open; the same item is now enabled.
    compose.onNodeWithText("New session").performClick()
    compose.onNodeWithText("Start a new session?").assertIsDisplayed()
    compose.onNode(hasText("New session") and hasClickAction()).performClick()
    assertEquals(listOf("interrupt", "new_session"), actions.controls)
  }

  @Test
  fun replacementSessionDoesNotInheritComposerDraftOrCommandConfirmation() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("replacement", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent =
      AgentRow(
        machine,
        "same-locator",
        "pane",
        "pi",
        "pi",
        "idle",
        false,
        null,
        nativeSessionRef = "pi:original",
      )
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          selectedAgent = agent,
          connection = ConnectionState.Connected,
          streamLive = true,
          stream = StreamState(agent.sessionId, 1, agentStatus = "idle"),
          bridgeDetails = boundDetails(profile),
        )
      )
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme { CappuccinoScreen(state.value, StateActions(state)) { _, _ -> } }
      }
    }
    compose.onNodeWithTag("promptInput").performTextInput("/compact")
    compose.onNodeWithContentDescription("Send").performClick()
    compose.onNodeWithText("Run /compact?").assertIsDisplayed()
    compose.runOnIdle {
      state.value =
        state.value.copy(selectedAgent = agent.copy(nativeSessionRef = "pi:replacement"))
    }
    compose.onNodeWithText("Run /compact?").assertDoesNotExist()
    assertEquals(
      "",
      compose
        .onNodeWithTag("promptInput")
        .fetchSemanticsNode()
        .config[SemanticsProperties.EditableText]
        .text,
    )
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
    compose.onNodeWithText("Recent output · History not verified").assertIsDisplayed()
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
    awaitJumpAffordance()
    compose.onNodeWithTag("jumpToLatest").assertIsDisplayed()
    assertJumpAffordanceSitsAboveComposer()
    compose.onNodeWithTag("jumpToLatest").performClick()
    compose.onNodeWithText("Synthetic output 31").assertIsDisplayed()

    compose.runOnUiThread {
      state.value =
        state.value.copy(connection = ConnectionState.Error("Synthetic transport error"))
    }
    compose.onNodeWithText("Synthetic transport error").assertIsDisplayed()
    capture("ConnectionError")
    compose.onNodeWithContentDescription("Back").performClick()
    compose.waitForIdle()
    // Back returns to the machine the agent belongs to.
    compose.onNodeWithText("Synthetic machine").assertIsDisplayed()
  }

  @Test
  fun toolOnlyReasoningUpdatePreservesChatViewport() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("tool-only", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent = AgentRow(machine, "synthetic-session", "pane", "pi", "pi", "idle", false, "main")
    val tool = ConversationPart.ToolCall("bash", "pwd", null)
    val replies =
      (1..30).map {
        ConversationTurn("reply-$it", "agent", null, "Reply paragraph $it", emptyList())
      }
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          selectedAgent = agent,
          conversationTurns =
            replies + ConversationTurn("reply", "agent", null, "ANCHOR REPLY", listOf(tool)),
          connection = ConnectionState.Connected,
          conversationSource = "canonical_log",
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
    // The reader opens following the newest message.
    compose.waitForIdle()
    capture("ToolOnlyBefore")
    compose.onNodeWithText("ANCHOR REPLY").assertIsDisplayed()
    compose.waitForIdle()
    val before = compose.onNodeWithText("ANCHOR REPLY").fetchSemanticsNode().boundsInRoot.top
    compose.runOnIdle {
      state.value =
        state.value.copy(
          conversationTurns =
            state.value.conversationTurns +
              ConversationTurn(
                "tool-step",
                "agent",
                null,
                null,
                listOf(ConversationPart.Thinking("Inspecting another file"), tool),
              )
        )
    }
    compose.waitForIdle()
    compose.onNodeWithText("ANCHOR REPLY").assertIsDisplayed()
    val after = compose.onNodeWithText("ANCHOR REPLY").fetchSemanticsNode().boundsInRoot.top
    assertEquals(before, after, 0.5f)
    // Tool-only updates may leave the reader above the bottom, but never flag new output.
    compose.onNodeWithContentDescription("Jump to new output").assertDoesNotExist()
    capture("ToolOnlyAfter")

    compose.onNodeWithTag("recentOutputList").performTouchInput { swipeDown() }
    compose.waitForIdle()
    val readingBefore =
      compose
        .onNodeWithTag("recentOutputList")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          conversationTurns =
            state.value.conversationTurns.dropLast(1) +
              state.value.conversationTurns
                .last()
                .copy(
                  parts =
                    listOf(
                      ConversationPart.Thinking("Still inspecting"),
                      tool.copy(output = "done"),
                    )
                )
        )
    }
    compose.waitForIdle()
    val readingAfter =
      compose
        .onNodeWithTag("recentOutputList")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
    assertEquals(readingBefore, readingAfter, 0.01f)
    // Tool-only updates may leave the reader above the bottom, but never flag new output.
    compose.onNodeWithContentDescription("Jump to new output").assertDoesNotExist()

    compose.runOnIdle {
      state.value =
        state.value.copy(
          conversationTurns =
            state.value.conversationTurns +
              ConversationTurn("new-answer", "agent", null, "NEW ANSWER", emptyList())
        )
    }
    awaitJumpAffordance()
    compose.onNodeWithTag("jumpToLatest").performClick()
    compose.onNodeWithText("NEW ANSWER").assertIsDisplayed()
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
    // Markdown lays out over several frames and the follow re-scrolls as it grows; slow
    // emulators (CI's x86 AVD) need a bounded wait before the newest text is on screen.
    awaitDisplayed("FINAL-TAIL")
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
    awaitDisplayed("Same-count update")
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
    awaitJumpAffordance()
    compose.onNodeWithTag("jumpToLatest").assertIsDisplayed()
    assertJumpAffordanceSitsAboveComposer()
    val after =
      compose
        .onNodeWithTag("recentOutputList")
        .fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange]
        .value()
    assertEquals(before, after, 0.01f)
    compose.onNodeWithTag("jumpToLatest").performClick()
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
    compose.onNodeWithTag("jumpToLatest").assertDoesNotExist()
  }

  @Test
  fun visibleTimestampTracksDeviceTimezoneChangesWithoutReloadingConversation() {
    // This OS-setting journey is emulator-only; never mutate a physical device's timezone.
    assumeTrue("needs an emulator", shell("getprop ro.kernel.qemu").trim() == "1")
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
                // Only the person's prompts carry a time: HH:mm, the full local time as its
                // description.
                ConversationTurn(
                  "tz-turn",
                  "user",
                  "2026-10-07T16:02:03Z",
                  "Timezone prompt",
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
      compose.onNodeWithText("16:02").assertIsDisplayed()
      compose.onNodeWithContentDescription("2026-10-07 16:02:03").assertExists()
      setZone("Asia/Tokyo")
      compose.waitUntil(timeoutMillis = 5000) {
        compose.onAllNodesWithText("01:02").fetchSemanticsNodes().isNotEmpty()
      }
      compose.onNodeWithText("01:02").assertIsDisplayed()
      compose.onNodeWithContentDescription("2026-10-08 01:02:03").assertExists()
      compose.onNodeWithText("Timezone prompt").assertIsDisplayed()
      capture("DeviceTimezoneChanged")
    } finally {
      setZone(originalZone)
      compose.waitUntil(timeoutMillis = 5000) { ZoneId.systemDefault() == ZoneId.of(originalZone) }
    }
  }

  @Test
  fun pendingQuestionLayoutAndRecoveryInvalidateStaleActions() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("question", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent =
      AgentRow(
        machine,
        "question-session",
        "pane",
        "claude",
        "claude",
        "blocked",
        false,
        "main",
        nativeSessionRef = "synthetic-question-native",
      )
    val options = listOf("Red", "Blue", "Green").map { PromptOption(it.lowercase(), it, null) }
    val question = PromptQuestion("Color", "Which color should the theme use?", false, options)
    val state =
      mutableStateOf(
        ConnectedUiState(
          profiles = listOf(profile),
          activeProfileId = profile.id,
          selectedAgent = agent,
          bridgeDetails = boundDetails(profile),
          conversationTurns =
            (1..20).map {
              ConversationTurn("t$it", "assistant", null, "Synthetic turn $it", emptyList())
            },
          connection = ConnectionState.Connected,
          streamLive = true,
          stream =
            StreamState(
              "question-session",
              1,
              agentStatus = "blocked",
              pendingPrompt =
                PromptCard(
                  "p-question-1",
                  "ask_user_question",
                  "Color",
                  null,
                  null,
                  null,
                  options,
                  0,
                  listOf(question),
                ),
            ),
        )
      )
    val actions = StateActions(state)
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme {
          val s by state
          CappuccinoScreen(s, actions) { _, _ -> }
        }
      }
    }
    awaitDisplayed("Which color should the theme use?")
    val transcript = compose.onNodeWithTag("recentOutputList").fetchSemanticsNode().boundsInRoot
    val card = compose.onNodeWithTag("pendingPrompt").fetchSemanticsNode().boundsInRoot
    val composer = compose.onNodeWithTag("promptInput").fetchSemanticsNode().boundsInRoot
    // The transcript keeps the top of the screen; the question waits by the composer.
    assertTrue(
      "Card must follow the transcript (card=$card, transcript=$transcript)",
      card.top >= transcript.bottom - 1f,
    )
    assertTrue(
      "Card must sit above the composer (card=$card, composer=$composer)",
      card.bottom <= composer.top + 1f,
    )
    assertTrue(
      "Transcript must stay at the top (transcript=$transcript)",
      transcript.top < card.top,
    )
    capture("PendingQuestion")

    compose.onNodeWithTag("promptInput").performTextInput("Review first")
    compose.onNodeWithContentDescription("Send").assertIsEnabled()
    compose.runOnIdle {
      state.value =
        state.value.copy(
          connection = ConnectionState.Recovering,
          streamLive = false,
          stream = state.value.stream.copy(pendingPrompt = null, agentStatus = null),
        )
    }
    compose.onNodeWithTag("pendingPrompt").assertDoesNotExist()
    compose.onNodeWithText("Unknown", substring = true).assertIsDisplayed()
    compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    compose.onNodeWithTag("promptInput").assertTextEquals("Review first")
    assertTrue(actions.prompts.isEmpty())

    // Reopening transport alone must not re-enable actions or replay the retained draft.
    compose.runOnIdle {
      state.value =
        state.value.copy(
          connection = ConnectionState.Connected,
          stream = StreamState("question-session", 2),
        )
    }
    compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    assertTrue(actions.prompts.isEmpty())
    compose.runOnIdle {
      state.value =
        state.value.copy(
          streamLive = true,
          stream = state.value.stream.copy(agentStatus = "idle"),
        )
    }
    compose.onNodeWithContentDescription("Send").assertIsEnabled()
    compose.onNodeWithTag("pendingPrompt").assertDoesNotExist()
    assertTrue(actions.prompts.isEmpty())
    compose.onNodeWithContentDescription("Send").performClick()
    assertEquals(listOf("Review first"), actions.prompts)
  }

  private fun boundDetails(profile: MachineProfile, advertised: Boolean? = true) =
    mapOf(
      profile.id to
        BridgeDetails(
          profile.machineId,
          "fixture",
          1,
          mapOf("prompt_input" to true, "prompt_answers" to true, "agent_control" to true) +
            (advertised?.let { mapOf("mutation_binding" to it) } ?: emptyMap()),
        )
    )

  @Test
  fun staleRenderedComposerCannotBorrowReplacementBinding() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("stale-render", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent =
      AgentRow(
        machine,
        "same-locator",
        "pane",
        "pi",
        "pi",
        "idle",
        false,
        null,
        nativeSessionRef = "native-A",
      )
    val rendered =
      ConnectedUiState(
        profiles = listOf(profile),
        activeProfileId = profile.id,
        selectedAgent = agent,
        connection = ConnectionState.Connected,
        streamLive = true,
        stream = StreamState(agent.sessionId, 1, agentStatus = "idle"),
        bridgeDetails = boundDetails(profile),
      )
    val current = mutableStateOf(rendered)
    val actions = StateActions(current)
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        // Deliberately freeze the rendered snapshot, simulating a callback before recomposition.
        CappuccinoTheme { CappuccinoScreen(rendered, actions) { _, _ -> } }
      }
    }
    compose.onNodeWithTag("promptInput").performTextInput("Old draft")
    compose.runOnIdle {
      current.value = current.value.copy(selectedAgent = agent.copy(nativeSessionRef = "native-B"))
    }
    compose.onNodeWithContentDescription("Send").performClick()
    compose.onNodeWithContentDescription("Commands").performClick()
    compose.onNodeWithText("New session").performClick()
    compose.onNodeWithText("Start a new session?").assertIsDisplayed()
    compose.onNode(hasText("New session") and hasClickAction()).performClick()
    assertTrue(actions.prompts.isEmpty())
    assertTrue(actions.controls.isEmpty())
    assertEquals("Old draft", current.value.drafts[agentDraftKey(agent)])
    assertNull(current.value.drafts[agentDraftKey(agent.copy(nativeSessionRef = "native-B"))])
  }

  @Test
  fun legacyOrIncompleteBindingKeepsLiveComposerReadOnly() {
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("binding", "Synthetic", Endpoint.parse("https://bridge.example"), machine)
    val agent =
      AgentRow(
        machine,
        "fixture",
        "pane",
        "pi",
        "pi",
        "idle",
        false,
        null,
        nativeSessionRef = "native-A",
      )
    val ready =
      ConnectedUiState(
        profiles = listOf(profile),
        activeProfileId = profile.id,
        selectedAgent = agent,
        connection = ConnectionState.Connected,
        streamLive = true,
        stream = StreamState(agent.sessionId, 1, agentStatus = "idle"),
        bridgeDetails = boundDetails(profile),
        drafts = mapOf(agentDraftKey(agent) to "Retained draft"),
      )
    val state = mutableStateOf(ready)
    val actions = StateActions(state)
    compose.activity.runOnUiThread {
      compose.activity.setContent {
        CappuccinoTheme { CappuccinoScreen(state.value, actions) { _, _ -> } }
      }
    }
    compose.onNodeWithContentDescription("Send").assertIsEnabled()
    for (readonly in
      listOf(
        ready.copy(bridgeDetails = boundDetails(profile, null)),
        ready.copy(bridgeDetails = boundDetails(profile, false)),
        ready.copy(selectedAgent = agent.copy(nativeSessionRef = null)),
      )) {
      compose.runOnIdle { state.value = readonly }
      compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
      compose
        .onNodeWithText("Session-bound actions unavailable.", substring = true)
        .assertIsDisplayed()
      assertTrue(actions.prompts.isEmpty())
    }
  }

  private fun assertSendAndStopSitAtTheEnd() {
    // The model chip must not take the slack: Stop and Send hug the composer's end edge.
    val composer = compose.onNodeWithTag("promptInput").fetchSemanticsNode().boundsInRoot
    val send = compose.onNodeWithContentDescription("Send").fetchSemanticsNode().boundsInRoot
    val stop = compose.onNodeWithContentDescription("Stop").fetchSemanticsNode().boundsInRoot
    val slack = composer.right - send.right
    assertTrue("Send must end at the composer edge (send=$send, field=$composer)", slack < 48f)
    assertTrue(
      "Stop must sit just before Send (stop=$stop, send=$send)",
      stop.right <= send.left + 1f && send.left - stop.right < 48f,
    )
  }

  private fun assertJumpAffordanceSitsAboveComposer() {
    // Contract geometry, not just presence: the jump button overlays the transcript's
    // bottom-end corner (pi-pocket's `.jump`) and never reaches into the composer.
    val jump = compose.onNodeWithTag("jumpToLatest").fetchSemanticsNode().boundsInRoot
    val composer = compose.onNodeWithTag("promptInput").fetchSemanticsNode().boundsInRoot
    val transcript = compose.onNodeWithTag("recentOutputList").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "Jump must sit inside the transcript's lower half (jump=$jump, transcript=$transcript)",
      jump.top >= transcript.center.y && jump.bottom <= transcript.bottom,
    )
    assertTrue(
      "Jump must sit at the end edge (jump=$jump, transcript=$transcript)",
      jump.right > transcript.center.x,
    )
    assertTrue(
      "Jump must stay above the composer (jump=$jump, composer=$composer)",
      jump.bottom <= composer.top,
    )
  }

  private fun awaitDisplayed(text: String) {
    compose.waitUntil(timeoutMillis = 5_000) {
      runCatching { compose.onNodeWithText(text).assertIsDisplayed() }.isSuccess
    }
  }

  private fun awaitJumpAffordance() {
    // Wait (bounded, test thread) until the jump button is laid out with real size.
    compose.waitUntil(timeoutMillis = 5_000) {
      compose.onAllNodesWithTag("jumpToLatest").fetchSemanticsNodes().any {
        it.boundsInRoot.width > 0
      }
    }
    compose.waitForIdle()
  }

  private fun shell(command: String): String =
    ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
      )
      .bufferedReader()
      .use { it.readText() }

  private class StateActions(private val state: MutableState<ConnectedUiState>) : ConnectedActions {
    override fun acceptsMutation(profileId: String?, agent: AgentRow): Boolean {
      val current = state.value.selectedAgent ?: return false
      return state.value.canMutate &&
        state.value.activeProfileId == profileId &&
        current.machineId == agent.machineId &&
        current.sessionId == agent.sessionId &&
        current.paneId == agent.paneId &&
        current.nativeSessionRef == agent.nativeSessionRef
    }

    val controls = mutableListOf<String>()
    val prompts = mutableListOf<String>()

    override fun controlAgent(action: String) {
      controls += action
    }

    override fun addMachine(label: String, url: String) = Unit

    override fun renameProfile(id: String, label: String) {
      state.value =
        state.value.copy(
          profiles = state.value.profiles.map { if (it.id == id) it.copy(label = label) else it }
        )
    }

    override fun selectProfile(id: String) = Unit

    override fun disconnectProfile() = Unit

    override fun refresh() = Unit

    override fun selectAgent(agent: AgentRow?) {
      state.value = state.value.copy(selectedAgent = agent)
    }

    override fun removeProfile(id: String) = Unit

    override fun retry() = Unit

    override fun setAppPalette(palette: com.azusachino.cappuccino.ui.AppPalette) {
      state.value = state.value.copy(appPalette = palette)
    }

    override fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
      state.value = state.value.copy(themeMode = mode)
    }

    override fun setDraft(key: String, text: String) {
      state.value =
        state.value.copy(
          drafts =
            if (text.isEmpty()) state.value.drafts - key else state.value.drafts + (key to text)
        )
    }

    override fun submitPrompt(text: String, confirmed: Boolean) {
      prompts += text
    }

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
