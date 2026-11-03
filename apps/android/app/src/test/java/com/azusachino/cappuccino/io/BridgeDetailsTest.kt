package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.ProtocolException
import com.azusachino.cappuccino.core.parseJsonObject
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDetailsTest {
  private val prefix =
    """"event":"paired","machine_id":"00000000-0000-0000-0000-000000000001","plugin":"cappuccino-bridge""""

  @Test
  fun offeredAndAgreedCapabilitiesAreTrueOnlyWhenAgreed() {
    val details =
      parseBridgeDetails(
        parseJsonObject(
          "{$prefix,\"protocol\":2,\"bridge_version\":\"0.2.0\",\"capabilities\":{\"live_output\":1,\"prompt_input\":1},\"agreed\":{\"live_output\":1}}"
        )
      )
    assertEquals("0.2.0", details.version)
    assertEquals(true, details.capabilities?.get("live_output"))
    assertEquals(false, details.capabilities?.get("prompt_input"))
    assertNull(details.capabilities?.get("prompt_answers"))
  }

  @Test(expected = ProtocolException::class)
  fun aHandshakeWithoutCapabilitiesIsRejected() {
    parseBridgeDetails(parseJsonObject("{$prefix,\"protocol\":2}"))
  }

  @Test(expected = ProtocolException::class)
  fun malformedCapabilitiesAreRejected() {
    parseBridgeDetails(
      parseJsonObject("{$prefix,\"protocol\":2,\"capabilities\":\"yes\",\"agreed\":{}}")
    )
  }

  @Test
  fun incompatibleProtocolExplainsTheConflict() {
    try {
      parsePairedFromDetails("{$prefix,\"protocol\":1}")
      throw AssertionError("Protocol v1 must fail closed")
    } catch (error: ProtocolException) {
      assertTrue(error.message!!.contains("protocol v1"))
      assertTrue(error.message!!.contains("requires v2"))
    }
  }

  @Test
  fun telegramRouteAndEventsAreReadFromTheHandshake() {
    val details =
      parseBridgeDetails(
        parseJsonObject(
          "{$prefix,\"protocol\":2,\"capabilities\":{},\"agreed\":{},\"telegram\":{\"route\":\"enabled\",\"events\":[\"completion\"]}}"
        )
      )
    assertEquals("enabled", details.telegramRoute)
    assertEquals(listOf("completion"), details.telegramEvents)
  }

  @Test
  fun missingTelegramBlockMeansTelegramDoesNotCover() {
    val details =
      parseBridgeDetails(
        parseJsonObject("{$prefix,\"protocol\":2,\"capabilities\":{},\"agreed\":{}}")
      )
    assertNull(details.telegramRoute)
    assertTrue(details.telegramEvents.isEmpty())
    assertFalse(telegramCovers(details, "completion"))
  }

  @Test
  fun telegramCoversOnlyAWorkingRouteForAnEventItSends() {
    fun covered(route: String?, events: List<String>, event: String) =
      telegramCovers(
        BridgeDetails(
          UUID.fromString("00000000-0000-0000-0000-000000000001"),
          "0.2.0",
          2,
          null,
          route,
          events,
        ),
        event,
      )
    assertTrue(covered("enabled", listOf("completion"), "completion"))
    assertFalse(covered("enabled", listOf("completion"), "needs_answer"))
    assertFalse(covered("failing", listOf("completion"), "completion"))
    assertFalse(covered("rejected", listOf("completion"), "completion"))
    assertFalse(covered("disabled", emptyList(), "completion"))
    assertFalse(covered(null, listOf("completion"), "completion"))
    assertFalse(telegramCovers(null, "completion"))
  }

  private fun parsePairedFromDetails(json: String) =
    com.azusachino.cappuccino.core.parsePaired(parseJsonObject(json))
}
