package com.azusachino.cappuccino.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolErrorProtocolTest {
  @Test
  fun actualErrorFlagIsPreservedAndMissingIsNotSuccess() {
    for ((flag, expected) in
      listOf(
        ",\"is_error\":true" to true,
        ",\"is_error\":false" to false,
        "" to null,
      )) {
      val json =
        """{"turns":[{"id":"t","role":"agent","parts":[{"type":"tool_call","name":"bash","input":{"command":"test"},"output":"contains error text"$flag}]}]}"""
      val part =
        parseConversationResponse(parseJsonObject(json)).single().parts.single()
          as ConversationPart.ToolCall
      assertEquals(expected, part.isError)
      assertEquals("contains error text", part.output)
    }
  }
}
