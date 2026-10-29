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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSource

open class BridgeHttpRejectionException(message: String) : IOException(message), BridgeAnswered

/** The bridge answered with a non-success status that is not a typed rejection. */
class BridgeHttpStatusException(val code: Int, message: String) :
  IOException(message), BridgeAnswered

class BridgeMutationUnsupportedException(message: String) : BridgeHttpRejectionException(message)

class BridgeStaleSessionException(message: String) : BridgeHttpRejectionException(message)

class BridgeMutationOutcomeUnknownException(message: String) : IOException(message), BridgeAnswered

class BridgeWebSocketClosedException(val code: Int, val reason: String) :
  IOException(
    "Bridge stream closed (code=$code${if (reason.isEmpty()) "" else ", reason=$reason"})"
  )

/**
 * The bounded local stream buffer overflowed because the consumer fell behind. The stream is lossy
 * once this happens, so it terminates instead of silently dropping an event; the client recovers by
 * reopening within its normal transport retry budget.
 */
class BridgeStreamOverflowException :
  IOException("Bridge stream events arrived faster than the app consumed them; reopening")

data class ConversationSnapshot(
  val turns: List<ConversationTurn>,
  val source: String,
  val conversationId: String? = null,
  val machineId: UUID? = null,
  val stats: SessionStats? = null,
)

/** A quota window such as 5h or 7d and its current consumption. */
data class RateLimitWindow(
  val window: String,
  val usedPercent: Long,
  val resetsAt: Long? = null,
  val resetsIn: String? = null,
)

/** Upstream or proxy rate limit quotas. */
data class RateLimitStats(
  val provider: String,
  val planTier: String? = null,
  val windows: List<RateLimitWindow> = emptyList(),
  val status: String? = null,
)

/** The session's model, context use, and rate limit quotas. */
data class SessionStats(
  val model: String?,
  val thinkingLevel: String?,
  val contextTokens: Long?,
  val contextWindow: Long?,
  val cachePercent: Long?,
  val rateLimits: RateLimitStats? = null,
)

interface BridgeTransport {
  val bridgeDetails: BridgeDetails?
    get() = null

  val machineName: String?
    get() = null

  suspend fun verify(expectedMachine: UUID? = null): UUID

  suspend fun agents(machine: UUID): List<AgentRow>

  fun stream(sessionId: String): Flow<StreamEvent>

  suspend fun conversation(sessionId: String): List<ConversationTurn> = emptyList()

  suspend fun conversationSnapshot(sessionId: String): ConversationSnapshot =
    ConversationSnapshot(conversation(sessionId), "canonical_log")

  /** Sends [text]; [confirmed] says the owner approved the agent command it starts with. */
  suspend fun submitPrompt(
    sessionId: String,
    text: String,
    confirmed: Boolean = false,
    binding: MutationBinding,
  ) {
    throw BridgeHttpRejectionException("Session-bound prompt input unavailable")
  }

  /** Owner control over a running agent: `interrupt` (Stop) or `new_session`. */
  suspend fun controlAgent(sessionId: String, action: String, binding: MutationBinding) {
    throw BridgeHttpRejectionException("Session-bound controls unavailable")
  }

  /** Answers every question of a pending AskUserQuestion card at once. */
  suspend fun answerQuestions(
    sessionId: String,
    promptId: String,
    answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
    binding: MutationBinding,
  ) {
    throw BridgeHttpRejectionException("Session-bound answers unavailable")
  }

  suspend fun answerPrompt(
    sessionId: String,
    promptId: String,
    optionIndex: Int?,
    optionId: String?,
    action: String?,
    binding: MutationBinding,
  ) {
    throw BridgeHttpRejectionException("Session-bound answers unavailable")
  }
}

