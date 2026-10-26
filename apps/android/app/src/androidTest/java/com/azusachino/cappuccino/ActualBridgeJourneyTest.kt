package com.azusachino.cappuccino

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.io.*
import com.azusachino.cappuccino.ui.CappuccinoScreen
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Requires make ui-test-android: no fake BridgeTransport, HTTP or WebSocket on the app side. */
@RunWith(AndroidJUnit4::class)
class ActualBridgeJourneyTest {
  @get:Rule val compose = createAndroidComposeRule<MainActivity>()
  private val instrumentation = InstrumentationRegistry.getInstrumentation()

  private fun <T> main(block: () -> T): T {
    val result = AtomicReference<T>()
    instrumentation.runOnMainSync { result.set(block()) }
    return result.get()
  }

  @Test
  fun replacementAndCanonicalRecoveryUseActualBridgeWithoutReplayingInput() {
    val arguments = InstrumentationRegistry.getArguments()
    val bridgePort =
      requireNotNull(arguments.getString("bridgePort")) {
          "Run make ui-test-android with its owned bridge fixture"
        }
        .toInt()
    val fixturePort = requireNotNull(arguments.getString("fixturePort")).toInt()
    val endpoint = Endpoint.parse("http://127.0.0.1:$bridgePort", allowLocalHttp = true)
    val wire = BridgeClient(endpoint)
    val machine = runBlocking { wire.verify() }
    val profile =
      MachineProfile(
        "actual-bridge-${UUID.randomUUID()}",
        "Synthetic actual bridge",
        endpoint,
        machine,
      )
    val store = ProfileStore(instrumentation.targetContext)
    store.save(profile)
    val vm = main {
      ConnectedViewModel(store, { BridgeClient(it) }, { delay(200) }, SavedStateHandle())
    }

    fun fixture(body: String? = null): JSONObject {
      val connection = URL("http://127.0.0.1:$fixturePort/").openConnection() as HttpURLConnection
      connection.connectTimeout = 5000
      connection.readTimeout = 5000
      try {
        if (body != null) {
          connection.requestMethod = "POST"
          connection.doOutput = true
          connection.setRequestProperty("Content-Type", "application/json")
          connection.outputStream.use { it.write(body.toByteArray()) }
        }
        assertEquals(200, connection.responseCode)
        return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
      } finally {
        connection.disconnect()
      }
    }

    fun awaitState(predicate: (ConnectedUiState) -> Boolean): ConnectedUiState = runBlocking {
      withTimeout(12_000) { vm.state.first { predicate(it) } }
    }

    fun attach(native: String, screen: String = "plain"): AgentRow {
      fixture("""{"native":"$native","screen":"$screen","history":true,"outage":false}""")
      val row = runBlocking { wire.agents(machine).single() }
      main {
        vm.selectAgent(null)
        vm.selectAgent(row)
      }
      awaitState {
        it.canMutate &&
          it.selectedAgent?.nativeSessionRef == row.nativeSessionRef &&
          it.conversationId != null
      }
      return row
    }

    try {
      main {
        vm.setForeground(true)
        vm.selectProfile(profile.id)
      }
      awaitState { it.agents.isNotEmpty() }
      for (action in listOf("prompt", "interrupt", "answer")) {
        attach("A", if (action == "answer") "approval" else "plain")
        val card =
          if (action == "answer")
            awaitState { it.stream.pendingPrompt != null }.stream.pendingPrompt
          else null
        fixture("""{"native":"B","clear_journal":true}""")
        main {
          assertTrue(vm.state.value.canMutate)
          when (action) {
            "prompt" -> vm.submitPrompt("Synthetic stale input")
            "interrupt" -> vm.controlAgent("interrupt")
            else ->
              vm.answerPrompt(
                requireNotNull(card).promptId,
                card.options.lastIndex,
                card.options.last().id,
                "select_option",
              )
          }
        }
        awaitState {
          !it.streamLive && it.actionError?.contains("Selected session changed") == true
        }
        assertEquals(
          "stale $action must produce zero terminal RPCs",
          0,
          fixture().getJSONArray("journal").length(),
        )
        assertNull(vm.state.value.stream.pendingPrompt)
      }

      // Freeze the old rendered screen while the actual model attaches to B. A wrong
      // callback would borrow B's valid binding and the real bridge would accept it.
      val old = attach("A")
      val rendered = vm.state.value
      compose.activity.runOnUiThread {
        compose.activity.setContent {
          CappuccinoTheme { CappuccinoScreen(rendered, vm) { _, _ -> } }
        }
      }
      compose.onNodeWithTag("promptInput").performTextReplacement("Old rendered draft")
      val replacement = attach("B")
      fixture("""{"clear_journal":true}""")
      compose.onNodeWithContentDescription("Send").performClick()
      compose.onNodeWithContentDescription("Commands").performClick()
      compose.onNodeWithText("New session").performClick()
      compose.onNodeWithText("Start a new session?").assertIsDisplayed()
      compose.onNode(hasText("New session") and hasClickAction()).performClick()
      assertEquals(0, fixture().getJSONArray("journal").length())
      assertEquals("Old rendered draft", vm.state.value.drafts[agentDraftKey(old)])
      assertNull(vm.state.value.drafts[agentDraftKey(replacement)])
      main { vm.submitPrompt("Synthetic B positive control") }
      awaitState {
        it.promptSends[agentSendKey(profile.id, replacement)]
          ?.outcome
          ?.startsWith("Sent to bridge") == true
      }
      val positive = fixture().getJSONArray("journal")
      assertEquals(1, positive.length())
      assertEquals("B", positive.getJSONObject(0).getString("native"))

      val original = attach("A")
      val canonical = vm.state.value.conversationId
      fixture("""{"clear_journal":true,"hold_next":true}""")
      main {
        vm.setDraft(agentDraftKey(original), "Synthetic pending input")
        vm.submitPrompt("Synthetic pending input")
      }
      compose.waitUntil(timeoutMillis = 5000) { fixture().getBoolean("waiting") }
      assertNull(vm.state.value.promptSends[agentSendKey(profile.id, original)]?.outcome)
      fixture("""{"history":false,"outage":true,"release":true}""")
      awaitState {
        !it.streamLive &&
          it.promptSends[agentSendKey(profile.id, original)]
            ?.outcome
            ?.startsWith("Delivery unknown") == true
      }
      fixture("""{"outage":false}""")
      main { vm.retry() }
      awaitState {
        (it.connection as? ConnectionState.Error)
          ?.message
          ?.contains("history", ignoreCase = true) == true
      }
      main {
        assertFalse(vm.state.value.canMutate)
        vm.submitPrompt("Must not send during missing history")
        vm.controlAgent("interrupt")
        vm.answerPrompt("old-card", 0, "reject", "select_option")
      }
      assertEquals(1, fixture().getJSONArray("journal").length())
      fixture("""{"history":true}""")
      main { vm.retry() }
      awaitState { it.canMutate && it.conversationId == canonical }
      val journal = fixture().getJSONArray("journal")
      assertEquals("recovery is read-only: never replay ambiguous input", 1, journal.length())
      assertEquals(
        "Synthetic pending input",
        journal.getJSONObject(0).getJSONObject("params").getString("text"),
      )
      assertEquals("Synthetic pending input", vm.state.value.drafts[agentDraftKey(original)])
    } finally {
      main { vm.setForeground(false) }
      store.remove(profile.id)
    }
  }
}
