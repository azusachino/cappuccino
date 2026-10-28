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
        .newCall(Request.Builder().url("http://iroh.invalid/api/session").build())
        .execute()
        .use { response -> assertEquals("{\"ok\":true}", response.body.string()) }
      assertEquals("/api/session", server.takeRequest().url.encodedPath)
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
            Request.Builder().url("http://iroh.invalid/api/stream").build(),
            object : WebSocketListener() {
              override fun onMessage(webSocket: WebSocket, text: String) {
                message = text
                received.countDown()
                webSocket.close(1000, "done")
              }
            },
          )
      assertEquals(true, received.await(5, TimeUnit.SECONDS))
      assertEquals("from-bridge", message)
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
