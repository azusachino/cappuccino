package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.MutationBinding
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.PromptOption
import com.azusachino.cappuccino.core.StreamEntry
import com.azusachino.cappuccino.core.StreamEvent
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedViewModelTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val profile =
    MachineProfile("p", "Machine", Endpoint.parse("https://bridge.example"), machine)
  private val nativeRef = "pi:aaaa-native"
  private val agent =
    AgentRow(
      machine,
      "locator",
      "pane",
      "Agent",
      "pi",
      "idle",
      false,
      null,
      nativeSessionRef = nativeRef,
    )
  private val savedId = "pi:${"a".repeat(64)}"
  private val store = MemoryProfiles(profile)
  private val dispatcher = StandardTestDispatcher()
  private lateinit var viewModel: ConnectedViewModel
  @org.junit.Rule @JvmField val offlineFolder = org.junit.rules.TemporaryFolder()

  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun teardown() {
    viewModel.setForeground(false)
    Dispatchers.resetMain()
  }

  @Test
  fun openThenFailureExhaustsInitialAndFourRetriesWithoutReset() =
    runTest(dispatcher) {
      var attempts = 0
      val delays = mutableListOf<Long>()
      viewModel =
        createViewModel({ delays += it }) { stream ->
          flow {
            attempts++
            emit(StreamEvent.Open(stream, attempts.toLong()))
            throw IOException("disconnected")
          }
        }
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      assertEquals(5, attempts)
      assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
    }

  @Test
  fun preOpenFailureExhaustsAndManualRetryStartsFreshBudget() =
    runTest(dispatcher) {
      var attempts = 0
      val delays = mutableListOf<Long>()
      viewModel =
        createViewModel({ delays += it }) {
          flow {
            attempts++
            throw IOException("before open")
          }
        }
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      assertEquals(5, attempts)
      viewModel.retry()
      advanceUntilIdle()
      assertEquals(10, attempts)
      assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L).repeatTwice(), delays)
    }

  @Test
  fun bridgeErrorIsTerminalAndCancelsOpenStream() =
    runTest(dispatcher) {
      var cancelled = false
      var attempts = 0
      viewModel =
        createViewModel({}) { session ->
          flow {
            attempts++
            try {
              emit(StreamEvent.Open(session, 1))
              emit(StreamEvent.AgentStatus(session, "working", "Old detail"))
              emit(
                StreamEvent.PromptRequest(
                  session,
                  PromptCard(
                    "p-1",
                    "approval",
                    "Approve?",
                    null,
                    null,
                    null,
                    listOf(PromptOption("a", "Yes", null)),
                    0,
                  ),
                )
              )
              emit(StreamEvent.Error("not_found", "gone"))
              kotlinx.coroutines.awaitCancellation()
            } finally {
              cancelled = true
            }
          }
        }
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      assertEquals(1, attempts)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      assertEquals("not_found: gone", viewModel.state.value.stream.error)
      assertNull(viewModel.state.value.stream.pendingPrompt)
      assertNull(viewModel.state.value.stream.agentStatusDetail)
      assertFalse(viewModel.state.value.streamLive)
      assertTrue(cancelled)
    }

  @Test
  fun upstreamUnavailableRetriesWithinBudgetClearsStalePromptAndRequiresFreshStatus() =
    runTest(dispatcher) {
      var attempts = 0
      val delays = mutableListOf<Long>()
      viewModel =
        createViewModel(
          pause = { delays += it },
          streamFactory = { session ->
            flow {
              attempts++
              emit(StreamEvent.Open(session, attempts.toLong()))
              if (attempts == 1) {
                emit(
                  StreamEvent.PromptRequest(
                    session,
                    PromptCard(
                      "p-1",
                      "approval",
                      "Approve?",
                      null,
                      null,
                      null,
                      listOf(PromptOption("a", "Yes", null)),
                      0,
                    ),
                  )
                )
                emit(
                  StreamEvent.Error(
                    "upstream_unavailable",
                    "The agent stream source is temporarily unavailable.",
                  )
                )
              } else {
                emit(StreamEvent.AgentStatus(session, "idle", null))
              }
              kotlinx.coroutines.awaitCancellation()
            }
          },
        )
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      advanceUntilIdle()
      // One bounded retry; the stale approval card does not survive source loss.
      assertEquals(2, attempts)
      assertEquals(listOf(1_000L), delays)
      assertNull(viewModel.state.value.stream.pendingPrompt)
      assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
      // The reopened stream is live only after its fresh authoritative agent_status.
      assertTrue(viewModel.state.value.streamLive)
      assertTrue(viewModel.state.value.stream.pendingPrompt == null)
    }

  @Test
  fun replacedIdentityOnReconnectFailsClosedWithoutAttaching() =
    runTest(dispatcher) {
      val replacement = agent.copy(nativeSessionRef = "pi:replaced")
      var catalogAgent = agent
      var streamAttempts = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(catalogAgent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            streamAttempts++
            emit(StreamEvent.Open(sessionId, 1))
            emit(StreamEvent.AgentStatus(sessionId, "working", null))
            catalogAgent = replacement
            throw IOException("source lost before locator reuse")
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      // The first binding was valid. Its replacement must never receive the reconnect.
      assertEquals(1, streamAttempts)
      assertNull(viewModel.state.value.selectedAgent)
      assertEquals(
        ConnectionState.Error("Selected session is missing or has changed; select it again"),
        viewModel.state.value.connection,
      )
      assertTrue(viewModel.state.value.stream.pendingPrompt == null)
    }

  @Test
  fun missingNativeIdentityAllowsManualViewButNeverAutomaticRecovery() =
    runTest(dispatcher) {
      val legacy = agent.copy(nativeSessionRef = null)
      var streamAttempts = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(legacy)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            streamAttempts++
            emit(StreamEvent.Open(sessionId, 1))
            emit(StreamEvent.AgentStatus(sessionId, "idle", null))
            emit(
              StreamEvent.Entries(listOf(StreamEntry("legacy", 1, "text", "Legacy output", null)))
            )
            throw IOException("source lost")
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(legacy)
      advanceUntilIdle()
      // Explicit legacy reading remains compatible; null refs cannot prove a safe reconnect.
      assertEquals(1, streamAttempts)
      assertEquals("Legacy output", viewModel.state.value.stream.entries.single().text)
      assertFalse(viewModel.state.value.streamLive)
      assertEquals(legacy, viewModel.state.value.selectedAgent)
      assertEquals(
        ConnectionState.Error("Selected session has no verified native identity; select it again"),
        viewModel.state.value.connection,
      )
      viewModel.submitPrompt("must not transmit")
      advanceUntilIdle()
      assertTrue(viewModel.state.value.promptSends.isEmpty())
    }

  @Test
  fun legacyForegroundRecoveryRequiresExplicitReselection() =
    runTest(dispatcher) {
      val legacy = agent.copy(nativeSessionRef = null)
      var attempts = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(legacy)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            attempts++
            emit(StreamEvent.Open(sessionId, 1))
            emit(StreamEvent.AgentStatus(sessionId, "idle", null))
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      advanceUntilIdle()
      viewModel.selectAgent(legacy)
      runCurrent()
      assertEquals(1, attempts)
      assertTrue(viewModel.state.value.streamLive)
      viewModel.setForeground(false)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(1, attempts)
      assertFalse(viewModel.state.value.streamLive)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      viewModel.selectAgent(legacy)
      runCurrent()
      assertEquals(2, attempts)
      assertTrue(viewModel.state.value.streamLive)
    }

  @Test
  fun protocolAndTlsLossClearStaleActionsWithoutRetry() =
    runTest(dispatcher) {
      for (failure in
        listOf(
          com.azusachino.cappuccino.core.ProtocolException("Malformed stream"),
          javax.net.ssl.SSLException("TLS failure"),
        )) {
        var attempts = 0
        viewModel =
          createViewModel({ error("Terminal failure must not retry") }) { session ->
            flow {
              attempts++
              emit(StreamEvent.Open(session, 1))
              emit(StreamEvent.AgentStatus(session, "working", "Old detail"))
              emit(
                StreamEvent.PromptRequest(
                  session,
                  PromptCard(
                    "p-1",
                    "approval",
                    "Approve?",
                    null,
                    null,
                    null,
                    listOf(PromptOption("a", "Yes", null)),
                    0,
                  ),
                )
              )
              throw failure
            }
          }
        viewModel.setForeground(true)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        advanceUntilIdle()
        assertEquals(1, attempts)
        assertTrue(viewModel.state.value.connection is ConnectionState.Error)
        assertFalse(viewModel.state.value.streamLive)
        assertNull(viewModel.state.value.stream.pendingPrompt)
        assertNull(viewModel.state.value.stream.agentStatusDetail)
        viewModel.submitPrompt("stale callback")
        assertTrue(viewModel.state.value.promptSends.isEmpty())
        viewModel.setForeground(false)
      }
    }

  @Test
  fun actionsStayDisabledUntilAFreshAgentStatusBaseline() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      viewModel = createViewModel({}, streamFactory = { events })
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      runCurrent()
      // stream_open is transport: the machine shows Connected but actions must not enable.
      assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
      assertFalse(viewModel.state.value.streamLive)
      viewModel.submitPrompt("before baseline")
      advanceUntilIdle()
      assertTrue(viewModel.state.value.promptSends.isEmpty())
      events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
      runCurrent()
      assertTrue(viewModel.state.value.streamLive)
    }

  @Test
  fun backgroundCancelsActualSelectedStream() =
    runTest(dispatcher) {
      var cancelled = false
      viewModel =
        createViewModel({}) { session ->
          flow {
            try {
              emit(StreamEvent.Open(session, 1))
              kotlinx.coroutines.awaitCancellation()
            } finally {
              cancelled = true
            }
          }
        }
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      viewModel.setForeground(false)
      advanceUntilIdle()
      assertTrue(cancelled)
      assertEquals(ConnectionState.Disconnected, viewModel.state.value.connection)
    }

  @Test
  fun errorThenClosedTransportKeepsBridgeErrorTerminal() =
    runTest(dispatcher) {
      var attempts = 0
      viewModel =
        createViewModel({}) { session ->
          flow {
            attempts++
            emit(StreamEvent.Open(session, 1))
            emit(StreamEvent.Error("denied", "bridge rejected stream"))
          }
        }
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      assertEquals(1, attempts)
      assertEquals(
        ConnectionState.Error("denied: bridge rejected stream"),
        viewModel.state.value.connection,
      )
      assertEquals("denied: bridge rejected stream", viewModel.state.value.stream.error)
    }

  @Test
  fun controlsUseTheMachinesVerifiedCapabilityWithAFreshClient() =
    runTest(dispatcher) {
      for (advertised in listOf(true, false)) {
        val sent = mutableListOf<String>()
        // Like the app: a new client per request, whose own details are only set by verify().
        val factory: (Endpoint) -> BridgeTransport = {
          object : BridgeTransport {
            override var bridgeDetails: BridgeDetails? = null

            override suspend fun verify(expectedMachine: UUID?): UUID {
              bridgeDetails =
                BridgeDetails(
                  machine,
                  "1",
                  1,
                  mapOf("agent_control" to advertised, "mutation_binding" to true),
                )
              return machine
            }

            override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

            override fun stream(sessionId: String): Flow<StreamEvent> = flow {
              // Truthful connection: transport open, then the fresh agent_status baseline that
              // enables actions; stream_open alone is not live state.
              emit(StreamEvent.Open(sessionId, 1))
              emit(StreamEvent.AgentStatus(sessionId, "idle", null))
              kotlinx.coroutines.awaitCancellation()
            }

            override suspend fun controlAgent(
              sessionId: String,
              action: String,
              binding: MutationBinding,
            ) {
              sent += "$sessionId:$action"
            }

            override suspend fun answerQuestions(
              sessionId: String,
              promptId: String,
              answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
              binding: MutationBinding,
            ) {
              throw java.io.IOException(
                "the question is no longer at its start; answer it in the terminal"
              )
            }
          }
        }
        viewModel = ConnectedViewModel(store, factory, {}, SavedStateHandle())
        viewModel.setForeground(true)
        viewModel.selectProfile(profile.id)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        advanceUntilIdle()
        viewModel.controlAgent("interrupt")
        advanceUntilIdle()
        assertEquals(if (advertised) listOf("locator:interrupt") else emptyList(), sent)
        assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
        // A refused answer is reported on its own; the connection stays usable.
        viewModel.answerQuestions(
          "q-1",
          listOf(com.azusachino.cappuccino.core.QuestionAnswer(listOf(0), null)),
        )
        advanceUntilIdle()
        assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
        assertEquals(
          "the question is no longer at its start; answer it in the terminal",
          viewModel.state.value.actionError,
        )
        viewModel.selectAgent(null)
        assertEquals(null, viewModel.state.value.actionError)
        viewModel.setForeground(false)
      }
    }

  @Test
  fun staleCatalogCompletionCannotReplaceNewProfile() =
    runTest(dispatcher) {
      val secondMachine = UUID.randomUUID()
      val secondProfile =
        MachineProfile("p2", "Second", Endpoint.parse("https://second.example"), secondMachine)
      store.save(secondProfile)
      val releaseOld = kotlinx.coroutines.CompletableDeferred<Unit>()
      val newAgent = AgentRow(secondMachine, "new", "pane", "New", "pi", "idle", false, null)
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            if (expectedMachine == machine)
              kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                releaseOld.await()
              }
            return expectedMachine!!
          }

          override suspend fun agents(machine: UUID): List<AgentRow> =
            if (machine == secondMachine) listOf(newAgent) else listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectProfile(secondProfile.id)
      runCurrent()
      advanceUntilIdle()
      releaseOld.complete(Unit)
      advanceUntilIdle()
      assertEquals(secondProfile.id, viewModel.state.value.activeProfileId)
      assertEquals(listOf(newAgent), viewModel.state.value.agents)
    }

  @Test
  fun transientRestoreFailureKeepsTargetForManualRetry() =
    runTest(dispatcher) {
      val handle = savedTarget()
      var verifies = 0
      var streams = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifies++
            if (verifies == 1) throw IOException("temporary identity failure")
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override suspend fun conversationSnapshot(sessionId: String) =
            ConversationSnapshot(emptyList(), "canonical_log", savedId, machine)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            streams++
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(1, verifies)
      assertNull(viewModel.state.value.selectedAgent)
      assertEquals(profile.id, handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
      assertEquals(machine.toString(), handle.get<String>(ConnectedViewModel.SELECTED_MACHINE))
      assertEquals(agent.sessionId, handle.get<String>(ConnectedViewModel.SELECTED_LOCATOR))
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      viewModel.retry()
      advanceUntilIdle()
      assertEquals(3, verifies)
      assertEquals(agent, viewModel.state.value.selectedAgent)
      assertEquals(1, streams)
      assertEquals(profile.id, handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
    }

  @Test
  fun transientRestoreFailureRecoversOnLaterForeground() =
    runTest(dispatcher) {
      val handle = savedTarget()
      var verifies = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifies++
            if (verifies == 1) throw IOException("temporary identity failure")
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      assertEquals(profile.id, handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
      viewModel.setForeground(false)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(3, verifies)
      assertEquals(agent, viewModel.state.value.selectedAgent)
      assertEquals(agent.sessionId, handle.get<String>(ConnectedViewModel.SELECTED_LOCATOR))
    }

  @Test
  fun staleSuccessfulRestoreCannotReplaceNewerSelection() =
    runTest(dispatcher) {
      val secondMachine = UUID.randomUUID()
      val secondProfile =
        MachineProfile("p2", "Second", Endpoint.parse("https://second.example"), secondMachine)
      store.save(secondProfile)
      val secondAgent = AgentRow(secondMachine, "new", "pane", "New", "pi", "idle", false, null)
      val releaseRestore = kotlinx.coroutines.CompletableDeferred<Unit>()
      val restoreEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
      var oldSubscriptions = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = expectedMachine!!

          override suspend fun agents(machine: UUID): List<AgentRow> {
            if (machine == this@ConnectedViewModelTest.machine) {
              restoreEntered.complete(Unit)
              kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                releaseRestore.await()
              }
            }
            return if (machine == secondMachine) listOf(secondAgent) else listOf(agent)
          }

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            if (sessionId == agent.sessionId) oldSubscriptions++
            kotlinx.coroutines.awaitCancellation()
          }
        }
      val handle = savedTarget()
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      runCurrent()
      restoreEntered.await()
      viewModel.selectProfile(secondProfile.id)
      runCurrent()
      viewModel.selectAgent(secondAgent)
      runCurrent()
      assertEquals(secondAgent, viewModel.state.value.selectedAgent)
      assertEquals(secondProfile.id, viewModel.state.value.activeProfileId)
      val newerStream = viewModel.state.value.stream
      releaseRestore.complete(Unit)
      advanceUntilIdle()
      assertEquals(secondAgent, viewModel.state.value.selectedAgent)
      assertEquals(secondProfile.id, viewModel.state.value.activeProfileId)
      assertEquals(newerStream, viewModel.state.value.stream)
      assertEquals(secondProfile.id, handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
      assertEquals(
        secondMachine.toString(),
        handle.get<String>(ConnectedViewModel.SELECTED_MACHINE),
      )
      assertEquals(secondAgent.sessionId, handle.get<String>(ConnectedViewModel.SELECTED_LOCATOR))
      assertEquals(0, oldSubscriptions)
      viewModel.setForeground(false)
      releaseRestore.complete(Unit)
    }

  @Test
  fun backgroundDuringRestoreRearmsSelectionForNextForeground() =
    runTest(dispatcher) {
      val handle =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      val catalogGate = kotlinx.coroutines.CompletableDeferred<Unit>()
      var verifies = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifies++
            if (verifies == 1) catalogGate.await()
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      runCurrent()
      viewModel.setForeground(false)
      catalogGate.complete(Unit)
      advanceUntilIdle()
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertTrue("interrupted restoration was not retried", verifies >= 2)
      assertEquals(agent, viewModel.state.value.selectedAgent)
    }

  @Test
  fun selectingSameProfileClearsPersistedTargetForFutureViewModel() =
    runTest(dispatcher) {
      val handle =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      advanceUntilIdle()
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      assertNull(handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
      val recreated = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel = recreated
      recreated.setForeground(true)
      advanceUntilIdle()
      assertNull(recreated.state.value.selectedAgent)
    }

  @Test
  fun savedTargetIsClearedWhenProfileOrCatalogRowIsMissing() =
    runTest(dispatcher) {
      val missing =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to "gone",
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      var verifies = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifies++
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = emptyList()

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            error("unexpected stream")
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, missing)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(0, verifies)
      assertNull(missing.get<String>(ConnectedViewModel.SELECTED_PROFILE))

      val mismatched =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to UUID.randomUUID().toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      viewModel = ConnectedViewModel(store, { transport }, {}, mismatched)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertNull(mismatched.get<String>(ConnectedViewModel.SELECTED_PROFILE))
      assertEquals(0, verifies)

      val vanished =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      viewModel = ConnectedViewModel(store, { transport }, {}, vanished)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      assertNull(viewModel.state.value.selectedAgent)
      assertNull(vanished.get<String>(ConnectedViewModel.SELECTED_PROFILE))
    }

  @Test
  fun failedOldRestoreCannotClearNewProfileSelection() =
    runTest(dispatcher) {
      val secondMachine = UUID.randomUUID()
      val secondProfile =
        MachineProfile("p2", "Second", Endpoint.parse("https://second.example"), secondMachine)
      store.save(secondProfile)
      val releaseOld = kotlinx.coroutines.CompletableDeferred<Unit>()
      val secondAgent = AgentRow(secondMachine, "new", "pane", "New", "pi", "idle", false, null)
      val handle =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            if (expectedMachine == machine)
              kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                releaseOld.await()
                throw IOException("old restore failed late")
              }
            return secondMachine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(secondAgent)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      runCurrent()
      viewModel.selectProfile(secondProfile.id)
      runCurrent()
      viewModel.selectAgent(secondAgent)
      releaseOld.complete(Unit)
      advanceUntilIdle()
      assertEquals(secondAgent, viewModel.state.value.selectedAgent)
      assertEquals(secondProfile.id, handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
    }

  @Test
  fun savedTargetReconstructsTwiceOnlyAfterForegroundAndRevalidates() =
    runTest(dispatcher) {
      val handle =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
            ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
          )
        )
      var verifications = 0
      var catalogs = 0
      var streams = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifications++
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> {
            catalogs++
            return listOf(agent)
          }

          override suspend fun conversationSnapshot(sessionId: String) =
            ConversationSnapshot(emptyList(), "canonical_log", savedId, machine)

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            streams++
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      assertEquals(0, verifications)
      viewModel.setForeground(true)
      advanceUntilIdle()
      // Restore verifies once; each stream attach re-verifies machine and catalog before opening.
      assertEquals(2, verifications)
      assertEquals(2, catalogs)
      assertEquals(agent, viewModel.state.value.selectedAgent)
      viewModel.setForeground(false)
      val second = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel = second
      assertEquals(2, verifications)
      second.setForeground(true)
      advanceUntilIdle()
      assertEquals(4, verifications)
      assertEquals(4, catalogs)
      assertEquals(agent, second.state.value.selectedAgent)
      assertTrue(streams >= 2)
    }

  @Test
  fun oldSavedSelectionWithoutNativeRefRequiresExplicitReselection() =
    runTest(dispatcher) {
      val handle =
        SavedStateHandle(
          mapOf(
            ConnectedViewModel.SELECTED_PROFILE to profile.id,
            ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
            ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
          )
        )
      var verifies = 0
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            verifies++
            return machine
          }

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> =
            error("a legacy saved selection must not attach without reselection")
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(0, verifies)
      assertNull(viewModel.state.value.selectedAgent)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      assertNull(handle.get<String>(ConnectedViewModel.SELECTED_PROFILE))
    }

  @Test
  fun restoreRejectsReusedLocatorAfterSessionReplacement() =
    runTest(dispatcher) {
      val replacement = agent.copy(nativeSessionRef = "pi:bbbb-replacement")
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(replacement)

          override fun stream(sessionId: String): Flow<StreamEvent> =
            error("a replaced session must not attach by locator reuse")
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, savedTarget())
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
    }

  @Test
  fun catalogRefreshDropsSelectionAndDraftWhenLocatorIsReused() =
    runTest(dispatcher) {
      val replacement = agent.copy(nativeSessionRef = "pi:cccc-replacement")
      var catalog = listOf(agent)
      val transport =
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = catalog

          override fun stream(sessionId: String): Flow<StreamEvent> =
            kotlinx.coroutines.flow.emptyFlow()
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      advanceUntilIdle()
      assertEquals(agent, viewModel.state.value.selectedAgent)
      viewModel.setDraft(draftKey(agent), "old draft")
      assertEquals(ConnectionState.Connected, viewModel.state.value.connection)

      catalog = listOf(replacement)
      viewModel.refreshProfile(profile.id)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      assertEquals(
        ConnectionState.Error("Selected session was replaced; select it again"),
        viewModel.state.value.connection,
      )
      assertTrue(viewModel.state.value.stream.entries.isEmpty())
      assertTrue(viewModel.state.value.conversationTurns.isEmpty())
      assertNull(viewModel.state.value.drafts[draftKey(replacement)])
    }

  @Test
  fun lateSendFailureDoesNotLeakIntoReplacementConversation() =
    runTest(dispatcher) {
      val response = CompletableDeferred<Unit>()
      val transport = sendTransport { _, _, _ -> response.await() }
      val replacement = agent.copy(nativeSessionRef = "pi:replacement")
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent()
      viewModel.setDraft(draftKey(agent), "original draft")
      viewModel.submitPrompt("original draft")
      runCurrent()
      viewModel.selectAgent(replacement)
      viewModel.setDraft(draftKey(replacement), "replacement draft")
      response.completeExceptionally(IOException("old send response lost"))
      advanceUntilIdle()
      assertNull(viewModel.state.value.actionError)
      assertEquals("original draft", viewModel.state.value.drafts[draftKey(agent)])
      assertEquals("replacement draft", viewModel.state.value.drafts[draftKey(replacement)])
      assertNull(viewModel.state.value.promptSends[sendKey(replacement)])
      assertTrue(
        viewModel.state.value.promptSends[sendKey(agent)]!!.outcome!!.startsWith("Delivery unknown")
      )
    }

  private fun savedTarget() =
    SavedStateHandle(
      mapOf(
        ConnectedViewModel.SELECTED_PROFILE to profile.id,
        ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
        ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
        ConnectedViewModel.SELECTED_NATIVE_REF to nativeRef,
      )
    )

  @Test
  fun machineRenamePersistsWithoutChangingIdentityOrConnecting() =
    runTest(dispatcher) {
      viewModel =
        ConnectedViewModel(store, { error("Renaming must not connect") }, {}, SavedStateHandle())
      viewModel.renameProfile(profile.id, "  My Mac mini  ")
      val renamed = viewModel.state.value.profiles.single()
      assertEquals("My Mac mini", renamed.label)
      assertEquals(profile.id, renamed.id)
      assertEquals(profile.machineId, renamed.machineId)
      assertEquals(profile.endpoint, renamed.endpoint)
      assertEquals(renamed, store.read().single())
      viewModel.renameProfile(profile.id, "   ")
      assertEquals(renamed, store.read().single())
    }

  @Test
  fun bridgeNameMigratesUrlDefaultsButNeverOverridesAnOwnerName() =
    runTest(dispatcher) {
      store.save(profile.copy(label = profile.endpoint.base.host))
      viewModel =
        createViewModel(
          pause = {},
          reportedMachineName = "Mac mini",
          streamFactory = { flow { kotlinx.coroutines.awaitCancellation() } },
        )
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      assertEquals("Mac mini", viewModel.state.value.profiles.single().label)
      viewModel.renameProfile(profile.id, "Owner name")
      viewModel.refresh()
      advanceUntilIdle()
      assertEquals("Owner name", viewModel.state.value.profiles.single().label)
      assertEquals(profile.machineId, store.read().single().machineId)
    }

  @Test
  fun recoveryWaitsForCanonicalHistoryAndDiscardsCancelledPreviousRefresh() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      val obsolete = CompletableDeferred<Unit>()
      val recovered = CompletableDeferred<Unit>()
      var reads = 0
      var attachments = 0
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            val read = ++reads
            if (read == 1) withContext(NonCancellable) { obsolete.await() }
            if (read == 2) recovered.await()
            listOf(
              ConversationTurn(
                "history-$read",
                "assistant",
                null,
                if (read == 1) "Obsolete" else "Recovered",
                emptyList(),
              )
            )
          },
          streamFactory = {
            attachments++
            events
          },
        )
      try {
        viewModel.setForeground(true)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        runCurrent()
        events.emit(StreamEvent.Open(agent.sessionId, 1))
        events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
        events.emit(StreamEvent.Error("upstream_unavailable", "Source lost"))
        runCurrent()
        assertEquals(2, reads)
        assertEquals(1, attachments)
        assertFalse(viewModel.state.value.streamLive)
        viewModel.submitPrompt("before history")
        assertTrue(viewModel.state.value.promptSends.isEmpty())
        obsolete.complete(Unit)
        runCurrent()
        assertTrue(viewModel.state.value.conversationTurns.isEmpty())
        assertEquals(1, attachments)
        assertFalse(viewModel.state.value.streamLive)
        recovered.complete(Unit)
        runCurrent()
        assertEquals(2, attachments)
        assertEquals("Recovered", viewModel.state.value.conversationTurns.single().text)
        assertFalse(viewModel.state.value.streamLive)
        events.emit(StreamEvent.Open(agent.sessionId, 2))
        events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
        runCurrent()
        assertTrue(viewModel.state.value.streamLive)
        assertEquals("Recovered", viewModel.state.value.conversationTurns.single().text)
        viewModel.submitPrompt("once ready")
        runCurrent()
        assertEquals(1, viewModel.state.value.promptSends.size)
      } finally {
        obsolete.complete(Unit)
        recovered.complete(Unit)
      }
    }

  @Test
  fun explicitRetryCanRecoverHistoryWithoutReplayingInput() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      var available = true
      var attachments = 0
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            if (!available) throw IOException("History offline")
            emptyList()
          },
          streamFactory = {
            attachments++
            events
          },
        )
      viewModel.setForeground(true)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
      available = false
      events.emit(StreamEvent.Error("upstream_unavailable", "Source lost"))
      advanceUntilIdle()
      assertEquals(1, attachments)
      assertFalse(viewModel.state.value.streamLive)
      assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      viewModel.submitPrompt("never replay")
      available = true
      viewModel.retry()
      runCurrent()
      assertEquals(2, attachments)
      assertFalse(viewModel.state.value.streamLive)
      events.emit(StreamEvent.Open(agent.sessionId, 2))
      events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
      runCurrent()
      assertTrue(viewModel.state.value.streamLive)
      assertTrue(viewModel.state.value.promptSends.isEmpty())
    }

  @Test
  fun unavailableOrUnverifiedCanonicalHistoryNeverUnlocksRecovery() =
    runTest(dispatcher) {
      val cases: List<Triple<String, String?, UUID?>> =
        listOf(
          Triple("terminal_scrollback", savedId, machine),
          Triple("canonical_log", null, machine),
          Triple("canonical_log", savedId, UUID.randomUUID()),
        )
      for ((source, identity, reportedMachine) in cases) {
        val events = MutableSharedFlow<StreamEvent>()
        var attachments = 0
        viewModel =
          createViewModel(
            pause = {},
            conversationSource = source,
            conversationIdentity = identity,
            conversationMachine = reportedMachine,
            streamFactory = {
              attachments++
              events
            },
          )
        viewModel.setForeground(true)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        runCurrent()
        events.emit(StreamEvent.Open(agent.sessionId, 1))
        events.emit(StreamEvent.AgentStatus(agent.sessionId, "idle", null))
        events.emit(StreamEvent.Error("upstream_unavailable", "Source lost"))
        advanceUntilIdle()
        assertEquals(1, attachments)
        assertFalse(viewModel.state.value.streamLive)
        assertTrue(viewModel.state.value.connection is ConnectionState.Error)
        viewModel.submitPrompt("must remain blocked")
        assertTrue(viewModel.state.value.promptSends.isEmpty())
        viewModel.setForeground(false)
      }
    }

  @Test
  fun canonicalRefreshBurstCoalescesWithoutHidingSourceLossOrFinalUpdate() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      val firstRead = CompletableDeferred<Unit>()
      val retry = CompletableDeferred<Unit>()
      var reads = 0
      viewModel =
        createViewModel(
          pause = { retry.await() },
          conversationFactory = {
            reads++
            if (reads == 1) firstRead.await()
            listOf(
              ConversationTurn("settled-$reads", "assistant", null, "Settled $reads", emptyList())
            )
          },
          streamFactory = { events },
        )
      viewModel.setForeground(true)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      assertEquals(1, reads)
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      events.emit(StreamEvent.AgentStatus(agent.sessionId, "working", null))
      repeat(20) { viewModel.loadConversation() }
      runCurrent()
      assertEquals(1, reads)
      events.emit(StreamEvent.Error("upstream_unavailable", "Source unavailable"))
      runCurrent()
      assertEquals(ConnectionState.Recovering, viewModel.state.value.connection)
      assertFalse(viewModel.state.value.streamLive)
      firstRead.complete(Unit)
      advanceUntilIdle()
      assertEquals(2, reads)
      assertEquals("Settled 2", viewModel.state.value.conversationTurns.single().text)
      assertEquals(ConnectionState.Recovering, viewModel.state.value.connection)
      assertFalse(viewModel.state.value.streamLive)
      assertTrue(viewModel.state.value.stream.error!!.contains("upstream_unavailable"))
    }

  @Test
  fun streamUpdatesRefreshVisibleConversation() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      var text = "Initial reply"
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            listOf(ConversationTurn("turn", "assistant", null, text, emptyList()))
          },
          streamFactory = { events },
        )
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      runCurrent()
      assertEquals("Initial reply", viewModel.state.value.conversationTurns.single().text)
      text = "Live reply"
      events.emit(StreamEvent.Entries(listOf(StreamEntry("entry", 1, "text", "Live reply", null))))
      advanceUntilIdle()
      assertEquals("Live reply", viewModel.state.value.conversationTurns.single().text)
    }

  @Test
  fun terminalFallbackIsNotRebuiltAsMarkdownHistoryOnExternalInput() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            listOf(
              ConversationTurn("unstable", "assistant", null, "Terminal snapshot", emptyList())
            )
          },
          conversationSource = "scrollback",
          streamFactory = { events },
        )
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      events.emit(
        StreamEvent.Entries(listOf(StreamEntry("external", 1, "text", "External input", null)))
      )
      advanceUntilIdle()
      assertTrue(viewModel.state.value.conversationTurns.isEmpty())
      assertEquals("scrollback", viewModel.state.value.conversationSource)
      assertEquals("External input", viewModel.state.value.stream.entries.single().text)
      viewModel.selectAgent(null)
      assertNull(viewModel.state.value.conversationSource)
    }

  @Test
  fun updatesDuringAnInflightReadCoalesceIntoOneFreshFollowup() =
    runTest(dispatcher) {
      val events = MutableSharedFlow<StreamEvent>()
      val firstRead = CompletableDeferred<List<ConversationTurn>>()
      var reads = 0
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            if (++reads == 1) firstRead.await()
            else listOf(ConversationTurn("latest", "assistant", null, "Latest reply", emptyList()))
          },
          streamFactory = { events },
        )
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      events.emit(StreamEvent.Open(agent.sessionId, 1))
      repeat(3) { events.emit(StreamEvent.AgentStatus(agent.sessionId, "working", "update $it")) }
      runCurrent()
      assertEquals(1, reads)
      firstRead.complete(
        listOf(ConversationTurn("initial", "assistant", null, "Earlier reply", emptyList()))
      )
      advanceUntilIdle()
      assertEquals(2, reads)
      assertEquals("Latest reply", viewModel.state.value.conversationTurns.single().text)
    }

  @Test
  fun lateConversationCannotOverwriteReselectedSameLocator() =
    runTest(dispatcher) {
      val late = CompletableDeferred<List<ConversationTurn>>()
      var reads = 0
      viewModel =
        createViewModel(
          pause = {},
          conversationFactory = {
            if (++reads == 1) withContext(NonCancellable) { late.await() }
            else listOf(ConversationTurn("new", "assistant", null, "New reply", emptyList()))
          },
          streamFactory = { flow { kotlinx.coroutines.awaitCancellation() } },
        )
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      advanceUntilIdle()
      viewModel.selectAgent(agent)
      runCurrent()
      viewModel.selectAgent(null)
      viewModel.selectAgent(agent)
      runCurrent()
      assertEquals("New reply", viewModel.state.value.conversationTurns.single().text)
      late.complete(listOf(ConversationTurn("old", "assistant", null, "Stale reply", emptyList())))
      advanceUntilIdle()
      assertEquals("New reply", viewModel.state.value.conversationTurns.single().text)
    }

  @Test
  fun savedHistorySurvivesModelReconstructionWithoutBecomingLive() =
    runTest(dispatcher) {
      val directory = offlineFolder.newFolder("history")
      val cache = OfflineConversations(directory)
      val turn = ConversationTurn("saved", "assistant", null, "Saved prose", emptyList())
      cache.save(
        machine,
        agent.copy(status = "working", working = true),
        com.azusachino.cappuccino.core.boundedConversation(listOf(turn)),
        savedId,
      )
      viewModel = offlineViewModel(cache)
      advanceUntilIdle()
      assertEquals(1, viewModel.state.value.savedConversations.size)
      viewModel.setForeground(false)
      viewModel = offlineViewModel(OfflineConversations(directory))
      viewModel.openSavedConversation(machine, savedId)
      advanceUntilIdle()
      val saved = viewModel.state.value
      assertEquals(listOf(turn), saved.conversationTurns)
      assertEquals("offline_snapshot", saved.conversationSource)
      assertEquals("unknown", saved.selectedAgent?.status)
      assertEquals(false, saved.selectedAgent?.working)
      assertNotNull(saved.offlineSavedAt)
      assertEquals(ConnectionState.Disconnected, saved.connection)
      assertNull(saved.stream.pendingPrompt)
      assertTrue(requireNotNull(saved.offlineBytes) > 0)
    }

  @Test
  fun savedReaderCannotSendAnswerRetryOrReloadFromRemote() =
    runTest(dispatcher) {
      val cache = OfflineConversations(offlineFolder.newFolder("read-only"))
      cache.save(
        machine,
        agent,
        com.azusachino.cappuccino.core.boundedConversation(emptyList()),
        savedId,
      )
      viewModel = offlineViewModel(cache)
      viewModel.openSavedConversation(machine, savedId)
      advanceUntilIdle()
      val before = viewModel.state.value
      viewModel.submitPrompt("Never send this")
      viewModel.answerPrompt("not-live", 0, null, null)
      viewModel.retry()
      viewModel.loadConversation()
      advanceUntilIdle()
      assertEquals(before, viewModel.state.value)
    }

  @Test
  fun savedReaderRejectsForeignMachineAndCancelledSelection() =
    runTest(dispatcher) {
      val foreign = profile.copy(id = "other", machineId = UUID.randomUUID())
      store.save(foreign)
      val cache = OfflineConversations(offlineFolder.newFolder("ownership"))
      cache.save(
        machine,
        agent,
        com.azusachino.cappuccino.core.boundedConversation(emptyList()),
        savedId,
      )
      viewModel = offlineViewModel(cache)
      advanceUntilIdle()
      viewModel.openSavedConversation(foreign.machineId, savedId)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      viewModel.openSavedConversation(machine, savedId)
      viewModel.selectAgent(null)
      advanceUntilIdle()
      assertNull(viewModel.state.value.selectedAgent)
      assertNull(viewModel.state.value.offlineSavedAt)
      assertNull(viewModel.state.value.conversationSource)
    }

  @Test
  fun discoveryCannotReplaceSavedReaderWithLiveAgentState() =
    runTest(dispatcher) {
      val cache = OfflineConversations(offlineFolder.newFolder("discovery"))
      val turn = ConversationTurn("saved", "assistant", null, "Offline only", emptyList())
      cache.save(
        machine,
        agent,
        com.azusachino.cappuccino.core.boundedConversation(listOf(turn)),
        savedId,
      )
      viewModel =
        createViewModel({}, offlineHistory = cache) {
          throw AssertionError("Saved history must not open a live stream")
        }
      viewModel.openSavedConversation(machine, savedId)
      advanceUntilIdle()
      val savedAt = viewModel.state.value.offlineSavedAt
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals("unknown", viewModel.state.value.selectedAgent?.status)
      assertEquals(listOf(turn), viewModel.state.value.conversationTurns)
      assertEquals(savedAt, viewModel.state.value.offlineSavedAt)
      viewModel.setForeground(false)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals("offline_snapshot", viewModel.state.value.conversationSource)
      assertEquals(savedAt, viewModel.state.value.offlineSavedAt)
    }

  @Test
  fun onlyCanonicalIdentityCanProduceOfflineArchiveWhileLegacyContentStaysReadable() =
    runTest(dispatcher) {
      val turn = ConversationTurn("owned", "assistant", null, "Live canonical prose", emptyList())
      for (identity in listOf(null, savedId)) {
        val cache =
          OfflineConversations(
            offlineFolder.newFolder(if (identity == null) "legacy" else "canonical")
          )
        viewModel =
          createViewModel(
            {},
            conversationFactory = { listOf(turn) },
            offlineHistory = cache,
            conversationIdentity = identity,
          ) { session ->
            flow { emit(StreamEvent.Open(session, 1)) }
          }
        viewModel.setForeground(true)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        advanceUntilIdle()
        assertEquals(listOf(turn), viewModel.state.value.conversationTurns)
        if (identity == null) {
          assertEquals(0, cache.list(setOf(machine)).size)
          assertTrue(
            requireNotNull(viewModel.state.value.offlineProblem)
              .contains("canonical conversation ID")
          )
        } else {
          assertEquals(listOf(turn), cache.read(machine, identity)?.history?.turns)
          assertEquals(identity, viewModel.state.value.conversationId)
        }
        viewModel.setForeground(false)
      }
    }

  @Test
  fun verificationCannotAuthorizeMissingOrForeignSnapshotMachine() =
    runTest(dispatcher) {
      val turn = ConversationTurn("foreign", "assistant", null, "Must not archive", emptyList())
      for (reported in listOf(null, UUID.randomUUID())) {
        val cache =
          OfflineConversations(
            offlineFolder.newFolder(if (reported == null) "missing-machine" else "foreign-machine")
          )
        viewModel =
          createViewModel(
            {},
            conversationFactory = { listOf(turn) },
            offlineHistory = cache,
            conversationIdentity = savedId,
            conversationMachine = reported,
          ) { session ->
            flow { emit(StreamEvent.Open(session, 1)) }
          }
        viewModel.setForeground(true)
        advanceUntilIdle()
        viewModel.selectAgent(agent)
        advanceUntilIdle()
        assertTrue(cache.list(setOf(machine)).isEmpty())
        assertTrue(viewModel.state.value.conversationTurns.isEmpty())
        assertNull(viewModel.state.value.conversationId)
        viewModel.setForeground(false)
      }
    }

  @Test
  fun successfulSendKeepsDraftUntilTransportReturns() =
    runTest(dispatcher) {
      val response = CompletableDeferred<Unit>()
      val transport = sendTransport { _, _, _ -> response.await() }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent() // Establish the fresh status baseline before exercising sending.
      val key = draftKey(agent)
      viewModel.setDraft(key, "my draft text")

      viewModel.submitPrompt("my draft text")
      assertEquals("my draft text", viewModel.state.value.drafts[key])
      assertEquals("my draft text", viewModel.state.value.promptSends[sendKey(agent)]?.text)
      viewModel.setDraft(key, "newer edit")
      response.complete(Unit)
      advanceUntilIdle()
      assertEquals("newer edit", viewModel.state.value.drafts[key])
      assertTrue(
        viewModel.state.value.promptSends[sendKey(agent)]!!.outcome!!.contains("not confirmed")
      )
    }

  @Test
  fun pendingSendRejectsRepeatedTapAndRetainsEditedTextOnFailure() =
    runTest(dispatcher) {
      val response = CompletableDeferred<Unit>()
      var attempts = 0
      val transport = sendTransport { _, _, _ ->
        attempts++
        response.await()
      }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent()
      val draftKey = draftKey(agent)
      viewModel.setDraft(draftKey, "first")
      viewModel.submitPrompt("first")
      runCurrent()
      viewModel.submitPrompt("duplicate")
      viewModel.setDraft(draftKey, "newer edit")
      assertEquals(1, attempts)
      response.completeExceptionally(BridgeHttpRejectionException("bad_request"))
      advanceUntilIdle()
      assertEquals("newer edit", viewModel.state.value.drafts[draftKey])
      assertTrue(
        viewModel.state.value.promptSends[sendKey(agent)]!!.outcome!!.startsWith("Rejected")
      )
    }

  @Test
  fun ambiguousFailureRestoresUnchangedDraftAndDoesNotRetry() =
    runTest(dispatcher) {
      var attempts = 0
      val transport = sendTransport { _, _, _ ->
        attempts++
        throw IOException("response lost")
      }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent()
      val draftKey = draftKey(agent)
      viewModel.setDraft(draftKey, "prompt")
      viewModel.submitPrompt("prompt")
      advanceUntilIdle()
      assertEquals(1, attempts)
      assertEquals("prompt", viewModel.state.value.drafts[draftKey])
      assertTrue(
        viewModel.state.value.promptSends[sendKey(agent)]!!.outcome!!.startsWith("Delivery unknown")
      )
      // No retry is scheduled after an ambiguous response.
      assertEquals(1, attempts)
    }

  @Test
  fun sendSettlesAfterNavigationWithoutLeakingOutcomeToOtherAgent() =
    runTest(dispatcher) {
      val response = CompletableDeferred<Unit>()
      var attempts = 0
      val other = agent.copy(sessionId = "other", paneId = "other-pane")
      val transport =
        sendTransport(listOf(agent, other)) { _, _, _ -> if (attempts++ == 0) response.await() }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent()
      viewModel.submitPrompt("pending")
      runCurrent()
      viewModel.selectAgent(other)
      response.completeExceptionally(IOException("lost response"))
      advanceUntilIdle()
      assertNull(viewModel.state.value.promptSends[sendKey(other)])
      assertEquals("pending", viewModel.state.value.drafts[draftKey(agent)])
      viewModel.setDraft(draftKey(other), "new")
      viewModel.submitPrompt("new")
      advanceUntilIdle()
      assertTrue(
        viewModel.state.value.promptSends[sendKey(other)]!!.outcome!!.startsWith("Sent to bridge")
      )
    }

  @Test
  fun sendSettlesAfterBackgroundEpochChange() =
    runTest(dispatcher) {
      val response = CompletableDeferred<Unit>()
      val transport = sendTransport { _, _, _ -> response.await() }
      viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
      viewModel.setForeground(true)
      viewModel.selectProfile(profile.id)
      viewModel.selectAgent(agent)
      runCurrent()
      viewModel.submitPrompt("pending")
      runCurrent()
      viewModel.setForeground(false)
      response.completeExceptionally(IOException("lost response"))
      advanceUntilIdle()
      assertTrue(
        viewModel.state.value.promptSends[sendKey(agent)]!!.outcome!!.startsWith("Delivery unknown")
      )
    }

  private fun mutationActions(): List<(ConnectedViewModel) -> Unit> =
    listOf(
      { it.submitPrompt("original draft") },
      { it.controlAgent("interrupt") },
      { it.answerPrompt("p-1", 0, "reject", "select_option") },
      {
        it.answerQuestions(
          "p-1",
          listOf(com.azusachino.cappuccino.core.QuestionAnswer(listOf(0), null)),
        )
      },
    )

  @Test
  fun missingCapabilityOrNativeBindingKeepsAllDirectCallbacksReadOnly() =
    runTest(dispatcher) {
      for ((capability, selected) in
        listOf(
          null to agent,
          false to agent,
          true to agent.copy(nativeSessionRef = null),
          true to agent.copy(paneId = null),
        )) {
        var attempts = 0
        val transport =
          sendTransport(catalog = listOf(selected), bindingCapability = capability) { _, _, _ ->
            attempts++
          }
        viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
        try {
          viewModel.setForeground(true)
          viewModel.selectProfile(profile.id)
          advanceUntilIdle()
          viewModel.selectAgent(selected)
          advanceUntilIdle()
          assertTrue(viewModel.state.value.streamLive)
          assertFalse(viewModel.state.value.canMutate)
          assertFalse(viewModel.acceptsMutation(profile.id, selected))
          val before = viewModel.state.value
          mutationActions().forEach { it(viewModel) }
          advanceUntilIdle()
          assertEquals(0, attempts)
          assertEquals(before, viewModel.state.value)
        } finally {
          viewModel.setForeground(false)
        }
      }
    }

  @Test
  fun staleAndPartialUnknownInvalidateAllActionKindsWithoutReplay() =
    runTest(dispatcher) {
      val pending =
        PromptCard(
          "p-1",
          "tool_approval",
          "Synthetic",
          null,
          null,
          null,
          listOf(PromptOption("reject", "Reject", null)),
          0,
        )
      for (failure in
        listOf(
          BridgeStaleSessionException("Reselect session"),
          BridgeMutationOutcomeUnknownException("Inspect current state"),
        )) {
        for (action in mutationActions()) {
          val bindings = mutableListOf<MutationBinding>()
          var attempts = 0
          val transport =
            sendTransport(bindings = bindings, pending = pending) { _, _, _ ->
              attempts++
              throw failure
            }
          viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
          try {
            viewModel.setForeground(true)
            viewModel.selectProfile(profile.id)
            advanceUntilIdle()
            viewModel.selectAgent(agent)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canMutate)
            assertNotNull(viewModel.state.value.stream.pendingPrompt)
            viewModel.setDraft(draftKey(agent), "original draft")
            action(viewModel)
            advanceUntilIdle()
            assertEquals(1, attempts)
            assertEquals(machine, bindings.single().machineId)
            assertEquals(agent.paneId, bindings.single().paneId)
            assertEquals(nativeRef, bindings.single().nativeSessionRef)
            assertFalse(viewModel.state.value.canMutate)
            assertFalse(viewModel.state.value.streamLive)
            assertNull(viewModel.state.value.stream.pendingPrompt)
            assertNull(viewModel.state.value.stream.agentStatus)
            assertTrue(viewModel.state.value.connection is ConnectionState.Error)
            assertEquals("original draft", viewModel.state.value.drafts[draftKey(agent)])
            mutationActions().forEach { it(viewModel) }
            advanceUntilIdle()
            assertEquals("no writes or automatic replay after source invalidation", 1, attempts)
          } finally {
            viewModel.setForeground(false)
          }
        }
      }
    }

  @Test
  fun lateTypedMutationErrorCannotInvalidateAnotherNativeSelection() =
    runTest(dispatcher) {
      val other =
        agent.copy(sessionId = "other", paneId = "other-pane", nativeSessionRef = "other-native")
      for (failure in
        listOf(
          BridgeStaleSessionException("old stale"),
          BridgeMutationOutcomeUnknownException("old unknown"),
        )) {
        for (action in mutationActions()) {
          val response = CompletableDeferred<Unit>()
          val bindings = mutableListOf<MutationBinding>()
          val transport =
            sendTransport(catalog = listOf(agent, other), bindings = bindings) { _, _, _ ->
              response.await()
            }
          viewModel = ConnectedViewModel(store, { transport }, {}, SavedStateHandle())
          try {
            viewModel.setForeground(true)
            viewModel.selectProfile(profile.id)
            advanceUntilIdle()
            viewModel.selectAgent(agent)
            advanceUntilIdle()
            action(viewModel)
            runCurrent()
            viewModel.selectAgent(other)
            advanceUntilIdle()
            assertTrue(viewModel.state.value.canMutate)
            response.completeExceptionally(failure)
            advanceUntilIdle()
            assertEquals(other, viewModel.state.value.selectedAgent)
            assertTrue(viewModel.state.value.canMutate)
            assertFalse(viewModel.acceptsMutation(profile.id, agent))
            assertTrue(viewModel.acceptsMutation(profile.id, other))
            assertFalse(viewModel.acceptsMutation("wrong-profile", other))
            assertNull(viewModel.state.value.actionError)
            assertEquals(nativeRef, bindings.single().nativeSessionRef)
            assertNull(viewModel.state.value.promptSends[sendKey(other)])
          } finally {
            viewModel.setForeground(false)
          }
        }
      }
    }

  private fun draftKey(agent: AgentRow) = agentDraftKey(agent)

  private fun sendKey(agent: AgentRow) = agentSendKey(profile.id, agent)

  private fun sendTransport(
    catalog: List<AgentRow> = listOf(agent),
    bindingCapability: Boolean? = true,
    bindings: MutableList<MutationBinding>? = null,
    pending: PromptCard? = null,
    send: suspend (String, String, Boolean) -> Unit,
  ) =
    object : BridgeTransport {
      override suspend fun verify(expectedMachine: UUID?): UUID = machine

      override suspend fun agents(machine: UUID): List<AgentRow> = catalog

      override fun stream(sessionId: String): Flow<StreamEvent> = flow {
        // Truthful synthetic connection: transport open plus the fresh agent_status that makes
        // actions live; Open alone is transport, not live state.
        emit(StreamEvent.Open(sessionId, 1))
        emit(StreamEvent.AgentStatus(sessionId, "idle", null))
        pending?.let { emit(StreamEvent.PromptRequest(sessionId, it)) }
        kotlinx.coroutines.awaitCancellation()
      }

      override val bridgeDetails =
        BridgeDetails(
          machine,
          "1",
          1,
          mapOf("agent_control" to true) +
            (bindingCapability?.let { mapOf("mutation_binding" to it) } ?: emptyMap()),
        )

      private suspend fun dispatch(
        session: String,
        text: String,
        confirmed: Boolean,
        binding: MutationBinding,
      ) {
        bindings?.add(binding)
        send(session, text, confirmed)
      }

      override suspend fun submitPrompt(
        sessionId: String,
        text: String,
        confirmed: Boolean,
        binding: MutationBinding,
      ) = dispatch(sessionId, text, confirmed, binding)

      override suspend fun controlAgent(
        sessionId: String,
        action: String,
        binding: MutationBinding,
      ) = dispatch(sessionId, "control:$action", false, binding)

      override suspend fun answerPrompt(
        sessionId: String,
        promptId: String,
        optionIndex: Int?,
        optionId: String?,
        action: String?,
        binding: MutationBinding,
      ) = dispatch(sessionId, "answer:$promptId", false, binding)

      override suspend fun answerQuestions(
        sessionId: String,
        promptId: String,
        answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
        binding: MutationBinding,
      ) = dispatch(sessionId, "question:$promptId", false, binding)
    }

  private fun offlineViewModel(cache: OfflineConversations) =
    ConnectedViewModel(
      store,
      { throw AssertionError("Local saved-reader actions must not contact a bridge") },
      {},
      SavedStateHandle(),
      cache,
      dispatcher,
    )

  private fun createViewModel(
    pause: suspend (Long) -> Unit,
    conversationFactory: suspend (String) -> List<ConversationTurn> = { emptyList() },
    conversationSource: String = "canonical_log",
    reportedMachineName: String? = null,
    offlineHistory: OfflineConversations? = null,
    conversationIdentity: String? = savedId,
    conversationMachine: UUID? = machine,
    streamFactory: (String) -> Flow<StreamEvent>,
  ): ConnectedViewModel =
    ConnectedViewModel(
      store,
      {
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override val machineName = reportedMachineName

          override fun stream(sessionId: String): Flow<StreamEvent> = streamFactory(sessionId)

          override suspend fun conversation(
            sessionId: String
          ): List<com.azusachino.cappuccino.core.ConversationTurn> = conversationFactory(sessionId)

          override suspend fun conversationSnapshot(sessionId: String): ConversationSnapshot =
            ConversationSnapshot(
              conversationFactory(sessionId),
              conversationSource,
              conversationIdentity,
              conversationMachine,
            )

          override val bridgeDetails =
            BridgeDetails(machine, "1", 1, mapOf("mutation_binding" to true))

          override suspend fun submitPrompt(
            sessionId: String,
            text: String,
            confirmed: Boolean,
            binding: MutationBinding,
          ) {}

          override suspend fun answerPrompt(
            sessionId: String,
            promptId: String,
            optionIndex: Int?,
            optionId: String?,
            action: String?,
            binding: MutationBinding,
          ) {}
        }
      },
      pause,
      SavedStateHandle(),
      offlineHistory,
      dispatcher,
    )

  private class MemoryProfiles(initial: MachineProfile) : MachineProfileStore {
    private val profiles = mutableListOf(initial)

    override fun read() = profiles.toList()

    override fun save(profile: MachineProfile) {
      profiles.removeAll { it.id == profile.id }
      profiles += profile
    }

    override fun remove(id: String) {
      profiles.removeAll { it.id == id }
    }

    private var themeMode = com.azusachino.cappuccino.ui.ThemeMode.SYSTEM

    override fun readThemeMode() = themeMode

    override fun saveThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
      themeMode = mode
    }
  }

  private fun List<Long>.repeatTwice() = this + this
}
