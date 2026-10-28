package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.ProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class IrohPairingTest {
  private val valid =
    """{"v":1,"endpoint_id":"abc123","relay":"https://relay.example/","addrs":["10.0.0.2:4433","[fd00::2]:4433"],"token":"tok"}"""

  private fun assertRejected(text: String) {
    try {
      parseIrohPairing(text)
      fail("expected rejection: $text")
    } catch (expected: ProtocolException) {}
  }

  @Test
  fun parsesVersionOnePayload() {
    val pairing = parseIrohPairing(valid)
    assertEquals("abc123", pairing.endpoint.endpointId)
    assertEquals("https://relay.example/", pairing.endpoint.relayUrl)
    assertEquals(listOf("10.0.0.2:4433", "[fd00::2]:4433"), pairing.endpoint.directAddrs)
    assertEquals("tok", pairing.token)
  }

  @Test
  fun emptyAddrListIsAccepted() {
    val pairing = parseIrohPairing(valid.replace("[\"10.0.0.2:4433\",\"[fd00::2]:4433\"]", "[]"))
    assertEquals(emptyList<String>(), pairing.endpoint.directAddrs)
  }

  @Test
  fun rejectsEveryVersionOtherThanOne() {
    assertRejected(valid.replace("\"v\":1", "\"v\":2"))
    assertRejected(valid.replace("\"v\":1", "\"v\":0"))
  }

  @Test
  fun rejectsMissingRequiredField() {
    assertRejected(valid.replace("\"endpoint_id\":\"abc123\",", ""))
    assertRejected(valid.replace("\"relay\":\"https://relay.example/\",", ""))
    assertRejected(valid.replace("\"token\":\"tok\"", "\"x\":1"))
    assertRejected("""{"v":1,"endpoint_id":"abc123","relay":"r","token":"t"}""")
  }

  @Test
  fun rejectsEmptyEndpointIdOrToken() {
    assertRejected(valid.replace("\"abc123\"", "\"\""))
    assertRejected(valid.replace("\"tok\"", "\"\""))
  }

  @Test
  fun rejectsNonStringAddr() {
    assertRejected(valid.replace("\"10.0.0.2:4433\"", "42"))
  }

  @Test
  fun rejectsNonObjectJson() {
    assertRejected("[1,2,3]")
    assertRejected("not json")
  }
}
