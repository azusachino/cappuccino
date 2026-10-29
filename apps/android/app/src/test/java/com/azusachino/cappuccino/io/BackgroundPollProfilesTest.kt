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

  @Test
  fun backgroundPollKeepsTailnetMachinesAndSkipsIrohAndPausedOnes() {
    val selected = backgroundPollProfiles(listOf(tailnet, paused, iroh), setOf("paused"))
    assertEquals(listOf("mac"), selected.keys.toList())
  }

  @Test
  fun onlyIrohMachinesYieldNoBackgroundPolls() {
    assertEquals(
      emptyMap<String, MachineProfile>(),
      backgroundPollProfiles(listOf(iroh), emptySet()),
    )
  }
}
