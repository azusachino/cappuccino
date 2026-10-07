package com.azusachino.cappuccino

import android.app.Application
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.StreamEvent
import com.azusachino.cappuccino.io.BridgeTransport
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.ProfileStore
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectedControllerTest {
  @Test
  fun retryStopsAfterInitialAttemptAndFourRetries() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val application = context.applicationContext as Application
    val store = ProfileStore(context)
    val suffix = UUID.randomUUID().toString()
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile("retry-$suffix", "Retry", Endpoint.parse("https://retry.example"), machine)
    store.save(profile)
    val streamAttempts = CountDownLatch(5)
    val catalogLoaded = CountDownLatch(1)
    val agent = AgentRow(machine, "retry-session", "pane-1", "pi", "pi", "idle", false, null)
    val viewModel =
      ConnectedViewModel(
        store = store,
        bridgeFor = { _ ->
          object : BridgeTransport {
            override suspend fun verify(expectedMachine: UUID?): UUID = machine

            override suspend fun agents(machine: UUID): List<AgentRow> {
              catalogLoaded.countDown()
              return listOf(agent)
            }

            override fun stream(sessionId: String): Flow<StreamEvent> = flow {
              streamAttempts.countDown()
              throw IOException("synthetic disconnect")
            }
          }
        },
        retryPause = {},
        savedStateHandle = SavedStateHandle(),
      )
    try {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.setForeground(true)
        viewModel.selectProfile(profile.id)
      }
      assertTrue("catalog did not load", catalogLoaded.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.selectAgent(agent) }
      assertTrue("stream retries did not exhaust", streamAttempts.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        assertTrue(viewModel.state.value.connection is ConnectionState.Error)
      }
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      store.remove(profile.id)
    }
  }

  @Test
  fun sameLocatorOnDifferentMachinesDoesNotCarrySelection() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val application = context.applicationContext as Application
    val store = ProfileStore(context)
    val suffix = UUID.randomUUID().toString()
    val firstMachine = UUID.randomUUID()
    val secondMachine = UUID.randomUUID()
    val first =
      MachineProfile(
        "same-first-$suffix",
        "First",
        Endpoint.parse("https://one.example"),
        firstMachine,
      )
    val second =
      MachineProfile(
        "same-second-$suffix",
        "Second",
        Endpoint.parse("https://two.example"),
        secondMachine,
      )
    val firstAgent =
      AgentRow(firstMachine, "same-locator", "pane-1", "pi", "pi", "idle", false, null)
    val secondAgent =
      AgentRow(secondMachine, "same-locator", "pane-2", "pi", "pi", "idle", false, null)
    store.save(first)
    store.save(second)
    val firstCatalogLoaded = CountDownLatch(1)
    val secondCatalogLoaded = CountDownLatch(1)
    val firstStreamStarted = CountDownLatch(1)
    val firstStreamCancelled = CountDownLatch(1)
    val viewModel =
      ConnectedViewModel(
        store,
        { endpoint ->
          object : BridgeTransport {
            override suspend fun verify(expectedMachine: UUID?): UUID =
              if (endpoint.base.host == "one.example") firstMachine else secondMachine

            override suspend fun agents(machine: UUID): List<AgentRow> {
              if (machine == firstMachine) firstCatalogLoaded.countDown()
              else secondCatalogLoaded.countDown()
              return listOf(if (machine == firstMachine) firstAgent else secondAgent)
            }

            override fun stream(sessionId: String): Flow<StreamEvent> = flow {
              firstStreamStarted.countDown()
              try {
                awaitCancellation()
              } finally {
                firstStreamCancelled.countDown()
              }
            }
          }
        },
        { kotlinx.coroutines.delay(it) },
        SavedStateHandle(),
      )
    try {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.setForeground(true)
        viewModel.selectProfile(first.id)
      }
      assertTrue("first catalog did not load", firstCatalogLoaded.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.selectAgent(firstAgent)
      }
      assertTrue("first stream did not start", firstStreamStarted.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.selectProfile(second.id)
      }
      assertTrue("first stream did not cancel", firstStreamCancelled.await(5, TimeUnit.SECONDS))
      assertTrue("second catalog did not load", secondCatalogLoaded.await(5, TimeUnit.SECONDS))
      assertEquals(second.id, viewModel.state.value.activeProfileId)
      assertNull(
        "same locator was incorrectly carried across machines",
        viewModel.state.value.selectedAgent,
      )
      assertEquals(secondMachine, viewModel.state.value.agents.single().machineId)
      assertEquals(firstAgent.sessionId, viewModel.state.value.agents.single().sessionId)
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      store.remove(first.id)
      store.remove(second.id)
    }
  }

  @Test
  fun switchingMachineCancelsOldRequestAndBackgroundCancelsStream() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val application = context.applicationContext as Application
    val store = ProfileStore(context)
    val suffix = UUID.randomUUID().toString()
    val first =
      MachineProfile(
        "first-$suffix",
        "First",
        Endpoint.parse("https://first.example"),
        UUID.randomUUID(),
      )
    val secondMachine = UUID.randomUUID()
    val second =
      MachineProfile(
        "second-$suffix",
        "Second",
        Endpoint.parse("https://second.example"),
        secondMachine,
      )
    store.save(first)
    store.save(second)
    assertEquals(first, ProfileStore(context).read().first { it.id == first.id })
    assertEquals(second, ProfileStore(context).read().first { it.id == second.id })
    val firstStarted = CountDownLatch(1)
    val firstCancelled = CountDownLatch(1)
    val streamStarted = CountDownLatch(1)
    val streamCancelled = CountDownLatch(1)
    val lateStreamEventAttempted = CountDownLatch(1)
    val callbackExecutor = Executors.newSingleThreadScheduledExecutor()
    val catalogLoaded = CountDownLatch(1)
    val agent = AgentRow(secondMachine, "same-locator", "pane-2", "pi", "pi", "idle", false, null)
    val viewModel =
      ConnectedViewModel(
        store,
        { endpoint ->
          object : BridgeTransport {
            override suspend fun verify(expectedMachine: UUID?): UUID {
              if (endpoint.base.host == "first.example") {
                return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
                  firstStarted.countDown()
                  continuation.invokeOnCancellation { firstCancelled.countDown() }
                }
              }
              return secondMachine
            }

            override suspend fun agents(machine: UUID): List<AgentRow> {
              catalogLoaded.countDown()
              return listOf(agent)
            }

            override fun stream(sessionId: String): Flow<StreamEvent> = callbackFlow {
              streamStarted.countDown()
              callbackExecutor.schedule(
                {
                  trySend(StreamEvent.Open(sessionId, 1))
                  lateStreamEventAttempted.countDown()
                },
                500,
                TimeUnit.MILLISECONDS,
              )
              awaitClose { streamCancelled.countDown() }
            }
          }
        },
        { kotlinx.coroutines.delay(it) },
        SavedStateHandle(),
      )

    try {
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.setForeground(true)
        viewModel.selectProfile(first.id)
      }
      assertTrue("first request did not start", firstStarted.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        viewModel.selectProfile(second.id)
      }
      assertTrue(
        "old request was not cancelled on machine switch",
        firstCancelled.await(5, TimeUnit.SECONDS),
      )
      assertTrue("second catalog did not load", catalogLoaded.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.selectAgent(agent) }
      assertTrue("selected stream did not start", streamStarted.await(5, TimeUnit.SECONDS))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      assertTrue("stream did not cancel on background", streamCancelled.await(5, TimeUnit.SECONDS))
      assertTrue(
        "late callback was not exercised",
        lateStreamEventAttempted.await(5, TimeUnit.SECONDS),
      )
      assertEquals(second.id, viewModel.state.value.activeProfileId)
      assertEquals(ConnectionState.Disconnected, viewModel.state.value.connection)
      assertFalse("background left stale progress active", viewModel.state.value.busy)
      assertTrue(
        "late event changed cleared output",
        viewModel.state.value.stream.entries.isEmpty(),
      )
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      store.remove(first.id)
      store.remove(second.id)
      callbackExecutor.shutdownNow()
      assertTrue(callbackExecutor.awaitTermination(5, TimeUnit.SECONDS))
    }
  }
}
