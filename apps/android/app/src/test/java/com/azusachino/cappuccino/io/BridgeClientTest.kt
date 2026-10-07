package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.MAX_HTTP_BODY_BYTES
import com.azusachino.cappuccino.core.ProtocolException
import com.azusachino.cappuccino.core.StreamEvent
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test

class BridgeClientTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")

  @Test
  fun requestsPairingAndCatalogWithoutMutatingRoutes() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"event":"paired","machine_id":"$machine","protocol":1,"plugin":"azusachino.cappuccino-bridge"}"""
          )
          .build()
      )
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"event":"agents","machine_id":"$machine","agents":[{"machine_id":"$machine","session_id":"unnamed-pane","pane_id":"p1","label":"pi","agent":"pi","status":"idle","working":false,"active_branch":null}]}"""
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      assertEquals(machine, client.verify())
      assertEquals("unnamed-pane", client.agents(machine).single().sessionId)
      assertEquals("/api/session", server.takeRequest().url.encodedPath)
      assertEquals("/api/agents", server.takeRequest().url.encodedPath)
      assertNull(server.takeRequest(10, TimeUnit.MILLISECONDS))
    }
  }

  @Test
  fun websocketEncodesLocatorParsesEventAndCancelsOnFlowClose() = runBlocking {
    MockWebServer().use { server ->
      val socketEnded = CountDownLatch(1)
      server.enqueue(
        MockResponse.Builder()
          .webSocketUpgrade(
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"event":"stream_open","session_id":"a &雪","generation":0}""")
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                socketEnded.countDown()
              }

              override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: okhttp3.Response?,
              ) {
                socketEnded.countDown()
              }
            }
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val event = client.stream("a &雪").first()
      assertEquals("a &雪", (event as StreamEvent.Open).sessionId)
      assertTrue(server.takeRequest().url.encodedQuery!!.contains("session=a%20%26%E9%9B%AA"))
      assertTrue("cancelled flow left the WebSocket open", socketEnded.await(5, TimeUnit.SECONDS))
    }
  }

  @Test
  fun remoteGracefulWebSocketCloseTerminatesBothPeers() = runBlocking {
    MockWebServer().use { server ->
      val peerClosed = CountDownLatch(1)
      server.enqueue(
        MockResponse.Builder()
          .webSocketUpgrade(
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"event":"stream_open","session_id":"session","generation":1}""")
                webSocket.close(1000, "雪".repeat(41))
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                peerClosed.countDown()
              }

              override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: okhttp3.Response?,
              ) {
                peerClosed.countDown()
              }
            }
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val completed = CountDownLatch(1)
      val collector =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).async {
          try {
            client.stream("session").collect {}
            null
          } catch (error: Exception) {
            error
          } finally {
            completed.countDown()
          }
        }
      try {
        assertNotNull("WebSocket request was not received", server.takeRequest(5, TimeUnit.SECONDS))
        assertTrue("remote close left stream suspended", completed.await(5, TimeUnit.SECONDS))
        assertTrue("server close handshake did not finish", peerClosed.await(5, TimeUnit.SECONDS))
        val closeError = collector.await() as BridgeWebSocketClosedException
        assertEquals(1000, closeError.code)
        assertEquals("雪".repeat(41), closeError.reason)
        assertEquals(123, closeError.reason.toByteArray(Charsets.UTF_8).size)
      } finally {
        collector.cancel()
      }
    }
  }

  @Test
  fun emptyPeerClosePreservesNoStatusCodeAndCompletesHandshake() = runBlocking {
    RawEmptyClosePeer().use { peer ->
      val client =
        BridgeClient(Endpoint.parse("http://127.0.0.1:${peer.port}/", allowLocalHttp = true))
      val result =
        kotlinx.coroutines.withTimeout(6_000) {
          try {
            client.stream("session").collect {}
            fail("remote close must terminate stream")
            null
          } catch (error: BridgeWebSocketClosedException) {
            error
          }
        }
      assertEquals(1005, result!!.code)
      assertEquals("", result.reason)
      peer.awaitAcknowledgement()
    }
  }

  @Test
  fun cleanHttpEndOfFileWithNoBodyIsAProtocolError() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("").build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      try {
        client.verify()
        fail("empty response must not be accepted as a pairing response")
      } catch (expected: ProtocolException) {
        assertEquals("Malformed JSON", expected.message)
      }
    }
  }

  @Test
  fun abruptWebSocketDisconnectTerminatesTheStream() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .webSocketUpgrade(
            object : WebSocketListener() {
              override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                webSocket.send("""{"event":"stream_open","session_id":"session","generation":1}""")
                webSocket.cancel()
              }
            }
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      try {
        client.stream("session").collect {}
        fail("abrupt disconnect must fail the stream")
      } catch (expected: java.io.IOException) {
        assertNotEquals("Bridge stream closed", expected.message)
      }
    }
  }

  @Test
  fun nonSuccessHttpStatusIsReported() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().code(503).body("unavailable").build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      try {
        client.verify()
        fail("non-success response should fail")
      } catch (expected: java.io.IOException) {
        assertTrue(expected.message!!.contains("503"))
      }
    }
  }

  @Test
  fun cancellingHttpRequestCancelsTheSuspendingCall() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("delayed").bodyDelay(4, TimeUnit.SECONDS).build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val request = async(Dispatchers.IO) { client.verify() }
      assertNotNull("request was not sent", server.takeRequest(2, TimeUnit.SECONDS))
      request.cancelAndJoin()
      assertTrue("cancel did not reach request job", request.isCancelled)
    }
  }

  @Test
  fun oversizedHttpBodyFailsDuringRead() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("x".repeat(MAX_HTTP_BODY_BYTES + 1)).build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      try {
        client.verify()
        fail("oversized body should fail")
      } catch (expected: ProtocolException) {
        assertTrue(expected.message!!.contains("exceeds"))
      }
    }
  }

  @Test
  fun fetchesConversationTurns() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"session_id":"s-1","source":"canonical_log","turns":[{"id":"t1","role":"user","text":"hello"}]}"""
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val turns = client.conversation("s-1")
      assertEquals(1, turns.size)
      assertEquals("t1", turns[0].id)
      val req = server.takeRequest()
      assertEquals("/api/agents/s-1/conversation", req.url.encodedPath)
    }
  }

  @Test
  fun submitsPromptAndAnswersPrompt() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("""{"status":"delivered"}""").build())
      server.enqueue(MockResponse.Builder().body("""{"status":"delivered"}""").build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))

      client.submitPrompt("s-1", "hello world")
      val req1 = server.takeRequest()
      assertEquals("/api/agents/s-1/prompt", req1.url.encodedPath)
      assertEquals("POST", req1.method)
      assertTrue(req1.body.readUtf8().contains(""""text":"hello world""""))

      client.answerPrompt("s-1", "p-1", 0, "opt-1", "select_option")
      val req2 = server.takeRequest()
      assertEquals("/api/agents/s-1/prompt", req2.url.encodedPath)
      assertEquals("POST", req2.method)
      val body2 = req2.body.readUtf8()
      assertTrue(body2.contains(""""prompt_id":"p-1""""))
      assertTrue(body2.contains(""""option_index":0"""))
    }
  }
}
