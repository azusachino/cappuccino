package com.azusachino.cappuccino.io

import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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

  override fun dialPoll(addr: IrohEndpointAddr, alpn: String): IrohPollConnection =
    error("routing tests never poll")
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
    assertEquals("tailnet", router.request(block = block))
    irohUp = true
    assertEquals("tailnet", router.request(block = block))
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
    assertEquals("iroh", router.request(block = block))
    irohUp = false
    assertEquals("tailnet", router.request(block = block))
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
    assertEquals("tailnet", router.request(block = block))
    irohUp = true
    router.onNetworkChanged()
    assertEquals("iroh", router.request(block = block))
    assertEquals(listOf("iroh", "tailnet", "iroh"), calls)
  }

  @Test
  fun networkChangeDuringAMutationNeverResendsItAndSurfacesOutcomeUnknown() = runTest {
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val attempts = mutableListOf<String>()
    val stuck = CompletableDeferred<String>()
    val result = async {
      runCatching {
        router.request(mutation = true) { client ->
          attempts += client
          // The change is on the wire when the network changes; nothing says it was not applied.
          stuck.await()
        }
      }
    }
    runCurrent()
    router.onNetworkChanged()
    assertEquals(
      BridgeMutationOutcomeUnknownException::class.java,
      result.await().exceptionOrNull()?.javaClass,
    )
    assertEquals(listOf("iroh"), attempts)
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
    repeat(2) { runCatching { router.request(block = fail) } }
    assertEquals(0, iroh.stops)
    runCatching { router.request(block = fail) }
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
    repeat(2) { runCatching { router.request(block = block) } }
    up = true
    router.request(block = block)
    up = false
    repeat(2) { runCatching { router.request(block = block) } }
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
      val error = runCatching { router.request(block = answered) }.exceptionOrNull()
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
    val error = runCatching { router.request(block = block) }.exceptionOrNull()
    assertEquals(BridgeHttpRejectionException::class.java, error?.javaClass)
    irohUp = true
    runCatching { router.request(block = block) }
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
    assertEquals("tailnet", fallthrough.request(block = block))

    val irohOnly = MachineRouter(mapOf(TransportKind.IROH to "iroh"), FakeClock(), iroh)
    repeat(IROH_REBUILD_AFTER_FAILURES) { runCatching { irohOnly.request(block = block) } }
    assertEquals(1, iroh.stops)
  }

  @Test
  fun oneMachineFailingDoesNotRestartTheSharedEndpointWhileAnotherMachineIsServed() = runTest {
    // Both routers share one process endpoint, as MachineTransports builds them.
    val iroh = RecordingIroh()
    val clock = FakeClock()
    val health = SharedIrohHealth(clock)
    val offline =
      MachineRouter(mapOf(TransportKind.IROH to "offline"), clock, iroh, health = health)
    val healthy =
      MachineRouter(mapOf(TransportKind.IROH to "healthy"), clock, iroh, health = health)
    assertEquals("healthy", healthy.request { it })
    repeat(IROH_REBUILD_AFTER_FAILURES) {
      runCatching {
        offline.request<String> { throw SocketTimeoutException("iroh connect timed out") }
      }
    }
    // Restarting the shared endpoint would cut the healthy machine's live streams.
    assertEquals(0, iroh.stops)

    // Once nobody has been served for a while the endpoint may be wedged, so it restarts.
    clock.now += IROH_ENDPOINT_ALIVE_MS
    repeat(IROH_REBUILD_AFTER_FAILURES) {
      runCatching { offline.request<String> { throw SocketTimeoutException("timed out") } }
    }
    assertEquals(1, iroh.stops)
  }

  @Test
  fun mutationWithMidRequestIoErrorOverIrohIsNeverResentAndSurfacesOutcomeUnknown() = runTest {
    val iroh = RecordingIroh()
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), iroh)
    val block: suspend (String) -> String = { client ->
      calls += client
      // The request left the phone before the reset, so the bridge may have applied it.
      if (client == "iroh") throw SocketException("Connection reset")
      client
    }
    repeat(IROH_REBUILD_AFTER_FAILURES) {
      val error = runCatching { router.request(mutation = true, block = block) }.exceptionOrNull()
      assertEquals(BridgeMutationOutcomeUnknownException::class.java, error?.javaClass)
    }
    // No tailnet call: the change is never sent twice. Each failure still counted toward rebuild.
    assertEquals(listOf("iroh", "iroh", "iroh"), calls)
    assertEquals(1, iroh.stops)
  }

  @Test
  fun mutationWithConnectFailureOverIrohFallsThroughToTailnetOnce() = runTest {
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      // The dial failed before any byte was sent, so the change cannot have reached the bridge.
      if (client == "iroh") throw ConnectException("iroh connect failed")
      client
    }
    assertEquals("tailnet", router.request(mutation = true, block = block))
    assertEquals(listOf("iroh", "tailnet"), calls)
  }

  @Test
  fun readWithMidRequestIoErrorOverIrohFallsThroughToTailnet() = runTest {
    val calls = mutableListOf<String>()
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val block: suspend (String) -> String = { client ->
      calls += client
      if (client == "iroh") throw SocketException("Connection reset")
      client
    }
    assertEquals("tailnet", router.request(block = block))
    assertEquals(listOf("iroh", "tailnet"), calls)
  }

  @Test
  fun irohInCooldownIsSkippedWhileTailnetIsUntriedThenProbedAgainAfterIt() = runTest {
    val clock = FakeClock()
    val router = MachineRouter(both, clock, RecordingIroh())
    val calls = mutableListOf<String>()
    // Both fail once, so nothing is sticky and iroh enters its first 5s cooldown.
    runCatching {
      router.request { client ->
        calls += client
        throw IOException("$client down")
      }
    }
    assertEquals(listOf("iroh", "tailnet"), calls)

    calls.clear()
    clock.now = 1_000L
    assertEquals(
      "tailnet",
      router.request { client ->
        calls += client
        client
      },
    )
    assertEquals("iroh skipped during its cooldown", listOf("tailnet"), calls)

    calls.clear()
    clock.now = 6_000L
    router.request { client ->
      calls += client
      client
    }
    assertEquals(
      "sticky tailnet keeps serving; iroh is not probed while it works",
      listOf("tailnet"),
      calls,
    )
  }

  @Test
  fun cooldownDoublesPerFailureAndManualRedialSkipsIt() = runTest {
    val clock = FakeClock()
    val router = MachineRouter(both, clock, RecordingIroh())
    suspend fun failBoth() = runCatching {
      router.request { client -> throw IOException("$client down") }
    }
    failBoth() // 5s cooldown from t=0
    clock.now = 4_000L
    failBoth() // still cooling; iroh is the only untried route left, so it is tried; cooldown 10s
    val calls = mutableListOf<String>()
    clock.now = 13_000L // 9s after the second failure: still inside the 10s window
    router.request { client ->
      calls += client
      client
    }
    assertEquals("doubled cooldown still skips iroh", listOf("tailnet"), calls)

    router.redial()
    calls.clear()
    router.request { client ->
      calls += client
      client
    }
    assertEquals("manual redial probes iroh at once", listOf("iroh"), calls)
  }

  @Test
  fun aCooldownThatTriesTailnetFirstStillReportsTheIrohFailure() = runTest {
    val clock = FakeClock()
    val router = MachineRouter(both, clock, RecordingIroh())
    runCatching { router.request { client -> throw IOException("$client down") } }
    clock.now = 1_000L // iroh is cooling: tailnet is tried first, then iroh
    val error = runCatching { router.request { client -> throw IOException("$client down") } }
    assertEquals("iroh down", error.exceptionOrNull()?.message)
  }

  @Test
  fun whenEveryTransportFailsTheFirstRouteErrorIsThrown() = runTest {
    val router = MachineRouter(both, FakeClock(), RecordingIroh())
    val error = runCatching {
      router.request { client -> throw IOException("$client down") }
    }
      .exceptionOrNull()
    // The fallback's failure (e.g. a tailnet DNS miss) must not hide the route the owner chose.
    assertEquals("iroh down", error?.message)
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

  @Test
  fun unknownHostExceptionMarksTailnetUnreachableAndStopsProbingIt() = runTest {
    val clock = FakeClock()
    val router = MachineRouter(both, clock, RecordingIroh())
    val calls = mutableListOf<String>()

    // First request: Iroh fails with generic IOException, fallthrough to Tailnet fails with
    // UnknownHostException
    val error = runCatching {
      router.request { client ->
        calls += client
        if (client == "iroh") throw IOException("iroh transient timeout")
        if (client == "tailnet") throw UnknownHostException("harus-mini.ts.net")
        client
      }
    }
      .exceptionOrNull()
    assertEquals("iroh transient timeout", error?.message)
    assertEquals(listOf("iroh", "tailnet"), calls)

    // Second request at t=1_000 (Iroh would be cooling down, but Tailnet is unreachable):
    // Tailnet is NOT probed; only Iroh is probed.
    calls.clear()
    clock.now = 1_000L
    val result = router.request { client ->
      calls += client
      client
    }
    assertEquals("iroh", result)
    assertEquals("unreachable tailnet is skipped even when iroh was cooling", listOf("iroh"), calls)
  }

  @Test
  fun networkChangeClearsTailnetUnreachability() = runTest {
    val clock = FakeClock()
    val router = MachineRouter(both, clock, RecordingIroh())
    val calls = mutableListOf<String>()

    // Tailnet DNS fails, marking it unreachable
    runCatching {
      router.request { client ->
        calls += client
        if (client == "iroh") throw IOException("iroh down")
        if (client == "tailnet") throw UnknownHostException("harus-mini.ts.net")
        client
      }
    }
    assertEquals(listOf("iroh", "tailnet"), calls)

    // Next request skips Tailnet
    calls.clear()
    clock.now = 1_000L
    runCatching {
      router.request { client ->
        calls += client
        throw IOException("$client down")
      }
    }
    assertEquals(listOf("iroh"), calls)

    // Network changes (e.g. connected to Tailscale VPN)
    router.onNetworkChanged()

    // Now Tailnet is probed again on fallback
    calls.clear()
    val result = router.request { client ->
      calls += client
      if (client == "iroh") throw IOException("iroh down")
      client
    }
    assertEquals("tailnet", result)
    assertEquals(listOf("iroh", "tailnet"), calls)
  }

  @Test
  fun directIrohPathDoesNotTriggerCooldownOnSingleFailure() = runTest {
    val clock = FakeClock()
    val iroh = RecordingIroh(IrohPathKind.DIRECT)
    val router = MachineRouter(both, clock, iroh, irohPath = { iroh.path })
    val calls = mutableListOf<String>()

    // Single request failure on Iroh Direct
    var irohFails = true
    runCatching {
      router.request { client ->
        calls += client
        if (client == "iroh" && irohFails) throw IOException("read timeout")
        if (client == "tailnet") throw IOException("tailnet down")
        client
      }
    }
    assertEquals(listOf("iroh", "tailnet"), calls)

    // Next request at t=1_000: Iroh Direct was not given a 5s cooldown because it is DIRECT
    // and only failed once, so it is still probed first!
    calls.clear()
    clock.now = 1_000L
    irohFails = false
    val result = router.request { client ->
      calls += client
      client
    }
    assertEquals("iroh", result)
    assertEquals("iroh direct is probed first without 5s cooldown", listOf("iroh"), calls)
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
