package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import java.io.IOException
import java.net.ConnectException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private val pollAddr = IrohEndpointAddr("bridge-id", null, listOf("10.0.0.2:4433"))
private val machine = UUID.fromString("11111111-2222-3333-4444-555555555555")

private class PollTestClock : MonotonicClock {
  override fun nowMs(): Long = 0L
}

/**
 * One poll's connection: streams are real TCP to the mock bridge; [fails] models a dead iroh path.
 */
private class FakePollConnection(
  private val port: Int,
  private val fails: Boolean,
) : IrohPollConnection {
  var streamsOpened = 0
  var closes = 0

  override suspend fun openStream(): IrohBiStream {
    if (fails) throw ConnectException("iroh dial failed")
    streamsOpened++
    return TcpTunnelTransport(port).openStream(pollAddr)
  }

  override suspend fun close() {
    closes++
  }
}

/**
 * Iroh transport double. Polls get their own [FakePollConnection]s; any use of the shared cache or
 * of the endpoint lifecycle is recorded so tests can prove a poll never touches them.
 */
private class PollRecorderIroh(private val port: Int, private val pollFails: Boolean = false) :
  IrohTransport {
  val polls = mutableListOf<FakePollConnection>()
  var sharedOpens = 0
  var drops = 0
  var stops = 0

  override suspend fun start(secretKey: ByteArray) = Unit

  override suspend fun stop() {
    stops++
  }

  override suspend fun dropConnections() {
    drops++
  }

  override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream {
    sharedOpens++
    return TcpTunnelTransport(port).openStream(addr, alpn)
  }

  override fun pathKind(addr: IrohEndpointAddr): IrohPathKind = IrohPathKind.NONE

  override fun dialPoll(addr: IrohEndpointAddr, alpn: String): IrohPollConnection =
    FakePollConnection(port, pollFails).also { polls += it }
}

class BackgroundPollerTest {
  private fun poller(iroh: IrohTransport) =
    BackgroundPoller(
      iroh,
      PollTestClock(),
      { addr, open ->
        BridgeClient(
          Endpoint.iroh(addr),
          IrohHttpClients(iroh, OkHttpClient()).pollClientFor(addr, open),
        )
      },
      { BridgeClient(it) },
    )

  private fun sessionAndAgents(server: MockWebServer) {
    server.enqueue(
      MockResponse.Builder()
        .body(
          """{"event":"paired","machine_id":"$machine","protocol":1,"plugin":"cappuccino-bridge"}"""
        )
        .build()
    )
    server.enqueue(
      MockResponse.Builder()
        .body("""{"event":"agents","machine_id":"$machine","agents":[]}""")
        .build()
    )
  }

  private fun irohEndpoint() = Endpoint.iroh(pollAddr)

  @Test
  fun irohPollDialsOneConnectionServesBothRequestsAndClosesIt() = runBlocking {
    MockWebServer().use { server ->
      sessionAndAgents(server)
      server.start()
      val iroh = PollRecorderIroh(server.port)
      poller(iroh).poll(irohEndpoint()) { bridge ->
        bridge.verify(machine)
        bridge.agents(machine)
      }
      assertEquals(1, iroh.polls.size)
      assertEquals(2, iroh.polls.single().streamsOpened)
      assertEquals(1, iroh.polls.single().closes)
      assertEquals("/api/session", server.takeRequest().url.encodedPath)
      assertEquals("/api/agents", server.takeRequest().url.encodedPath)
      // The poll never uses the shared connection cache or the endpoint lifecycle.
      assertEquals(0, iroh.sharedOpens)
      assertEquals(0, iroh.drops)
      assertEquals(0, iroh.stops)
    }
  }

  @Test
  fun eachPollDialsAndClosesItsOwnConnection() = runBlocking {
    MockWebServer().use { server ->
      sessionAndAgents(server)
      sessionAndAgents(server)
      server.start()
      val iroh = PollRecorderIroh(server.port)
      val poller = poller(iroh)
      repeat(2) {
        poller.poll(irohEndpoint()) { bridge ->
          bridge.verify(machine)
          bridge.agents(machine)
        }
      }
      assertEquals(2, iroh.polls.size)
      assertTrue(iroh.polls[0] !== iroh.polls[1])
      iroh.polls.forEach { assertEquals(1, it.closes) }
    }
  }

  @Test
  fun pollLeavesTheForegroundConnectionAloneWhileItRuns() = runBlocking {
    MockWebServer().use { server ->
      sessionAndAgents(server)
      server.start()
      val iroh = PollRecorderIroh(server.port)
      // The foreground holds a connection on the shared cache before the poll starts.
      val foreground = iroh.openStream(pollAddr)
      val poll = poller(iroh)
      poll.poll(irohEndpoint()) { bridge -> bridge.verify(machine) }
      foreground.release()
      // Only the foreground opened on the shared path; the poll's close never reaches it.
      assertEquals(1, iroh.sharedOpens)
      assertEquals(0, iroh.drops)
      assertEquals(0, iroh.stops)
      assertEquals(1, iroh.polls.single().closes)
    }
  }

  @Test
  fun dualPollFallsBackToTailnetWhenIrohFailsAndStillClosesTheConnection() = runBlocking {
    MockWebServer().use { server ->
      sessionAndAgents(server)
      server.start()
      val iroh = PollRecorderIroh(server.port, pollFails = true)
      val dual =
        Endpoint.parse(server.url("/").toString(), allowLocalHttp = true).withIroh(pollAddr)
      val verified = poller(iroh).poll(dual) { bridge -> bridge.verify(machine) }
      assertEquals(machine, verified)
      assertEquals(1, iroh.polls.single().closes)
      assertEquals("/api/session", server.takeRequest().url.encodedPath)
    }
  }

  @Test
  fun irohOnlyPollFailureSurfacesAndStillClosesTheConnection() = runBlocking {
    val iroh = PollRecorderIroh(port = 0, pollFails = true)
    try {
      poller(iroh).poll(irohEndpoint()) { bridge -> bridge.verify(machine) }
      fail("an iroh-only poll with a dead path must throw")
    } catch (expected: IOException) {
      // Expected: no tailnet fallback exists for an iroh-only machine.
    }
    assertEquals(1, iroh.polls.single().closes)
  }

  @Test
  fun tailnetOnlyPollNeverDialsIroh() = runBlocking {
    MockWebServer().use { server ->
      sessionAndAgents(server)
      server.start()
      val iroh = PollRecorderIroh(server.port)
      val tailnet = Endpoint.parse(server.url("/").toString(), allowLocalHttp = true)
      poller(iroh).poll(tailnet) { bridge -> bridge.verify(machine) }
      assertEquals(0, iroh.polls.size)
    }
  }
}
