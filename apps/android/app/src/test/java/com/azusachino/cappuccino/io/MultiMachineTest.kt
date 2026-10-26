package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.MutationBinding
import com.azusachino.cappuccino.core.StreamEvent
import com.azusachino.cappuccino.ui.ThemeMode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MultiMachineTest {
  @Test
  fun independentMachinesKeepSameLocatorBoundToItsOwnerAndIsolateFailureAndDisconnect() = runTest {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val mac =
      MachineProfile("mac", "MacMini", Endpoint.parse("https://mac.example"), UUID.randomUUID())
    val mini =
      MachineProfile(
        "mini",
        "Linux mini",
        Endpoint.parse("https://mini.example"),
        UUID.randomUUID(),
      )
    val profiles = mutableListOf(mac, mini)
    val store =
      object : MachineProfileStore {
        override fun read() = profiles.toList()

        override fun save(profile: MachineProfile) {
          profiles.removeAll { it.id == profile.id }
          profiles.add(profile)
        }

        override fun remove(id: String) {
          profiles.removeAll { it.id == id }
        }

        private var palette = com.azusachino.cappuccino.ui.AppPalette.CATPPUCCIN

        override fun readAppPalette() = palette

        override fun saveAppPalette(palette: com.azusachino.cappuccino.ui.AppPalette) {
          this.palette = palette
        }

        override fun readThemeMode() = ThemeMode.DARK

        override fun saveThemeMode(mode: ThemeMode) {}
      }
    var miniFails = false
    val reads = mutableListOf<String>()
    val vm =
      ConnectedViewModel(
        store,
        { endpoint ->
          val profile = profiles.single { it.endpoint == endpoint }
          object : BridgeTransport {
            override suspend fun verify(expectedMachine: UUID?): UUID {
              if (profile == mini && miniFails) throw IOException("Linux mini unavailable")
              check(expectedMachine == null || expectedMachine == profile.machineId)
              return profile.machineId
            }

            override suspend fun agents(machine: UUID) =
              listOf(
                AgentRow(machine, "same-locator", "pane", profile.label, "pi", "idle", false, null)
              )

            override fun stream(sessionId: String) =
              flow<StreamEvent> { kotlinx.coroutines.awaitCancellation() }

            override suspend fun conversation(sessionId: String): List<ConversationTurn> {
              reads.add(profile.id)
              return emptyList()
            }

            override suspend fun submitPrompt(
              sessionId: String,
              text: String,
              confirmed: Boolean,
              binding: MutationBinding,
            ) {
              error("No prompts permitted in this fixture")
            }

            override suspend fun answerPrompt(
              sessionId: String,
              promptId: String,
              optionIndex: Int?,
              optionId: String?,
              action: String?,
              binding: MutationBinding,
            ) {
              error("No approvals permitted in this fixture")
            }
          }
        },
        {},
        SavedStateHandle(),
      )
    try {
      vm.setForeground(true)
      advanceUntilIdle()
      assertEquals(ConnectionState.Connected, vm.state.value.machineConnection(mac).connection)
      assertEquals(ConnectionState.Connected, vm.state.value.machineConnection(mini).connection)
      vm.refreshAll()
      assertFalse(vm.state.value.machineConnection(mac).busy)
      assertFalse(vm.state.value.machineConnection(mini).busy)
      assertEquals(ConnectionState.Connected, vm.state.value.machineConnection(mac).connection)
      advanceUntilIdle()
      vm.refreshProfile(mini.id)
      assertTrue(vm.state.value.machineConnection(mini).busy)
      advanceUntilIdle()
      vm.selectProfile(mac.id)
      vm.selectAgent(vm.state.value.machineConnection(mac).agents.single())
      advanceUntilIdle()
      assertEquals(listOf("mac"), reads)
      vm.addMachine("Unavailable", "https://missing.example")
      advanceUntilIdle()
      assertNotNull(vm.state.value.addError)
      assertEquals(mac.id, vm.state.value.activeProfileId)
      assertEquals(mac.machineId, vm.state.value.selectedAgent?.machineId)
      assertEquals(ConnectionState.Connected, vm.state.value.connection)
      miniFails = true
      vm.refreshProfile(mini.id)
      advanceUntilIdle()
      assertTrue(vm.state.value.machineConnection(mini).connection is ConnectionState.Error)
      assertEquals(mac.id, vm.state.value.activeProfileId)
      assertEquals(mac.machineId, vm.state.value.selectedAgent?.machineId)
      assertEquals(ConnectionState.Connected, vm.state.value.connection)
      miniFails = false
      vm.refreshProfile(mini.id)
      advanceUntilIdle()
      vm.disconnectMachine(mini.id)
      vm.refreshAll()
      advanceUntilIdle()
      assertEquals(ConnectionState.Disconnected, vm.state.value.machineConnection(mini).connection)
      assertEquals(ConnectionState.Connected, vm.state.value.machineConnection(mac).connection)
      vm.refreshProfile(mini.id)
      advanceUntilIdle()
      vm.selectProfile(mini.id)
      vm.selectAgent(vm.state.value.machineConnection(mini).agents.single())
      advanceUntilIdle()
      assertEquals(listOf("mac", "mini"), reads)
      assertEquals(mini.machineId, vm.state.value.selectedAgent?.machineId)
      vm.removeProfile(mac.id)
      assertEquals(ThemeMode.DARK, vm.state.value.themeMode)
      assertEquals(mini.machineId, vm.state.value.selectedAgent?.machineId)
    } finally {
      vm.setForeground(false)
      Dispatchers.resetMain()
    }
  }
}
