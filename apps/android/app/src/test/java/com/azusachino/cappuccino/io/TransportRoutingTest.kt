package com.azusachino.cappuccino.io

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

private class FakeClock(var now: Long = 0L) : MonotonicClock {
  override fun nowMs(): Long = now
}

/** Iroh transport double: records endpoint stops and connection drops, and reports a set path. */
private class RecordingIroh(var path: IrohPathKind = IrohPathKind.NONE) : IrohTransport {
  var stops = 0
  var drops = 0

  override suspend fun start(secretKey: ByteArray) = Unit

  override suspend fun stop() {
    stops++
  }

  override suspend fun dropConnections() {
    drops++
  }

  override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream =
    error("routing tests never open a stream")

  override fun pathKind(addr: IrohEndpointAddr): IrohPathKind = path
}

/** Clients are plain strings; the block under test returns the client it was given. */
@OptIn(ExperimentalCoroutinesApi::class)
class MachineRouterTest {
  private val both = mapOf(TransportKind.IROH to "iroh", TransportKind.TAILNET to "tailnet")

  @Test
  fun irohIsProbedFirstAndTailnetIsNotTouchedWhenIrohWorks() = runTest {
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val result = router.request {
      calls += it
      it
    }
    assertEquals("iroh", result)
    assertEquals(listOf("iroh"), calls)
  }

  @Test
  fun failedIrohFallsBackToTailnetInTheSameRequestAndTailnetStaysSticky() = runTest {
    var irohUp = false
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      if (client == "iroh" && !irohUp) throw IOException("dial failed")
      client
    }
    assertEquals("tailnet", router.request(block))
    irohUp = true
    assertEquals("tailnet", router.request(block))
    // Sticky: the second request does not probe iroh while tailnet keeps working.
    assertEquals(listOf("iroh", "tailnet", "tailnet"), calls)
  }

  @Test
  fun requestFailureReprobesInOrderAndMovesToTheTransportThatWorks() = runTest {
    var irohUp = true
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      if (client == "iroh" && !irohUp) throw IOException("dial failed")
      client
    }
    assertEquals("iroh", router.request(block))
    irohUp = false
    assertEquals("tailnet", router.request(block))
    assertEquals(listOf("iroh", "iroh", "tailnet"), calls)
  }

  @Test
  fun networkChangeClearsStickinessSoIrohIsProbedFirstAgain() = runTest {
    var irohUp = false
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      if (client == "iroh" && !irohUp) throw IOException("dial failed")
      client
    }
    assertEquals("tailnet", router.request(block))
    irohUp = true
    router.onNetworkChanged()
    assertEquals("iroh", router.request(block))
    assertEquals(listOf("iroh", "tailnet", "iroh"), calls)
  }

  @Test
  fun networkChangeCancelsAnInFlightIrohDialAndRedialsAtOnce() = runTest {
    val iroh = RecordingIroh()
    val router = MachineRouter(both, FakeClock(), iroh)
    val attempts = mutableListOf<String>()
    val stuck = CompletableDeferred<String>()
    val result = async {
      router.request { client ->
        if (client == "iroh") {
          attempts += client
          // The first dial hangs as a dead path would; only a network change ends it.
          if (attempts.size == 1) stuck.await()
        }
        client
      }
    }
    runCurrent()
    router.onNetworkChanged()
    assertEquals("iroh", result.await())
    assertEquals(2, attempts.size)
    assertEquals(1, iroh.drops)
    // No dial timeout was waited out: virtual time never advanced.
    assertEquals(0L, testScheduler.currentTime)
    assertEquals(0, iroh.stops)
  }

  @Test
  fun threeConsecutiveIrohFailuresRebuildTheEndpointOnce() = runTest {
    val iroh = RecordingIroh()
    val router = MachineRouter(mapOf(TransportKind.IROH to "iroh"), FakeClock(), iroh)
    val fail: suspend (String) -> String = { throw IOException("bridge unreachable") }
    repeat(2) { runCatching { router.request(fail) } }
    assertEquals(0, iroh.stops)
    runCatching { router.request(fail) }
    assertEquals(1, iroh.stops)
    assertEquals(0, iroh.drops)
  }

  @Test
  fun aSuccessfulIrohAttemptResetsTheFailureCount() = runTest {
    val iroh = RecordingIroh()
    val router = MachineRouter(mapOf(TransportKind.IROH to "iroh"), FakeClock(), iroh)
    var up = false
    val block: suspend (String) -> String = { client ->
      if (!up) throw IOException("dial failed")
      client
    }
    repeat(2) { runCatching { router.request(block) } }
    up = true
    router.request(block)
    up = false
    repeat(2) { runCatching { router.request(block) } }
    assertEquals(0, iroh.stops)
  }

  @Test
  fun bridgeAnswerOverIrohPropagatesWithoutFallbackOrCounting() = runTest {
    val iroh = RecordingIroh()
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), iroh)
    val answered: suspend (String) -> String = { client ->
      calls += client
      throw BridgeHttpStatusException(500, "bridge error")
    }
    repeat(3) {
      val error = runCatching { router.request(answered) }.exceptionOrNull()
      assertEquals(BridgeHttpStatusException::class.java, error?.javaClass)
    }
    // Three answered errors never reach tailnet and never trigger an endpoint rebuild.
    assertEquals(listOf("iroh", "iroh", "iroh"), calls)
    assertEquals(0, iroh.stops)
  }

  @Test
  fun aRejectionFromTailnetKeepsTailnetSticky() = runTest {
    var irohUp = false
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      if (client == "iroh" && !irohUp) throw IOException("dial failed")
      if (client == "tailnet") throw BridgeHttpRejectionException("conflict")
      client
    }
    val error = runCatching { router.request(block) }.exceptionOrNull()
    assertEquals(BridgeHttpRejectionException::class.java, error?.javaClass)
    irohUp = true
    runCatching { router.request(block) }
    // Tailnet answered, so it stays sticky and iroh is not probed again.
    assertEquals(listOf("iroh", "tailnet", "tailnet"), calls)
  }

  @Test
  fun ioTimeoutOverIrohFallsThroughToTailnetAndCountsTowardRebuild() = runTest {
    val iroh = RecordingIroh()
    val fallthrough = MachineRouter(both, FakeClock(), iroh)
    val block: suspend (String) -> String = { client ->
      if (client == "iroh") throw SocketTimeoutException("iroh connect timed out")
      client
    }
    assertEquals("tailnet", fallthrough.request(block))

    val irohOnly = MachineRouter(mapOf(TransportKind.IROH to "iroh"), FakeClock(), iroh)
    repeat(IROH_REBUILD_AFTER_FAILURES) { runCatching { irohOnly.request(block) } }
    assertEquals(1, iroh.stops)
  }

  @Test
  fun whenEveryTransportFailsTheLastErrorIsThrown() = runTest {
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val error = runCatching {
      router.request { client -> throw IOException("$client down") }
    }
      .exceptionOrNull()
    assertEquals("tailnet down", error?.message)
  }

  @Test
  fun badgeFollowsTheSelectedPathAndHoldsADirectRelayedSwitch() = runTest {
    val clock = FakeClock()
    val iroh = RecordingIroh(IrohPathKind.DIRECT)
    val router = MachineRouter(both, clock, iroh, irohPath = { iroh.path })
    router.request { it }
    assertEquals(TransportBadge.IROH_DIRECT, router.badge())
    iroh.path = IrohPathKind.RELAYED
    clock.now = 1_000
    assertEquals(TransportBadge.IROH_DIRECT, router.badge())
    clock.now = 11_000
    assertEquals(TransportBadge.IROH_RELAYED, router.badge())
  }
}

