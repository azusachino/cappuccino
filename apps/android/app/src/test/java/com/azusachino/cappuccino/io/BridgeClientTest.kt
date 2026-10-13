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
  fun transparentlyNegotiatesAndDecodesGzipWithoutChangingIdentity() = runBlocking {
    MockWebServer().use { server ->
      val payload =
        """{"event":"paired","machine_id":"$machine","protocol":1,"plugin":"cappuccino-bridge"}"""
      val bytes = java.io.ByteArrayOutputStream()
      java.util.zip.GZIPOutputStream(bytes).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
      server.enqueue(
        MockResponse.Builder()
          .addHeader("Content-Encoding", "gzip")
          .addHeader("Content-Type", "application/json")
          .body(okio.Buffer().write(bytes.toByteArray()))
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      assertEquals(machine, client.verify())
      assertEquals("gzip", server.takeRequest().headers["Accept-Encoding"])
    }
  }

  @Test
  fun gzipCannotBypassTheDecompressedBodyLimit() = runBlocking {
    MockWebServer().use { server ->
      val bytes = java.io.ByteArrayOutputStream()
      java.util.zip.GZIPOutputStream(bytes).use {
        it.write(" ".repeat(MAX_HTTP_BODY_BYTES + 1).toByteArray())
      }
      server.enqueue(
        MockResponse.Builder()
          .addHeader("Content-Encoding", "gzip")
          .body(okio.Buffer().write(bytes.toByteArray()))
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      try {
        client.verify()
        fail("Decompressed limit must be enforced")
      } catch (error: ProtocolException) {
        assertEquals("HTTP body exceeds local limit", error.message)
      }
    }
  }

  @Test
  fun requestsPairingAndCatalogWithoutMutatingRoutes() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"event":"paired","machine_id":"$machine","protocol":1,"plugin":"cappuccino-bridge"}"""
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
  fun fetchesCanonicalIdentityWithoutTreatingLocatorAsIdentity() = runBlocking {
    MockWebServer().use { server ->
      val id = "pi:${"a".repeat(64)}"
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"session_id":"chosen","source":"canonical_log","machine_id":"11111111-1111-4111-8111-111111111111","conversation_id":"$id","turns":[]}"""
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val snapshot = client.conversationSnapshot("chosen")
      assertEquals(id, snapshot.conversationId)
      assertEquals("canonical_log", snapshot.source)
      assertEquals("/api/agents/chosen/conversation", server.takeRequest().url.encodedPath)
    }
  }

  @Test
  fun rejectsWrongLocatorMalformedIdentityAndScrollbackIdentityClaims() = runBlocking {
    MockWebServer().use { server ->
      val id = "pi:${"a".repeat(64)}"
      val bodies =
        listOf(
          """{"session_id":"other","source":"canonical_log","machine_id":"11111111-1111-4111-8111-111111111111","conversation_id":"$id","turns":[]}""",
          """{"session_id":"chosen","source":"canonical_log","conversation_id":"$id","turns":[]}""",
          """{"session_id":"chosen","source":"canonical_log","machine_id":"11111111-1111-4111-8111-111111111111","conversation_id":"pane","turns":[]}""",
          """{"session_id":"chosen","source":"scrollback","machine_id":"11111111-1111-4111-8111-111111111111","conversation_id":"$id","turns":[]}""",
          """{"session_id":"chosen","source":"canonical_log","machine_id":"11111111-1111-4111-8111-111111111111","conversation_id":123,"turns":[]}""",
        )
      bodies.forEach { server.enqueue(MockResponse.Builder().body(it).build()) }
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      bodies.forEach { _ ->
        try {
          client.conversationSnapshot("chosen")
          fail("Invalid ownership/identity must fail closed")
        } catch (_: ProtocolException) {}
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
      assertTrue(req1.body?.utf8()?.contains(""""text":"hello world"""") == true)
      assertTrue(req1.body?.utf8()?.contains("confirmed") == false)

      server.enqueue(MockResponse.Builder().body("""{"status":"delivered"}""").build())
      client.submitPrompt("s-1", "/model\topus", confirmed = true)
      val command = server.takeRequest().body?.utf8() ?: ""
      assertTrue(command, command.contains(""""text":"/model\topus""""))
      assertTrue(command, command.contains(""""confirmed":true"""))

      client.answerPrompt("s-1", "p-1", 0, "opt-1", "select_option")
      val req2 = server.takeRequest()
      assertEquals("/api/agents/s-1/prompt", req2.url.encodedPath)
      assertEquals("POST", req2.method)
      val body2 = req2.body?.utf8() ?: ""
      assertTrue(body2.contains(""""prompt_id":"p-1""""))
      assertTrue(body2.contains(""""option_index":0"""))
    }
  }

  @Test
  fun conversationCarriesSessionStatsWhenTheBridgeSendsThem() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"session_id":"s-1","source":"canonical_log","turns":[],"stats":{"model":"glm","thinking_level":"low","context_tokens":1100,"cache_percent":60}}"""
          )
          .build()
      )
      server.enqueue(
        MockResponse.Builder()
          .body("""{"session_id":"s-1","source":"canonical_log","turns":[]}""")
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      assertEquals(
        SessionStats("glm", "low", 1100, null, 60),
        client.conversationSnapshot("s-1").stats,
      )
      assertNull(client.conversationSnapshot("s-1").stats)
    }
  }

  @Test
  fun answersEveryQuestionInOneStructuredSubmission() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(MockResponse.Builder().body("""{"event":"question_answered"}""").build())
      server.enqueue(
        MockResponse.Builder()
          .code(409)
          .body("""{"event":"error","code":"stale_prompt","message":"answer it in the terminal"}""")
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val answers =
        listOf(
          com.azusachino.cappuccino.core.QuestionAnswer(listOf(1), null),
          com.azusachino.cappuccino.core.QuestionAnswer(listOf(0, 2), "a \"bird\""),
        )
      client.answerQuestions("s-1", "q-toolu_1", answers)
      val request = server.takeRequest()
      assertEquals("/api/agents/s-1/prompt", request.url.encodedPath)
      assertEquals(
        """{"type":"answer_question","prompt_id":"q-toolu_1","answers":[{"selected":[1]},{"selected":[0,2],"other":"a \"bird\""}]}""",
        request.body?.utf8(),
      )
      // The bridge's reason reaches the owner, not just the status.
      val refused = runCatching { client.answerQuestions("s-1", "q-toolu_1", answers) }
      assertEquals("answer it in the terminal", refused.exceptionOrNull()?.message)
    }
  }

  @Test
  fun controlsAgentOnlyWhenTheBridgeAdvertisesIt() = runBlocking {
    MockWebServer().use { server ->
      val session =
        """{"event":"paired","machine_id":"%s","protocol":1,"plugin":"cappuccino-bridge","capabilities":{%s}}"""
      val machine = UUID.randomUUID()
      server.enqueue(
        MockResponse.Builder().body(session.format(machine, """"agent_control":false""")).build()
      )
      server.enqueue(
        MockResponse.Builder().body(session.format(machine, """"agent_control":true""")).build()
      )
      server.enqueue(MockResponse.Builder().body("""{"event":"control_sent"}""").build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))

      client.verify()
      try {
        client.controlAgent("s-1", "interrupt")
        fail("a bridge that declines controls must not receive them")
      } catch (_: ProtocolException) {}
      server.takeRequest()

      client.verify()
      server.takeRequest()
      client.controlAgent("s-1", "interrupt")
      val req = server.takeRequest()
      assertEquals("/api/agents/s-1/control", req.url.encodedPath)
      assertEquals("POST", req.method)
      assertEquals("""{"action":"interrupt"}""", req.body?.utf8())
    }
  }
}
