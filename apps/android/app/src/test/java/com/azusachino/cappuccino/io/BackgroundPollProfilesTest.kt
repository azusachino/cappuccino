package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.Endpoint
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundPollProfilesTest {
  private val tailnet =
    MachineProfile("mac", "MacMini", Endpoint.parse("https://mac.example"), UUID.randomUUID())
  private val paused =
    MachineProfile("paused", "Paused", Endpoint.parse("https://p.example"), UUID.randomUUID())
  private val iroh =
    MachineProfile(
      "office",
      "Office",
      Endpoint.iroh(IrohEndpointAddr("bridge-id", null, listOf("10.0.0.2:4433"))),
      UUID.randomUUID(),
    )

  private val dual =
    MachineProfile(
      "dual",
      "Dual",
      Endpoint.parse("https://dual.example")
        .withIroh(IrohEndpointAddr("dual-id", null, emptyList())),
      UUID.randomUUID(),
    )

  @Test
  fun backgroundPollSelectsTailnetIrohAndDualMachinesAndSkipsPausedOnes() {
    val selected = backgroundPollProfiles(listOf(tailnet, paused, iroh, dual), setOf("paused"))
    assertEquals(listOf("mac", "office", "dual"), selected.keys.toList())
  }

  @Test
  fun onlyPausedMachinesYieldNoBackgroundPolls() {
    assertEquals(
      emptyMap<String, MachineProfile>(),
      backgroundPollProfiles(listOf(paused), setOf("paused")),
    )
  }
}
