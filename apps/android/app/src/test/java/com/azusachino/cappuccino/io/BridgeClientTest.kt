package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.MAX_HTTP_BODY_BYTES
import com.azusachino.cappuccino.core.MutationBinding
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
import kotlinx.serialization.json.jsonObject
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.*
import org.junit.Test

class BridgeClientTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val binding =
    requireNotNull(
      MutationBinding.from(
        AgentRow(
          machine,
          "s-1",
          "pane-1",
          "fixture",
          "pi",
          "idle",
          false,
          null,
          nativeSessionRef = "native-ref:${"a".repeat(64)}",
        )
      )
    )

  private fun enqueueMutation(server: MockWebServer, response: MockResponse) {
    server.enqueue(advertisement())
    server.enqueue(response)
  }

  private fun advertisement(flags: String = "\"mutation_binding\":true") =
    MockResponse.Builder()
      .body(
        """{"event":"paired","machine_id":"$machine","protocol":1,"plugin":"cappuccino-bridge","capabilities":{$flags}}"""
      )
      .build()

  private fun takeBoundPost(server: MockWebServer): mockwebserver3.RecordedRequest {
    val handshake = server.takeRequest()
    assertEquals("GET", handshake.method)
    assertEquals("/api/session", handshake.url.encodedPath)
    val request = server.takeRequest()
    assertEquals("POST", request.method)
    val json = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8()).jsonObject
    val expected =
      kotlinx.serialization.json.Json.parseToJsonElement(
        """{"machine_id":"$machine","pane_id":"${binding.paneId}","native_session_ref":"${binding.nativeSessionRef}"}"""
      )
    assertEquals(expected, json["expected_session"])
    return request
  }

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
  fun slowConsumerOverflowClosesTheStreamInsteadOfDroppingEvents() = runBlocking {
    MockWebServer().use { server ->
      val firstEvent = kotlinx.coroutines.CompletableDeferred<Unit>()
      val releaseConsumer = kotlinx.coroutines.CompletableDeferred<Unit>()
      val peerClosed = kotlinx.coroutines.CompletableDeferred<Unit>()
      server.enqueue(
        MockResponse.Builder()
          .webSocketUpgrade(
            object : WebSocketListener() {
              override fun onFailure(
                webSocket: WebSocket,
                t: Throwable,
                response: okhttp3.Response?,
              ) {
                peerClosed.complete(Unit)
              }

              override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                peerClosed.complete(Unit)
              }

              override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                // The collector blocks on its first event while this fills the bounded buffer.
                repeat(200) {
                  webSocket.send(
                    """{"event":"agent_status","session_id":"session","state":"working","detail":null}"""
                  )
                }
              }
            }
          )
          .build()
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val collector = async {
        runCatching {
          client.stream("session").collect {
            firstEvent.complete(Unit)
            releaseConsumer.await()
          }
        }
          .exceptionOrNull()
      }
      try {
        kotlinx.coroutines.withTimeout(5_000) {
          firstEvent.await()
          peerClosed.await()
        }
        releaseConsumer.complete(Unit)
        val failure = kotlinx.coroutines.withTimeout(5_000) { collector.await() }
        assertTrue(
          "expected a typed overflow failure, got $failure",
          failure is BridgeStreamOverflowException,
        )
      } finally {
        releaseConsumer.complete(Unit)
        collector.cancelAndJoin()
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
      enqueueMutation(server, MockResponse.Builder().body("""{"status":"delivered"}""").build())
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))

      client.submitPrompt("s-1", "hello world", binding = binding)
      val req1 = takeBoundPost(server)
      assertEquals("/api/agents/s-1/prompt", req1.url.encodedPath)
      assertEquals("POST", req1.method)
      val body1 = req1.body?.utf8().orEmpty()
      assertTrue(body1.contains(""""text":"hello world""""))
      assertFalse(body1.contains("confirmed"))

      enqueueMutation(server, MockResponse.Builder().body("""{"status":"delivered"}""").build())
      client.submitPrompt("s-1", "/model\topus", confirmed = true, binding = binding)
      val command = takeBoundPost(server).body?.utf8() ?: ""
      assertTrue(command, command.contains(""""text":"/model\topus""""))
      assertTrue(command, command.contains(""""confirmed":true"""))

      enqueueMutation(server, MockResponse.Builder().body("""{"status":"delivered"}""").build())
      client.answerPrompt("s-1", "p-1", 0, "opt-1", "select_option", binding)
      val req2 = takeBoundPost(server)
      assertEquals("/api/agents/s-1/prompt", req2.url.encodedPath)
      assertEquals("POST", req2.method)
      val body2 = req2.body?.utf8() ?: ""
      assertTrue(body2.contains(""""prompt_id":"p-1""""))
      assertTrue(body2.contains(""""option_index":0"""))
    }
  }

  @Test
  fun promptRejectsOnlyClientErrorsAndLeavesGatewayFailureAmbiguous() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      enqueueMutation(
        server,
        MockResponse.Builder().code(409).body("""{"code":"confirm_command"}""").build(),
      )
      val rejection = runCatching {
        client.submitPrompt("s-1", "/new", binding = binding)
      }
        .exceptionOrNull()
      assertTrue(rejection is BridgeHttpRejectionException)
      enqueueMutation(
        server,
        MockResponse.Builder().code(502).body("""{"code":"herdr_error"}""").build(),
      )
      val gatewayFailure = runCatching {
        client.submitPrompt("s-1", "possibly written", binding = binding)
      }
        .exceptionOrNull()
      assertTrue(gatewayFailure is java.io.IOException)
      assertFalse(gatewayFailure is BridgeHttpRejectionException)
      assertEquals("/api/agents/s-1/prompt", takeBoundPost(server).url.encodedPath)
      assertEquals("/api/agents/s-1/prompt", takeBoundPost(server).url.encodedPath)
    }
  }

  @Test
  fun conversationCarriesSessionStatsWhenTheBridgeSendsThem() = runBlocking {
    MockWebServer().use { server ->
      server.enqueue(
        MockResponse.Builder()
          .body(
            """{"session_id":"s-1","source":"canonical_log","turns":[],"stats":{"model":"glm","thinking_level":"low","context_tokens":1100,"cache_percent":60,"rate_limits":{"provider":"anthropic","plan_tier":null,"windows":[{"window":"5h","used_percent":2,"resets_in":null},{"window":"7d","used_percent":81,"resets_in":"17h43m"}],"status":"warning"}}}"""
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
        SessionStats(
          "glm",
          "low",
          1100,
          null,
          60,
          RateLimitStats(
            provider = "anthropic",
            planTier = null,
            windows =
              listOf(
                RateLimitWindow("5h", 2, null, null),
                RateLimitWindow("7d", 81, null, "17h43m"),
              ),
            status = "warning",
          ),
        ),
        client.conversationSnapshot("s-1").stats,
      )
      assertNull(client.conversationSnapshot("s-1").stats)
    }
  }

  @Test
  fun answersEveryQuestionInOneStructuredSubmission() = runBlocking {
    MockWebServer().use { server ->
      enqueueMutation(
        server,
        MockResponse.Builder().body("""{"event":"question_answered"}""").build(),
      )
      enqueueMutation(
        server,
        MockResponse.Builder()
          .code(409)
          .body("""{"event":"error","code":"stale_prompt","message":"answer it in the terminal"}""")
          .build(),
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val answers =
        listOf(
          com.azusachino.cappuccino.core.QuestionAnswer(listOf(1), null),
          com.azusachino.cappuccino.core.QuestionAnswer(listOf(0, 2), "a \"bird\""),
        )
      client.answerQuestions("s-1", "q-toolu_1", answers, binding)
      val request = takeBoundPost(server)
      assertEquals("/api/agents/s-1/prompt", request.url.encodedPath)
      assertEquals(
        """{"expected_session":{"machine_id":"$machine","pane_id":"${binding.paneId}","native_session_ref":"${binding.nativeSessionRef}"},"type":"answer_question","prompt_id":"q-toolu_1","answers":[{"selected":[1]},{"selected":[0,2],"other":"a \"bird\""}]}""",
        request.body?.utf8(),
      )
      // The bridge's reason reaches the owner, not just the status.
      val refused = runCatching { client.answerQuestions("s-1", "q-toolu_1", answers, binding) }
      assertEquals("answer it in the terminal", refused.exceptionOrNull()?.message)
    }
  }

  @Test
  fun unsupportedBindingCapabilityBlocksEveryDirectMutationBeforePost() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      val actions: List<suspend () -> Unit> =
        listOf(
          { client.submitPrompt("s-1", "synthetic", binding = binding) },
          { client.controlAgent("s-1", "interrupt", binding) },
          { client.answerPrompt("s-1", "p-1", null, null, "cancel", binding) },
          { client.answerQuestions("s-1", "p-1", emptyList(), binding) },
        )
      for (flags in listOf("", "\"mutation_binding\":false")) {
        for (action in actions) {
          server.enqueue(advertisement(flags))
          assertTrue(
            runCatching { action() }.exceptionOrNull() is BridgeMutationUnsupportedException
          )
          assertEquals("GET", server.takeRequest().method)
        }
      }
      assertEquals(8, server.requestCount)
    }
  }

  @Test
  fun sharedTypedErrorsRetainRejectionVersusUncertainty() = runBlocking {
    val fixture =
      kotlinx.serialization.json.Json.parseToJsonElement(
          java.io.File("../../../fixtures/mutation-session-binding.json").readText()
        )
        .jsonObject
    val errors = fixture.getValue("errors") as kotlinx.serialization.json.JsonArray
    MockWebServer().use { server ->
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      for ((index, error) in errors.withIndex()) {
        enqueueMutation(
          server,
          MockResponse.Builder().code(if (index == 0) 409 else 502).body(error.toString()).build(),
        )
        val outcome = runCatching { client.submitPrompt("s-1", "synthetic", binding = binding) }
        val failure = outcome.exceptionOrNull()
        if (index == 0) assertTrue(failure is BridgeStaleSessionException)
        else {
          assertTrue(failure is BridgeMutationOutcomeUnknownException)
          assertFalse(failure is BridgeHttpRejectionException)
        }
        takeBoundPost(server)
      }
      assertEquals(4, server.requestCount)
    }
  }

  @Test
  fun ambiguousPostIsNotRetriedEvenWithRetryEnabledOnInjectedClient() = runBlocking {
    MockWebServer().use { server ->
      enqueueMutation(
        server,
        MockResponse.Builder().onResponseStart(mockwebserver3.SocketEffect.CloseSocket()).build(),
      )
      server.enqueue(MockResponse.Builder().body("""{"event":"prompt_sent"}""").build())
      server.start()
      val client =
        BridgeClient(
          Endpoint.parse(server.url("/").toString(), allowLocalHttp = true),
          BridgeClient.defaultClient().newBuilder().retryOnConnectionFailure(true).build(),
        )
      val outcome = runCatching { client.submitPrompt("s-1", "synthetic", binding = binding) }
      val failure = outcome.exceptionOrNull()
      assertTrue(failure is java.io.IOException)
      assertFalse(failure is BridgeHttpRejectionException)
      takeBoundPost(server)
      assertEquals("exactly one POST, no transport replay", 2, server.requestCount)
    }
  }

  @Test
  fun answerFieldsAreEscapedWithoutChangingTheBinding() = runBlocking {
    MockWebServer().use { server ->
      enqueueMutation(
        server,
        MockResponse.Builder().body("""{"event":"prompt_answered"}""").build(),
      )
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      client.answerPrompt("s-1", "p\"1", 0, "option\"1", "select_option", binding)
      val json =
        kotlinx.serialization.json.Json.parseToJsonElement(takeBoundPost(server).body!!.utf8())
          .jsonObject
      assertEquals(kotlinx.serialization.json.JsonPrimitive("p\"1"), json["prompt_id"])
      assertEquals(kotlinx.serialization.json.JsonPrimitive("option\"1"), json["option_id"])
    }
  }

  @Test
  fun controlsAgentOnlyWhenTheBridgeAdvertisesIt() = runBlocking {
    MockWebServer().use { server ->
      server.start()
      val client = BridgeClient(Endpoint.parse(server.url("/").toString(), allowLocalHttp = true))
      for (flags in
        listOf(
          "\"agent_control\":false,\"mutation_binding\":true",
          "\"agent_control\":true",
          "\"agent_control\":true,\"mutation_binding\":false",
        )) {
        server.enqueue(advertisement(flags))
        val outcome = runCatching { client.controlAgent("s-1", "interrupt", binding) }
        val error = outcome.exceptionOrNull()
        assertTrue(
          "a bridge that declines bound controls must not receive them",
          error is BridgeMutationUnsupportedException,
        )
        assertEquals("GET", server.takeRequest().method)
      }
      assertEquals("read-only compatibility sends no POST", 3, server.requestCount)
      server.enqueue(advertisement("\"agent_control\":true,\"mutation_binding\":true"))
      server.enqueue(MockResponse.Builder().body("""{"event":"control_sent"}""").build())
      client.controlAgent("s-1", "interrupt", binding)
      val req = takeBoundPost(server)
      assertEquals("/api/agents/s-1/control", req.url.encodedPath)
      val json = kotlinx.serialization.json.Json.parseToJsonElement(req.body!!.utf8()).jsonObject
      assertEquals(kotlinx.serialization.json.JsonPrimitive("interrupt"), json["action"])
      assertEquals(2, json.size)
    }
  }
}
