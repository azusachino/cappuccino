package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTP and the WebSocket upgrade through the iroh socket factory, with TCP standing in for iroh.
 */
class IrohHttpTest {
  private val addr = IrohEndpointAddr("bridge-id", null, emptyList())

  private fun client(port: Int) =
    IrohHttpClients(TcpTunnelTransport(port), OkHttpClient()).clientFor(addr)

  @Test
  fun plainHttpRequestRidesTheIrohSocket() {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("{\"ok\":true}").build())
      server.start()
      client(server.port)
        .newCall(Request.Builder().url("http://iroh.invalid/api/v2/handshake").build())
        .execute()
        .use { response -> assertEquals("{\"ok\":true}", response.body.string()) }
      assertEquals("/api/v2/handshake", server.takeRequest().url.encodedPath)
    }
  }

  @Test
  fun websocketUpgradeRidesTheIrohSocket() {
    MockWebServer().use { server ->
      val received = CountDownLatch(1)
      var message = ""
      server.enqueue(
        MockResponse.Builder()
          .webSocketUpgrade(
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("from-bridge")
              }
            }
          )
          .build()
      )
      server.start()
      val socket =
        client(server.port)
          .newWebSocket(
            Request.Builder().url("http://iroh.invalid/api/v2/stream").build(),
            object : WebSocketListener() {
              override fun onMessage(webSocket: WebSocket, text: String) {
                message = text
                received.countDown()
              }
            },
          )
      assertEquals(true, received.await(5, TimeUnit.SECONDS))
      assertEquals("from-bridge", message)
      // Cancel without a close handshake: under load MockWebServer.close() can stall waiting on the
      // server side of one, and the test fails at this `use` (#63).
      socket.cancel()
    }
  }

  @Test
  fun irohEndpointsKeepTheirPlaceholderBaseAndTheirOwnAddress() {
    val endpoint = Endpoint.iroh(addr)
    assertEquals("http://iroh.invalid/", endpoint.base.toString())
    assertEquals(addr, endpoint.iroh)
  }
}

/** Streams are TCP connections to whichever server stands in for the dialled endpoint. */
private class PerEndpointTunnel(private val ports: Map<String, Int>) :
  IrohTransport by TcpTunnelTransport(0) {
  override suspend fun openStream(addr: IrohEndpointAddr, alpn: String): IrohBiStream =
    TcpTunnelTransport(ports.getValue(addr.endpointId)).openStream(addr, alpn)
}

class IrohClientIsolationTest {
  @Test
  fun aRequestForOneMachineNeverRidesAnotherMachinesPooledConnection() {
    MockWebServer().use { a ->
      MockWebServer().use { b ->
        a.enqueue(MockResponse.Builder().body("machine-a").build())
        a.enqueue(MockResponse.Builder().body("machine-a-second").build())
        b.enqueue(MockResponse.Builder().body("machine-b").build())
        a.start()
        b.start()
        val addrA = IrohEndpointAddr("endpoint-a", null, emptyList())
        val addrB = IrohEndpointAddr("endpoint-b", null, emptyList())
        // One shared base client, as MachineTransports builds it from BridgeClient.defaultClient().
        val clients =
          IrohHttpClients(
            PerEndpointTunnel(mapOf("endpoint-a" to a.port, "endpoint-b" to b.port)),
            OkHttpClient(),
          )
        fun body(addr: IrohEndpointAddr) =
          clients
            .clientFor(addr)
            .newCall(Request.Builder().url("http://iroh.invalid/api/v2/handshake").build())
            .execute()
            .use { it.body.string() }
        assertEquals("machine-a", body(addrA))
        // A's connection is idle in the pool now; B must dial its own.
        assertEquals("machine-b", body(addrB))
      }
    }
  }
}