class BridgeClient(
  private val endpoint: Endpoint,
  private val client: OkHttpClient = defaultClient(),
) : BridgeTransport {
  override var bridgeDetails: BridgeDetails? = null
    private set

  override var machineName: String? = null
    private set

  override suspend fun verify(expectedMachine: UUID?): UUID =
    withContext(Dispatchers.IO) {
      val json = parseJsonObject(get("api/session"))
      val machine = parsePaired(json)
      if (expectedMachine != null && machine != expectedMachine)
        throw ProtocolException("Machine identity changed")
      val details = parseBridgeDetails(json)
      machineName = json.optionalString("machine_name")?.trim()?.takeIf { it.isNotEmpty() }
      bridgeDetails = details
      machine
    }

  override suspend fun agents(machine: UUID): List<AgentRow> =
    withContext(Dispatchers.IO) { parseAgents(parseJsonObject(get("api/agents")), machine) }

  override suspend fun conversation(sessionId: String): List<ConversationTurn> =
    conversationSnapshot(sessionId).turns

  override suspend fun conversationSnapshot(sessionId: String): ConversationSnapshot =
    withContext(Dispatchers.IO) {
      val json = parseJsonObject(get("api/agents/$sessionId/conversation"))
      if (json.string("session_id") != sessionId)
        throw ProtocolException("Conversation belongs to a different locator")
      val source = json.string("source")
      val identity = json.optionalString("conversation_id")
      val machine = json.optionalString("machine_id")?.let(::uuid)
      if (
        identity != null &&
          (source != "canonical_log" || machine == null || !isCanonicalConversationId(identity))
      )
        throw ProtocolException("Invalid canonical conversation identity")
      val stats =
        json.optionalObject("stats")?.let {
          val rateLimits =
            it.optionalObject("rate_limits")?.let { rl ->
              val windows =
                rl.optionalArray("windows")?.map { w ->
                  RateLimitWindow(
                    window = w.string("window"),
                    usedPercent = w.long("used_percent"),
                    resetsAt = w.optionalLong("resets_at"),
                    resetsIn = w.optionalString("resets_in"),
                  )
                } ?: emptyList()
              RateLimitStats(
                provider = rl.string("provider"),
                planTier = rl.optionalString("plan_tier"),
                windows = windows,
                status = rl.optionalString("status"),
              )
            }
          SessionStats(
            it.optionalString("model"),
            it.optionalString("thinking_level"),
            it.optionalLong("context_tokens"),
            it.optionalLong("context_window"),
            it.optionalLong("cache_percent"),
            rateLimits,
          )
        }
      ConversationSnapshot(parseConversationResponse(json), source, identity, machine, stats)
    }

  override suspend fun submitPrompt(
    sessionId: String,
    text: String,
    confirmed: Boolean,
    binding: MutationBinding,
  ): Unit =
    withContext(Dispatchers.IO) {
      requireMutation(binding, "prompt_input")
      val body =
        kotlinx.serialization.json.buildJsonObject {
          put("expected_session", bindingJson(binding))
          put("type", kotlinx.serialization.json.JsonPrimitive("prompt"))
          put("text", kotlinx.serialization.json.JsonPrimitive(text))
          if (confirmed) put("confirmed", kotlinx.serialization.json.JsonPrimitive(true))
        }
      post("api/agents/$sessionId/prompt", body.toString())
    }

  override suspend fun controlAgent(
    sessionId: String,
    action: String,
    binding: MutationBinding,
  ): Unit =
    withContext(Dispatchers.IO) {
      requireMutation(binding, "agent_control")
      val body =
        kotlinx.serialization.json.buildJsonObject {
          put("action", kotlinx.serialization.json.JsonPrimitive(action))
          put("expected_session", bindingJson(binding))
        }
      post("api/agents/$sessionId/control", body.toString())
    }

  override suspend fun answerQuestions(
    sessionId: String,
    promptId: String,
    answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
    binding: MutationBinding,
  ): Unit =
    withContext(Dispatchers.IO) {
      requireMutation(binding, "prompt_answers")
      val body =
        kotlinx.serialization.json.buildJsonObject {
          put("expected_session", bindingJson(binding))
          put("type", kotlinx.serialization.json.JsonPrimitive("answer_question"))
          put("prompt_id", kotlinx.serialization.json.JsonPrimitive(promptId))
          put(
            "answers",
            kotlinx.serialization.json.JsonArray(
              answers.map { answer ->
                kotlinx.serialization.json.buildJsonObject {
                  put(
                    "selected",
                    kotlinx.serialization.json.JsonArray(
                      answer.selected.map { kotlinx.serialization.json.JsonPrimitive(it) }
                    ),
                  )
                  answer.other?.let { put("other", kotlinx.serialization.json.JsonPrimitive(it)) }
                }
              }
            ),
          )
        }
      post("api/agents/$sessionId/prompt", body.toString())
    }

  override suspend fun answerPrompt(
    sessionId: String,
    promptId: String,
    optionIndex: Int?,
    optionId: String?,
    action: String?,
    binding: MutationBinding,
  ): Unit =
    withContext(Dispatchers.IO) {
      requireMutation(binding, "prompt_answers")
      val body =
        kotlinx.serialization.json.buildJsonObject {
          put("type", kotlinx.serialization.json.JsonPrimitive("answer_prompt"))
          put("prompt_id", kotlinx.serialization.json.JsonPrimitive(promptId))
          put("expected_session", bindingJson(binding))
          optionIndex?.let { put("option_index", kotlinx.serialization.json.JsonPrimitive(it)) }
          optionId?.let { put("option_id", kotlinx.serialization.json.JsonPrimitive(it)) }
          action?.let { put("action", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
      post("api/agents/$sessionId/prompt", body.toString())
    }

  private suspend fun requireMutation(binding: MutationBinding, capability: String) {
    verify(binding.machineId)
    val flags = bridgeDetails?.capabilities
    if (flags?.get("mutation_binding") != true || flags[capability] == false)
      throw BridgeMutationUnsupportedException("Session-bound actions unsupported by this bridge")
  }

  private fun bindingJson(binding: MutationBinding) =
    kotlinx.serialization.json.buildJsonObject {
      put("machine_id", kotlinx.serialization.json.JsonPrimitive(binding.machineId.toString()))
      put("pane_id", kotlinx.serialization.json.JsonPrimitive(binding.paneId))
      put("native_session_ref", kotlinx.serialization.json.JsonPrimitive(binding.nativeSessionRef))
    }

  override fun stream(sessionId: String): Flow<StreamEvent> = callbackFlow {
    // callbackFlow's default buffer (64) is the only local buffering; there is no unbounded
    // queue. An overflow closes the stream with a typed error rather than dropping an event.
    val request = Request.Builder().url(endpoint.stream(sessionId).toString()).build()
    val socket =
      client.newWebSocket(
        request,
        object : WebSocketListener() {
          override fun onMessage(webSocket: WebSocket, text: String) {
            try {
              validateWebSocketMessage(text)
              val sent = trySend(parseStreamEvent(parseJsonObject(text)))
              if (sent.isFailure) {
                // A dropped event is silent corruption; fail the stream into recovery instead.
                close(BridgeStreamOverflowException())
                webSocket.cancel()
              }
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
              if (!it.isSuccessful)
                throw BridgeHttpStatusException(it.code, "Bridge returned HTTP ${it.code}")
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

  private suspend fun post(path: String, jsonBody: String): String =
    suspendCancellableCoroutine { continuation ->
      val body = jsonBody.toRequestBody("application/json".toMediaType())
      val request = Request.Builder().url(endpoint.route(path).toString()).post(body).build()
      // An ambiguous POST must not be retried by OkHttp or followed to another endpoint.
      val call =
        client
          .newBuilder()
          .retryOnConnectionFailure(false)
          .followRedirects(false)
          .followSslRedirects(false)
          .build()
          .newCall(request)
      continuation.invokeOnCancellation { call.cancel() }
      call.enqueue(
        object : okhttp3.Callback {
          override fun onFailure(call: okhttp3.Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
          }

          override fun onResponse(call: okhttp3.Call, response: Response) {
            response.use {
              try {
                val text = readBounded(it.body.source(), MAX_HTTP_BODY_BYTES)
                // The bridge's error envelope says why ("claude is working; stop it first").
                if (!it.isSuccessful) {
                  val error = runCatching { parseJsonObject(text) }.getOrNull()
                  val message =
                    error?.optionalString("message") ?: "Bridge returned HTTP ${it.code}"
                  val code = error?.optionalString("code")
                  if (it.code == 409 && code == "stale_session")
                    throw BridgeStaleSessionException(message)
                  if (it.code == 502 && code == "mutation_outcome_unknown")
                    throw BridgeMutationOutcomeUnknownException(message)
                  if (it.code in 400..499) throw BridgeHttpRejectionException(message)
                  throw BridgeHttpStatusException(it.code, message)
                }
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
