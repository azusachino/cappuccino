package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.StreamEvent
import com.azusachino.cappuccino.ui.ThemeMode
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Bridge double that reports [machine] and rejects a verify for any other expected machine. */
private class VerifyingBridge(private val machine: UUID) : BridgeTransport {
  override suspend fun verify(expectedMachine: UUID?): UUID {
    if (expectedMachine != null && expectedMachine != machine) {
      throw IOException("Machine identity changed")
    }
    return machine
  }

  override suspend fun agents(machine: UUID): List<AgentRow> = emptyList()

  override fun stream(sessionId: String): Flow<StreamEvent> = emptyFlow()
}

private class MemoryStore(initial: List<MachineProfile>) : MachineProfileStore {
  private val profiles = initial.toMutableList()
  private var theme = ThemeMode.SYSTEM

  override fun read() = profiles.toList()

  override fun save(profile: MachineProfile) {
    profiles.removeAll { it.id == profile.id }
    profiles += profile
  }

  override fun remove(id: String) {
    profiles.removeAll { it.id == id }
  }

  override fun readThemeMode() = theme

  override fun saveThemeMode(mode: ThemeMode) {
    theme = mode
  }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectedViewModelPairingTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val otherMachine = UUID.fromString("22222222-2222-4222-8222-222222222222")
  private val tailnetProfile =
    MachineProfile("mac", "MacMini", Endpoint.parse("https://mac.example"), machine)
  private val irohProfile =
    MachineProfile(
      "office",
      "Office",
      Endpoint.iroh(IrohEndpointAddr("bridge-id", null, listOf("10.0.0.2:4433"))),
      machine,
    )
  private val pairingPayload =
    """{"v":1,"endpoint_id":"bridge-id","relay":null,"addrs":["10.0.0.2:4433"],"token":"tok"}"""
  private val dispatcher = StandardTestDispatcher()

  @Before
  fun setup() {
    Dispatchers.setMain(dispatcher)
  }

  @After
  fun teardown() {
    Dispatchers.resetMain()
  }

  private fun viewModel(
    store: MemoryStore,
    bridgeMachine: UUID = machine,
    redeemed: MutableList<IrohPairing> = mutableListOf(),
  ) =
    ConnectedViewModel(
      store,
      { VerifyingBridge(bridgeMachine) },
      {},
      SavedStateHandle(),
      irohPairing = { redeemed += it },
    )

  @Test
  fun addIrohPairingKeepsTheTailnetUrlAndAddsTheIrohPairing() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(tailnetProfile))
      val redeemed = mutableListOf<IrohPairing>()
      val vm = viewModel(store, redeemed = redeemed)

      vm.addIrohPairing(tailnetProfile.id, pairingPayload)
      advanceUntilIdle()

      assertEquals("bridge-id", redeemed.single().endpoint.endpointId)
      val saved = store.read().single()
      assertEquals(tailnetProfile.id, saved.id)
      assertEquals(tailnetProfile.machineId, saved.machineId)
      assertEquals(Endpoint.parse("https://mac.example").base, saved.endpoint.base)
      assertEquals("bridge-id", saved.endpoint.iroh?.endpointId)
      assertNull(vm.state.value.addError)
    }

  @Test
  fun addIrohPairingLeavesTheProfileUnchangedWhenTheBridgeIsAnotherMachine() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(tailnetProfile))
      val vm = viewModel(store, bridgeMachine = otherMachine)

      vm.addIrohPairing(tailnetProfile.id, pairingPayload)
      advanceUntilIdle()

      assertEquals(listOf(tailnetProfile), store.read())
      assertNotNull(vm.state.value.addError)
      assertEquals(false, vm.state.value.busy)
    }

  @Test
  fun addTailnetUrlKeepsTheIrohPairingAndAddsTheTailnetUrl() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(irohProfile))
      val vm = viewModel(store)

      vm.addTailnetUrl(irohProfile.id, "https://office.example")
      advanceUntilIdle()

      val saved = store.read().single()
      assertEquals(Endpoint.parse("https://office.example").base, saved.endpoint.base)
      assertEquals("bridge-id", saved.endpoint.iroh?.endpointId)
      assertEquals(listOf("10.0.0.2:4433"), saved.endpoint.iroh?.directAddrs)
      assertNull(vm.state.value.addError)
    }

  @Test
  fun secondTailnetSubmitWhileTheFirstVerifiesIsIgnored() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(irohProfile))
      val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
      val bridge =
        object : BridgeTransport by VerifyingBridge(machine) {
          override suspend fun verify(expectedMachine: UUID?): UUID {
            gate.await()
            return machine
          }
        }
      val vm = ConnectedViewModel(store, { bridge }, {}, SavedStateHandle())

      vm.addTailnetUrl(irohProfile.id, "https://office.example")
      vm.addTailnetUrl(irohProfile.id, "https://other.example")
      gate.complete(Unit)
      advanceUntilIdle()

      assertEquals(
        Endpoint.parse("https://office.example").base,
        store.read().single().endpoint.base,
      )
      assertNull(vm.state.value.addError)
    }

  @Test
  fun addTailnetUrlLeavesTheProfileUnchangedWhenTheBridgeIsAnotherMachine() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(irohProfile))
      val vm = viewModel(store, bridgeMachine = otherMachine)

      vm.addTailnetUrl(irohProfile.id, "https://office.example")
      advanceUntilIdle()

      assertEquals(listOf(irohProfile), store.read())
      assertNotNull(vm.state.value.addError)
    }

  @Test
  fun addIrohMachineWithTailnetUrlConfiguresBothTransports() =
    runTest(dispatcher) {
      val store = MemoryStore(emptyList())
      val redeemed = mutableListOf<IrohPairing>()
      val vm = viewModel(store, redeemed = redeemed)
      val payloadWithTailnet =
        pairingPayload.replace("\"tok\"}", "\"tok\",\"tailnet_url\":\"https://mac.example\"}")

      vm.addIrohMachine("My Machine", payloadWithTailnet)
      advanceUntilIdle()

      val saved = store.read().single()
      assertEquals("My Machine", saved.label)
      assertEquals(Endpoint.parse("https://mac.example").base, saved.endpoint.base)
      assertEquals("bridge-id", saved.endpoint.iroh?.endpointId)
      assertEquals(true, saved.endpoint.hasTailnet)
      assertNull(vm.state.value.addError)
    }

  @Test
  fun addIrohPairingWithTailnetUrlAttachesTailnetWhenMachineWasIrohOnly() =
    runTest(dispatcher) {
      val store = MemoryStore(listOf(irohProfile))
      val vm = viewModel(store)
      val payloadWithTailnet =
        pairingPayload.replace("\"tok\"}", "\"tok\",\"tailnet_url\":\"https://mac.example\"}")

      vm.addIrohPairing(irohProfile.id, payloadWithTailnet)
      advanceUntilIdle()

      val saved = store.read().single()
      assertEquals(Endpoint.parse("https://mac.example").base, saved.endpoint.base)
      assertEquals("bridge-id", saved.endpoint.iroh?.endpointId)
      assertEquals(true, saved.endpoint.hasTailnet)
      assertNull(vm.state.value.addError)
    }
}
