package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSource

class BridgeWebSocketClosedException(val code: Int, val reason: String) :
  IOException(
    "Bridge stream closed (code=$code${if (reason.isEmpty()) "" else ", reason=$reason"})"
  )

interface BridgeTransport {
  suspend fun verify(expectedMachine: UUID? = null): UUID

  suspend fun agents(machine: UUID): List<AgentRow>

  fun stream(sessionId: String): Flow<StreamEvent>
}

class BridgeClient(
  private val endpoint: Endpoint,
  private val client: OkHttpClient = defaultClient(),
) : BridgeTransport {
  override suspend fun verify(expectedMachine: UUID?): UUID =
    withContext(Dispatchers.IO) {
      val json = get("api/session")
      val machine = parsePaired(parseJsonObject(json))
      if (expectedMachine != null && machine != expectedMachine)
        throw ProtocolException("Machine identity changed")
      machine
    }

  override suspend fun agents(machine: UUID): List<AgentRow> =
    withContext(Dispatchers.IO) { parseAgents(parseJsonObject(get("api/agents")), machine) }

  override fun stream(sessionId: String): Flow<StreamEvent> = callbackFlow {
    val request = Request.Builder().url(endpoint.stream(sessionId).toString()).build()
    val socket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            try {
              validateWebSocketMessage(text)
              trySend(parseStreamEvent(parseJsonObject(text)))
            } catch (error: Exception) {
              close(error)
              webSocket.cancel()
            }
          }

          override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            close(t)
          }

          override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            val responseCode =
              if (code in 1000..4999 && code !in setOf(1004, 1005, 1006, 1015)) code else 1000
            webSocket.close(responseCode, reason)
          }

          override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            close(BridgeWebSocketClosedException(code, reason))
          }
        },
      )
    awaitClose { socket.cancel() }
  }

  private suspend fun get(path: String): String = suspendCancellableCoroutine { continuation ->
    val request = Request.Builder().url(endpoint.route(path).toString()).get().build()
    val call = client.newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
      object : okhttp3.Callback {
        override fun onFailure(call: okhttp3.Call, e: IOException) {
          if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: okhttp3.Call, response: Response) {
          response.use {
            try {
              if (!it.isSuccessful) throw IOException("Bridge returned HTTP ${it.code}")
              val text = readBounded(it.body.source(), MAX_HTTP_BODY_BYTES)
              if (continuation.isActive) continuation.resume(text)
            } catch (error: Exception) {
              if (continuation.isActive) continuation.resumeWithException(error)
            }
          }
        }
      }
    )
  }

  companion object {
    fun defaultClient(): OkHttpClient =
      OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .cache(null)
        .build()
  }
}

private fun readBounded(source: BufferedSource, maxBytes: Int): String {
  val buffer = okio.Buffer()
  var total = 0L
  while (true) {
    val read = source.read(buffer, minOf(8192L, maxBytes.toLong() - total + 1))
    if (read == -1L) break
    total += read
    if (total > maxBytes) throw ProtocolException("HTTP body exceeds local limit")
  }
  return buffer.readUtf8()
}
