package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.io.BridgeDetails
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MachineInformationTest {
  private val profile =
    MachineProfile(
      "profile",
      "Machine",
      Endpoint.parse("https://bridge.example"),
      UUID.randomUUID(),
    )
  private val connected =
    ConnectedUiState(activeProfileId = profile.id, connection = ConnectionState.Connected)

  @Test
  fun unknownAndForeignMetadataDoNotInventLimitations() {
    assertTrue(machineInformationWarnings(profile, connected).isEmpty())
    val foreign = BridgeDetails(UUID.randomUUID(), "1", 1, mapOf("prompt_input" to false))
    assertTrue(
      machineInformationWarnings(
          profile,
          connected.copy(bridgeDetails = mapOf(profile.id to foreign)),
        )
        .isEmpty()
    )
  }

  @Test
  fun onlyCurrentVerifiedUnsupportedFeaturesRaiseWarnings() {
    val details =
      BridgeDetails(
        profile.machineId,
        "1",
        1,
        mapOf("prompt_input" to false, "live_output" to true),
      )
    val state = connected.copy(bridgeDetails = mapOf(profile.id to details))
    assertEquals(
      listOf("Prompt input is unavailable on this bridge."),
      machineInformationWarnings(profile, state),
    )
    assertTrue(
      machineInformationWarnings(profile, state.copy(connection = ConnectionState.Disconnected))
        .isEmpty()
    )
  }

  @Test
  fun protocolConflictAndActionFailureHaveHonestLabels() {
    assertEquals(
      "Protocol conflict",
      machineInformationStatus(
        profile,
        connected.copy(
          connection = ConnectionState.Error("Incompatible bridge protocol 2; app requires 1")
        ),
      ),
    )
    assertEquals(
      "Needs attention",
      machineInformationStatus(
        profile,
        connected.copy(connection = ConnectionState.Error("Prompt failed")),
      ),
    )
    assertEquals(
      "Not connected",
      machineInformationStatus(profile, connected.copy(activeProfileId = "other")),
    )
  }
}
