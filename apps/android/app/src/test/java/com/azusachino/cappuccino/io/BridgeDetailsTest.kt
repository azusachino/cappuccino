package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.ProtocolException
import com.azusachino.cappuccino.core.parseJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDetailsTest {
  private val prefix =
    """"event":"paired","machine_id":"00000000-0000-0000-0000-000000000001","plugin":"azusachino.cappuccino-bridge""""

  @Test
  fun legacyHandshakeIsUnknownRatherThanUnsupported() {
    val details = parseBridgeDetails(parseJsonObject("{$prefix,\"protocol\":1}"))
    assertNull(details.version)
    assertNull(details.capabilities)
    assertEquals(1L, details.protocol)
  }

  @Test
  fun advertisedFalseIsDistinctFromMissingAndTrue() {
    val details =
      parseBridgeDetails(
        parseJsonObject(
          "{$prefix,\"protocol\":1,\"bridge_version\":\"0.1.0\",\"capabilities\":{\"live_output\":true,\"prompt_input\":false}}"
        )
      )
    assertEquals("0.1.0", details.version)
    assertEquals(true, details.capabilities?.get("live_output"))
    assertEquals(false, details.capabilities?.get("prompt_input"))
    assertNull(details.capabilities?.get("prompt_answers"))
  }

  @Test(expected = ProtocolException::class)
  fun malformedCapabilitiesAreRejected() {
    parseBridgeDetails(
      parseJsonObject("{$prefix,\"protocol\":1,\"capabilities\":{\"prompt_input\":\"yes\"}}")
    )
  }

  @Test
  fun incompatibleProtocolExplainsTheConflict() {
    try {
      parseBridgeDetails(parseJsonObject("{$prefix,\"protocol\":2}"))
      throw AssertionError("Incompatible protocol must fail closed")
    } catch (error: ProtocolException) {
      assertTrue(error.message!!.contains("protocol 2"))
      assertTrue(error.message!!.contains("requires 1"))
    }
  }
}