class BadgeHoldDownTest {
  @Test
  fun firstReadingAppearsAtOnce() {
    val holdDown = BadgeHoldDown(FakeClock())
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(TransportBadge.IROH_DIRECT))
  }

  @Test
  fun directToRelayedWaitsForTheFullHoldDown() {
    val clock = FakeClock()
    val holdDown = BadgeHoldDown(clock)
    holdDown.observe(TransportBadge.IROH_DIRECT)
    clock.now = 1_000
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(TransportBadge.IROH_RELAYED))
    clock.now = 10_999
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(TransportBadge.IROH_RELAYED))
    clock.now = 11_000
    assertEquals(TransportBadge.IROH_RELAYED, holdDown.observe(TransportBadge.IROH_RELAYED))
  }

  @Test
  fun aFlickerBackToTheShownPathRestartsTheHoldDown() {
    val clock = FakeClock()
    val holdDown = BadgeHoldDown(clock)
    holdDown.observe(TransportBadge.IROH_DIRECT)
    clock.now = 1_000
    holdDown.observe(TransportBadge.IROH_RELAYED)
    clock.now = 5_000
    holdDown.observe(TransportBadge.IROH_DIRECT)
    clock.now = 6_000
    holdDown.observe(TransportBadge.IROH_RELAYED)
    clock.now = 15_999
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(TransportBadge.IROH_RELAYED))
    clock.now = 16_000
    assertEquals(TransportBadge.IROH_RELAYED, holdDown.observe(TransportBadge.IROH_RELAYED))
  }

  @Test
  fun tailnetSwitchAppliesWithoutHoldDown() {
    val clock = FakeClock()
    val holdDown = BadgeHoldDown(clock)
    holdDown.observe(TransportBadge.IROH_DIRECT)
    assertEquals(TransportBadge.TAILNET, holdDown.observe(TransportBadge.TAILNET))
  }

  @Test
  fun aMissingOrNoneReadingKeepsTheShownBadge() {
    val holdDown = BadgeHoldDown(FakeClock())
    holdDown.observe(TransportBadge.IROH_DIRECT)
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(null))
    assertEquals(TransportBadge.IROH_DIRECT, holdDown.observe(TransportBadge.NONE))
  }

  @Test
  fun pollCadenceIsSlowOnlyWhileRelayed() {
    assertEquals(30_000L, pollIntervalMs(TransportBadge.IROH_RELAYED))
    assertEquals(5_000L, pollIntervalMs(TransportBadge.IROH_DIRECT))
    assertEquals(5_000L, pollIntervalMs(TransportBadge.TAILNET))
    assertEquals(5_000L, pollIntervalMs(TransportBadge.NONE))
  }
}
