package com.azusachino.cappuccino.core

import java.net.URI
import java.util.UUID

const val MAX_HTTP_BODY_BYTES = 2 * 1024 * 1024
const val MAX_WS_MESSAGE_BYTES = 1024 * 1024
const val MAX_OUTPUT_ROWS = 2_000
const val MAX_OUTPUT_UTF8_BYTES = 4 * 1024 * 1024
const val BRIDGE_PLUGIN_ID = "azusachino.cappuccino-bridge"

class StreamRetryBudget(private val maximumRetries: Int = 4) {
  private var retriesUsed = 0

  fun nextDelayMillis(): Long? {
    if (retriesUsed >= maximumRetries) return null
    return longArrayOf(1_000L, 2_000L, 4_000L, 8_000L)[retriesUsed++]
  }
}

class ProtocolException(message: String) : Exception(message)

class Endpoint private constructor(val base: URI) {
  companion object {
    fun parse(input: String, allowLocalHttp: Boolean = false): Endpoint {
      val uri =
        try {
          URI(input.trim())
        } catch (_: Exception) {
          throw IllegalArgumentException("Enter a valid bridge URL")
        }
      require(uri.isAbsolute && uri.host != null) { "Enter an absolute bridge URL" }
      require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Credentials, query and fragment are not allowed"
      }
      require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") {
        "Bridge URL must not have a path"
      }
      val scheme = uri.scheme.lowercase()
      require(
        scheme == "https" || (allowLocalHttp && scheme == "http" && uri.host in LOCAL_HOSTS)
      ) {
        "Use an HTTPS bridge URL"
      }
      require(uri.port == -1 || uri.port in 1..65535) { "Bridge URL port is invalid" }
      val defaultPort = if (scheme == "https") 443 else 80
      val port = if (uri.port == defaultPort) -1 else uri.port
      return Endpoint(URI(scheme, null, uri.host.lowercase(), port, "/", null, null))
    }

    private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1")
  }

  fun route(path: String): URI = base.resolve(path.removePrefix("/"))

  override fun equals(other: Any?): Boolean = other is Endpoint && base == other.base

  override fun hashCode(): Int = base.hashCode()

  fun stream(sessionId: String): URI =
    URI.create(
      "${if (base.scheme == "https") "wss" else "ws"}://${base.rawAuthority}/api/stream?session=${encodeQuery(sessionId)}"
    )

  private fun encodeQuery(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

data class AgentRow(
  val machineId: UUID,
  val sessionId: String,
  val paneId: String?,
  val label: String,
  val agent: String,
  val status: String,
  val working: Boolean,
  val branch: String?,
)

data class StreamEntry(
  val id: String,
  val seq: Long,
  val kind: String,
  val text: String,
  val branch: String?,
)

sealed interface StreamEvent {
  data class Open(val sessionId: String, val generation: Long) : StreamEvent

  data class Entries(val entries: List<StreamEntry>) : StreamEvent

  data class Reset(val generation: Long) : StreamEvent

  data class Error(val code: String, val message: String) : StreamEvent
}

data class OutputRow(val key: String, val text: String, val gap: Boolean = false)

data class StreamState(
  val sessionId: String? = null,
  val generation: Long? = null,
  val entries: List<StreamEntry> = emptyList(),
  val markers: List<OutputRow> = emptyList(),
  val error: String? = null,
) {
  fun rows(): List<OutputRow> {
    val result = mutableListOf<OutputRow>()
    var previous: Long? = null
    entries.forEach { entry ->
      val last = previous
      if (last != null && entry.seq > last + 1) {
        result +=
          OutputRow(
            "gap:$last:${entry.seq}",
            "Output gap: ${entry.seq - last - 1} entries missing",
            true,
          )
      } else if (last == null && entry.seq > 1) {
        result +=
          OutputRow(
            "gap:start:${entry.seq}",
            "Output begins at ${entry.seq}; earlier output unavailable",
            true,
          )
      }
      result += OutputRow("${generation}:${entry.id}", entry.text)
      previous = entry.seq
    }
    return markers + result
  }
}

fun parsePaired(json: JsonObject): UUID {
  if (
    json.string("event") != "paired" ||
      json.long("protocol") != 1L ||
      json.string("plugin") != BRIDGE_PLUGIN_ID
  )
    throw ProtocolException("Unsupported bridge session response")
  return uuid(json.string("machine_id"))
}

fun parseAgents(json: JsonObject, expectedMachine: UUID): List<AgentRow> {
  if (
    json.fields["event"]?.let { (it as? JsonValue.StringValue)?.value }?.let { it != "agents" } ==
      true
  )
    throw ProtocolException("Expected agents response")
  if (uuid(json.string("machine_id")) != expectedMachine)
    throw ProtocolException("Machine identity changed")
  return json.array("agents").map { row ->
    val machine = uuid(row.string("machine_id"))
    if (machine != expectedMachine) throw ProtocolException("Agent belongs to another machine")
    val session = row.string("session_id")
    if (session.isBlank()) throw ProtocolException("Agent locator is empty")
    AgentRow(
      machine,
      session,
      row.optionalString("pane_id"),
      row.optionalString("label") ?: session,
      row.optionalString("agent") ?: "unknown",
      row.optionalString("status") ?: "unknown",
      (row.fields["working"] as? JsonValue.BooleanValue)?.value ?: false,
      row.optionalString("active_branch"),
    )
  }
}

fun parseStreamEvent(json: JsonObject): StreamEvent =
  when (json.string("event")) {
    "stream_open" -> {
      val session = json.string("session_id")
      if (session.isBlank()) throw ProtocolException("Stream locator is empty")
      StreamEvent.Open(session, nonNegative(json.long("generation"), "generation"))
    }
    "entries" ->
      StreamEvent.Entries(
        json.array("entries").map { row ->
          val id = row.string("id")
          val seq = positive(row.long("seq"), "sequence")
          if (row.boolean("complete").not()) throw ProtocolException("Incomplete stream entry")
          val kind = row.string("kind")
          val text = row.string("text")
          StreamEntry(
            id.also { if (it.isBlank()) throw ProtocolException("Entry id is empty") },
            seq,
            kind,
            text,
            row.optionalString("branch"),
          )
        }
      )
    "stream_reset" -> StreamEvent.Reset(nonNegative(json.long("generation"), "generation"))
    "error" -> StreamEvent.Error(json.string("code"), json.string("message"))
    else -> throw ProtocolException("Unknown stream event")
  }

fun reduce(state: StreamState, event: StreamEvent, selectedSession: String): StreamState =
  when (event) {
    is StreamEvent.Open -> {
      if (event.sessionId != selectedSession) throw ProtocolException("Stream locator mismatch")
      if (state.generation != null)
        StreamState(
          event.sessionId,
          event.generation,
          markers =
            listOf(
              OutputRow(
                "reconnect:${event.generation}",
                "Recent output window reopened; earlier output may be missing",
                true,
              )
            ),
        )
      else StreamState(event.sessionId, event.generation)
    }
    is StreamEvent.Entries -> {
      if (state.generation == null || state.sessionId != selectedSession)
        throw ProtocolException("Entries arrived before stream_open")
      val byId = state.entries.associateBy { it.id }.toMutableMap()
      val bySeq = state.entries.associateBy { it.seq }.toMutableMap()
      event.entries.forEach { entry ->
        val idMatch = byId[entry.id]
        val seqMatch = bySeq[entry.seq]
        if (idMatch != null && idMatch != entry) throw ProtocolException("Conflicting entry id")
        if (seqMatch != null && seqMatch != entry)
          throw ProtocolException("Conflicting entry sequence")
        byId[entry.id] = entry
        bySeq[entry.seq] = entry
      }
      val ordered = byId.values.sortedBy { it.seq }
      var retained = ordered.takeLast(MAX_OUTPUT_ROWS)
      var bytes = retained.sumOf { it.text.toByteArray(Charsets.UTF_8).size }
      while (bytes > MAX_OUTPUT_UTF8_BYTES && retained.isNotEmpty()) {
        bytes -= retained.first().text.toByteArray(Charsets.UTF_8).size
        retained = retained.drop(1)
      }
      val markers =
        if (retained.size < ordered.size)
          listOf(
            OutputRow("truncated:${state.generation}", "Earlier output truncated locally", true)
          )
        else state.markers
      state.copy(entries = retained, markers = markers)
    }
    is StreamEvent.Reset -> {
      val current = state.generation ?: throw ProtocolException("Reset arrived before stream_open")
      when {
        event.generation < current -> throw ProtocolException("Regressive stream reset")
        event.generation == current -> state
        else ->
          StreamState(
            selectedSession,
            event.generation,
            markers =
              listOf(
                OutputRow(
                  "reset:${event.generation}",
                  "Output window reset; earlier output unavailable",
                  true,
                )
              ),
          )
      }
    }
    is StreamEvent.Error -> state.copy(error = "${event.code}: ${event.message}")
  }

private fun uuid(value: String): UUID =
  try {
    UUID.fromString(value)
  } catch (_: Exception) {
    throw ProtocolException("Invalid machine identity")
  }

private fun positive(value: Long, name: String): Long =
  value.takeIf { it > 0 } ?: throw ProtocolException("Invalid $name")

private fun nonNegative(value: Long, name: String): Long =
  value.takeIf { it >= 0 } ?: throw ProtocolException("Invalid $name")
