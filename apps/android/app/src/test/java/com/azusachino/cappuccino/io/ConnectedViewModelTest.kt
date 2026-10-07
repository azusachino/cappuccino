package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.StreamEvent
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedViewModelTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val profile =
    MachineProfile("p", "Machine", Endpoint.parse("https://bridge.example"), machine)
  private val agent = AgentRow(machine, "locator", "pane", "Agent", "pi", "idle", false, null)
  private val store = MemoryProfiles(profile)
  private val dispatcher = StandardTestDispatcher()
  private lateinit var viewModel: ConnectedViewModel

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
      assertTrue(cancelled)
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

          override fun stream(sessionId: String): Flow<StreamEvent> = flow {
            streams++
            kotlinx.coroutines.awaitCancellation()
          }
        }
      viewModel = ConnectedViewModel(store, { transport }, {}, handle)
      assertEquals(0, verifications)
      viewModel.setForeground(true)
      advanceUntilIdle()
      assertEquals(2, verifications)
      assertEquals(1, catalogs)
      assertEquals(agent, viewModel.state.value.selectedAgent)
      viewModel.setForeground(false)
      val second = ConnectedViewModel(store, { transport }, {}, handle)
      viewModel = second
      assertEquals(2, verifications)
      second.setForeground(true)
      advanceUntilIdle()
      assertEquals(4, verifications)
      assertEquals(2, catalogs)
      assertEquals(agent, second.state.value.selectedAgent)
      assertTrue(streams >= 2)
    }

  private fun savedTarget() =
    SavedStateHandle(
      mapOf(
        ConnectedViewModel.SELECTED_PROFILE to profile.id,
        ConnectedViewModel.SELECTED_MACHINE to machine.toString(),
        ConnectedViewModel.SELECTED_LOCATOR to agent.sessionId,
      )
    )

  private fun createViewModel(
    pause: suspend (Long) -> Unit,
    streamFactory: (String) -> Flow<StreamEvent>,
  ): ConnectedViewModel =
    ConnectedViewModel(
      store,
      {
        object : BridgeTransport {
          override suspend fun verify(expectedMachine: UUID?): UUID = machine

          override suspend fun agents(machine: UUID): List<AgentRow> = listOf(agent)

          override fun stream(sessionId: String): Flow<StreamEvent> = streamFactory(sessionId)

          override suspend fun conversation(
            sessionId: String
          ): List<com.azusachino.cappuccino.core.ConversationTurn> = emptyList()

          override suspend fun submitPrompt(sessionId: String, text: String) {}

          override suspend fun answerPrompt(
            sessionId: String,
            promptId: String,
            optionIndex: Int?,
            optionId: String?,
            action: String?,
          ) {}
        }
      },
      pause,
      SavedStateHandle(),
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
