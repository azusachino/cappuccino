package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineProfileCodecTest {
  private val machine = UUID.fromString("11111111-1111-4111-8111-111111111111")
  private val legacyJson =
    """[{"id":"mac","label":"MacMini","endpoint":"https://mac.example/","machine_id":"$machine"}]"""

  @Test
  fun profilesSavedBeforeIrohDecodeUnchangedAndReEncodeByteForByte() {
    val decoded = MachineProfileCodec.decode(legacyJson, allowLocalHttp = false)
    assertEquals(
      listOf(MachineProfile("mac", "MacMini", Endpoint.parse("https://mac.example"), machine)),
      decoded,
    )
    assertNull(decoded.single().endpoint.iroh)
    assertEquals(legacyJson, MachineProfileCodec.encode(decoded))
  }

  @Test
  fun irohProfileRoundTripsWithRelayAndDirectAddresses() {
    val profile =
      MachineProfile(
        "office",
        "Office",
        Endpoint.iroh(
          IrohEndpointAddr("bridge-id", "https://relay.example/", listOf("10.0.0.2:4433"))
        ),
        machine,
      )
    val json = MachineProfileCodec.encode(listOf(profile))
    assertTrue(json.contains("\"type\":\"iroh\""))
    assertEquals(listOf(profile), MachineProfileCodec.decode(json, allowLocalHttp = false))
  }

  @Test
  fun irohProfileRoundTripsWithoutRelayOrDirectAddresses() {
    val profile =
      MachineProfile(
        "lan",
        "Lab",
        Endpoint.iroh(IrohEndpointAddr("bridge-id", null, emptyList())),
        machine,
      )
    assertEquals(
      listOf(profile),
      MachineProfileCodec.decode(MachineProfileCodec.encode(listOf(profile)), false),
    )
  }

  @Test
  fun tailnetAndIrohRowsCoexistInOneList() {
    val tailnet = MachineProfile("mac", "MacMini", Endpoint.parse("https://mac.example"), machine)
    val iroh =
      MachineProfile(
        "office",
        "Office",
        Endpoint.iroh(IrohEndpointAddr("bridge-id", null, listOf("10.0.0.2:4433"))),
        UUID.randomUUID(),
      )
    val decoded =
      MachineProfileCodec.decode(MachineProfileCodec.encode(listOf(tailnet, iroh)), false)
    assertEquals(listOf(tailnet, iroh), decoded)
  }

  @Test
  fun unknownMachineTypeIsRejectedInsteadOfGuessed() {
    val json =
      """[{"id":"x","label":"X","endpoint":"https://x.example/","machine_id":"$machine","type":"future"}]"""
    assertThrows(IllegalArgumentException::class.java) {
      MachineProfileCodec.decode(json, allowLocalHttp = false)
    }
  }
}
