package com.azusachino.cappuccino.core

import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class BridgeProtocolTest {
  @Test
  fun retryBudgetAllowsInitialPlusFourBoundedRetriesIncludingAfterOpen() {
    val budget = StreamRetryBudget()
    val delays = List(4) { budget.nextDelayMillis() }
    assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L), delays)
    assertNull(budget.nextDelayMillis())
  }

  @Test
  fun newAttachmentGetsIndependentRetryBudget() {
    val first = StreamRetryBudget()
    repeat(4) { assertNotNull(first.nextDelayMillis()) }
    assertNull(first.nextDelayMillis())
    assertEquals(1_000L, StreamRetryBudget().nextDelayMillis())
  }

  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")

  private fun obj(vararg fields: Pair<String, JsonValue>) = JsonObject(mapOf(*fields))

  private fun s(value: String) = JsonValue.StringValue(value)

  private fun n(value: Long) = JsonValue.Number(value)

  private fun b(value: Boolean) = JsonValue.BooleanValue(value)

  @Test
  fun parsesActualSharedCatalogFixture() {
    val fixture = java.io.File("../../../fixtures/agents-list.json").readText()
    val parsed = parseAgents(parseJsonObject(fixture), machine)
    assertEquals(listOf("s-aurora", "s-borealis"), parsed.map { it.sessionId })
  }

  @Test
  fun endpointRejectsUnsafeUrlsAndNormalizesHttps() {
    assertEquals(
      "https://bridge.example/",
      Endpoint.parse(" HTTPS://Bridge.Example:443 ").base.toString(),
    )
    assertEquals(
      "https://bridge.example:80/",
      Endpoint.parse("https://bridge.example:80").base.toString(),
    )
    assertEquals(
      "http://localhost:443/",
      Endpoint.parse("http://localhost:443", allowLocalHttp = true).base.toString(),
    )
    assertThrows(IllegalArgumentException::class.java) {
      Endpoint.parse("https://bridge.example:65536")
    }
    assertThrows(IllegalArgumentException::class.java) {
      Endpoint.parse("https://bridge.example:0")
    }
    listOf(
        "http://bridge.example",
        "https://u:p@bridge.example",
        "https://bridge.example/path",
        "https://bridge.example/?x=1",
        "https://bridge.example/#x",
      )
      .forEach { assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(it) } }
    assertEquals(
      "http://10.0.2.2/",
      Endpoint.parse("http://10.0.2.2:80", allowLocalHttp = true).base.toString(),
    )
    assertThrows(IllegalArgumentException::class.java) {
      Endpoint.parse("http://example.com", allowLocalHttp = true)
    }
  }

  @Test
  fun streamQueryEncodesLocator() {
    val uri = Endpoint.parse("https://bridge.example").stream("w1:p6F a&?雪#")
    assertEquals("wss", uri.scheme)
    assertTrue(uri.rawQuery!!.contains("%26%3F"))
    assertTrue(uri.rawQuery!!.contains("%E9%9B%AA"))
    assertFalse(uri.rawQuery!!.contains("#"))
  }

  @Test
  fun parsesPairedAndRejectsMalformedValues() {
    assertEquals(
      machine,
      parsePaired(
        obj(
          "event" to s("paired"),
          "protocol" to n(1),
          "machine_id" to s(machine.toString()),
          "plugin" to s(BRIDGE_PLUGIN_ID),
        )
      ),
    )
    assertThrows(ProtocolException::class.java) {
      parsePaired(
        obj(
          "event" to s("paired"),
          "protocol" to n(2),
          "machine_id" to s(machine.toString()),
          "plugin" to s(BRIDGE_PLUGIN_ID),
        )
      )
    }
  }

  @Test
  fun parsesCatalogIncludingUnnamedRowsAndEnforcesIdentity() {
    val row =
      obj(
        "machine_id" to s(machine.toString()),
        "session_id" to s("w1:p6F"),
        "pane_id" to s("w1:p6F"),
        "label" to s("pi"),
        "agent" to s("pi"),
        "status" to s("idle"),
        "working" to b(false),
        "active_branch" to JsonValue.Null,
      )
    val catalog =
      obj(
        "event" to s("agents"),
        "machine_id" to s(machine.toString()),
        "agents" to JsonValue.Array(listOf(JsonValue.Object(row))),
      )
    assertEquals("w1:p6F", parseAgents(catalog, machine).single().sessionId)
    assertThrows(ProtocolException::class.java) { parseAgents(catalog, UUID.randomUUID()) }
    assertThrows(ProtocolException::class.java) {
      parseAgents(
        catalog.copy(
          fields =
            catalog.fields + ("agents" to JsonValue.Array(listOf(JsonValue.StringValue("bad"))))
        ),
        machine,
      )
    }
  }

  @Test
  fun boundsHttpBodiesAndAssembledWebSocketMessages() {
    assertThrows(ProtocolException::class.java) {
      parseJsonObject(" ".repeat(MAX_HTTP_BODY_BYTES + 1))
    }
    assertThrows(ProtocolException::class.java) {
      validateWebSocketMessage("雪".repeat(MAX_WS_MESSAGE_BYTES / 2 + 1))
    }
  }

  @Test
  fun reducerOrdersDeduplicatesGapsAndResetsConnectionWindow() {
    var state = reduce(StreamState(), StreamEvent.Open("s", 1), "s")
    val first = StreamEntry("a", 4, "output", "first", null)
    state = reduce(state, StreamEvent.Entries(listOf(first, first)), "s")
    assertEquals(1, state.entries.size)
    assertTrue(state.rows().first().gap)
    assertEquals(state, reduce(state, StreamEvent.Reset(1), "s"))
    state = reduce(state, StreamEvent.Reset(2), "s")
    assertTrue(state.entries.isEmpty())
    assertThrows(ProtocolException::class.java) { reduce(state, StreamEvent.Reset(1), "s") }
    assertThrows(ProtocolException::class.java) { reduce(state, StreamEvent.Open("other", 3), "s") }
  }

  @Test
  fun reducerRejectsConflictIncompleteAndUnknownEvents() {
    val open = reduce(StreamState(), StreamEvent.Open("s", 1), "s")
    assertThrows(ProtocolException::class.java) {
      reduce(
        open,
        StreamEvent.Entries(
          listOf(StreamEntry("a", 1, "output", "x", null), StreamEntry("a", 1, "output", "y", null))
        ),
        "s",
      )
    }
    assertThrows(ProtocolException::class.java) {
      parseStreamEvent(
        obj(
          "event" to s("entries"),
          "entries" to JsonValue.Array(listOf(JsonValue.Object(obj("id" to s("x"))))),
        )
      )
    }
    assertThrows(ProtocolException::class.java) { parseStreamEvent(obj("event" to s("mystery"))) }
  }

  @Test
  fun reducerRejectsDuplicateSequenceWithinBatchAndAgainstState() {
    val opened = reduce(StreamState(), StreamEvent.Open("s", 0), "s")
    assertThrows(ProtocolException::class.java) {
      reduce(
        opened,
        StreamEvent.Entries(
          listOf(StreamEntry("a", 1, "output", "a", null), StreamEntry("b", 1, "output", "b", null))
        ),
        "s",
      )
    }
    val populated =
      reduce(opened, StreamEvent.Entries(listOf(StreamEntry("a", 1, "output", "a", null))), "s")
    assertThrows(ProtocolException::class.java) {
      reduce(populated, StreamEvent.Entries(listOf(StreamEntry("b", 1, "output", "b", null))), "s")
    }
  }

  @Test
  fun retentionCapsRowsAndUtf8Bytes() {
    var state = reduce(StreamState(), StreamEvent.Open("s", 1), "s")
    val entries =
      (1L..(MAX_OUTPUT_ROWS + 2).toLong()).map { StreamEntry("id$it", it, "output", "x", null) }
    state = reduce(state, StreamEvent.Entries(entries), "s")
    assertEquals(MAX_OUTPUT_ROWS, state.entries.size)
    assertTrue(state.markers.isNotEmpty())
    state = reduce(state, StreamEvent.Reset(2), "s")
    state =
      reduce(
        state,
        StreamEvent.Entries(
          listOf(StreamEntry("large", 1, "output", "雪".repeat(MAX_OUTPUT_UTF8_BYTES), null))
        ),
        "s",
      )
    assertTrue(state.entries.isEmpty())
  }
}
