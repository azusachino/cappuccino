package com.azusachino.cappuccino

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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundRecoveryTest {
  @Test
  fun returningToForegroundRevalidatesCatalogAndResubscribes() {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val store = ProfileStore(context)
    val machine = UUID.randomUUID()
    val profile =
      MachineProfile(
        UUID.randomUUID().toString(),
        "Resume",
        Endpoint.parse("https://resume.example"),
        machine,
      )
    val agent = AgentRow(machine, "resume-session", "pane-1", "pi", "pi", "idle", false, null)
    store.save(profile)
    val verifications = CountDownLatch(4)
    val catalogCalls = AtomicInteger()
    val initialCatalog = CountDownLatch(1)
    val resumedCatalog = CountDownLatch(1)
    val streamCalls = AtomicInteger()
    val initialStream = CountDownLatch(1)
    val resumedStream = CountDownLatch(1)
    val streamCancelled = CountDownLatch(1)
    val viewModel =
      ConnectedViewModel(
        store,
        { _ ->
          object : BridgeTransport {
            override suspend fun verify(expectedMachine: UUID?): UUID {
              verifications.countDown()
              return machine
            }

            override suspend fun agents(machine: UUID): List<AgentRow> {
              if (catalogCalls.incrementAndGet() == 1) initialCatalog.countDown()
              else resumedCatalog.countDown()
              return listOf(agent)
            }

            override fun stream(sessionId: String): Flow<StreamEvent> = flow {
              if (streamCalls.incrementAndGet() == 1) initialStream.countDown()
              else resumedStream.countDown()
              try {
                awaitCancellation()
              } finally {
                streamCancelled.countDown()
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
        viewModel.selectProfile(profile.id)
      }
      assertTrue("initial catalog did not load", await(initialCatalog))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.selectAgent(agent) }
      assertTrue("initial stream did not start", await(initialStream))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      assertTrue("background did not cancel stream", await(streamCancelled))
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(true) }
      assertTrue("foreground did not refresh catalog", await(resumedCatalog))
      assertTrue("foreground did not open a new stream", await(resumedStream))
      assertTrue("foreground did not revalidate profile", await(verifications))
      InstrumentationRegistry.getInstrumentation().runOnMainSync {
        assertEquals(ConnectionState.Connected, viewModel.state.value.connection)
        assertFalse(viewModel.state.value.busy)
      }
    } finally {
      InstrumentationRegistry.getInstrumentation().runOnMainSync { viewModel.setForeground(false) }
      store.remove(profile.id)
    }
  }

  private fun await(latch: CountDownLatch): Boolean = latch.await(5, TimeUnit.SECONDS)
}
