package com.azusachino.cappuccino.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ResponseStatusTest {
  @Test
  fun explicitStatusIsRetainedInOrderWithPartialText() {
    val turns =
      parseConversationResponse(
        parseJsonObject(
          """{"turns":[{"id":"t","role":"agent","parts":[{"type":"text","text":"Partial"},{"type":"response_status","status":"aborted","detail":"Cancelled"}]}]}"""
        )
      )
    assertEquals(ConversationPart.Text("Partial"), turns.single().parts[0])
    assertEquals(ConversationPart.ResponseStatus("aborted", "Cancelled"), turns.single().parts[1])
  }

  @Test
  fun toolTimestampsArePreservedAndAbsentTimesStayUnknown() {
    val json =
      """{"turns":[{"id":"t","role":"agent","parts":[{"type":"tool_call","name":"bash","input":"test","output":"done","started_at":"2026-10-10T01:00:00Z","finished_at":"2026-10-10T01:00:03Z"},{"type":"tool_call","name":"read","input":"file"}]}]}"""
    val parts = parseConversationResponse(parseJsonObject(json)).single().parts
    assertEquals("2026-10-10T01:00:00Z", (parts[0] as ConversationPart.ToolCall).startedAt)
    assertEquals("2026-10-10T01:00:03Z", (parts[0] as ConversationPart.ToolCall).finishedAt)
    assertEquals(null, (parts[1] as ConversationPart.ToolCall).startedAt)
  }

  @Test
  fun unknownStatusDoesNotBecomeAbortedOrSuccessful() {
    val turns =
      parseConversationResponse(
        parseJsonObject(
          """{"turns":[{"id":"t","role":"agent","parts":[{"type":"text","text":"Aborted in prose"},{"type":"response_status","status":"unknown"}]}]}"""
        )
      )
    assertEquals(listOf(ConversationPart.Text("Aborted in prose")), turns.single().parts)
  }
}
