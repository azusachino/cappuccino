package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineConnection
import com.azusachino.cappuccino.io.MachineProfile
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ConnectionStatusTest {
  private val profile =
    MachineProfile(
      "profile",
      "MacMini.local",
      Endpoint.parse("https://bridge.example"),
      UUID.randomUUID(),
    )

  private fun state(
    connection: ConnectionState = ConnectionState.Connected,
    streamLive: Boolean = false,
    machine: ConnectionState? = null,
    offlineSavedAt: Long? = null,
    reconnectAttempt: Int = 0,
  ) =
    ConnectedUiState(
      profiles = listOf(profile),
      activeProfileId = profile.id,
      connection = connection,
      streamLive = streamLive,
      machines = machine?.let { mapOf(profile.id to MachineConnection(it)) } ?: emptyMap(),
      offlineSavedAt = offlineSavedAt,
      reconnectAttempt = reconnectAttempt,
    )

  @Test
  fun workingNeverShowsOverAnErrorOnTheMachine() {
    // The bug: the stream was still marked live while the machine had already failed.
    val broken =
      state(
          connection = ConnectionState.Connected,
          streamLive = true,
          machine = ConnectionState.Error("timeout"),
        )
        .let { it.copy(stream = it.stream.copy(agentStatus = "working")) }
    assertEquals(SessionStatus.Link(ConnectionStatus.Offline), broken.sessionStatus(null))
  }

  @Test
  fun aLiveConnectedSessionShowsTheAgentStatus() {
    val live =
      state(connection = ConnectionState.Connected, streamLive = true).let {
        it.copy(stream = it.stream.copy(agentStatus = "working"))
      }
    assertEquals(SessionStatus.Agent("working"), live.sessionStatus(null))
  }

  @Test
  fun aPendingPromptBlocksTheAgentWhileLive() {
    val prompt =
      PromptCard(
        promptId = "p",
        type = "tool_approval",
        title = "Tool Permission Approval",
        message = null,
        toolName = null,
        command = null,
        options = emptyList(),
      )
    val blocked =
      state(connection = ConnectionState.Connected, streamLive = true).let {
        it.copy(stream = it.stream.copy(pendingPrompt = prompt, agentStatus = "working"))
      }
    assertEquals(SessionStatus.Agent("blocked"), blocked.sessionStatus(null))
  }

  @Test
  fun aConnectedSessionThatIsNotLiveYetIsLoading() {
    assertEquals(
      SessionStatus.Loading,
      state(connection = ConnectionState.Connected).sessionStatus(null),
    )
  }

  @Test
  fun reconnectingWithNothingToShowIsLoadingNotUnknown() {
    val reconnecting = state(connection = ConnectionState.Recovering, reconnectAttempt = 2)
    assertEquals(SessionStatus.Loading, reconnecting.sessionStatus(null))
  }

  @Test
  fun aSavedOfflineViewIsNeverLoading() {
    val saved = state(connection = ConnectionState.Connecting, offlineSavedAt = 1L)
    assertEquals(SessionStatus.Link(ConnectionStatus.Connecting), saved.sessionStatus(null))
  }

  @Test
  fun aMissingMachineEntryFallsBackToTheSessionConnection() {
    assertEquals(
      SessionStatus.Link(ConnectionStatus.Offline),
      state(connection = ConnectionState.Error("boom")).sessionStatus(null),
    )
  }

  @Test
  fun machineStatusReadsTheMachinesOwnConnection() {
    val machine =
      state(connection = ConnectionState.Connected, machine = ConnectionState.Error("x"))
    assertEquals(ConnectionStatus.Offline, machine.machineStatus(profile))
  }

  @Test
  fun protocolConflictIsNotReportedAsOffline() {
    val conflict =
      state(connection = ConnectionState.Error("Incompatible bridge protocol v2; app requires v1"))
    assertEquals(ConnectionStatus.ProtocolConflict, conflict.machineStatus(profile))
  }

  @Test
  fun reconnectingNamesTheAttempt() {
    assertEquals("Reconnecting · attempt 2", ConnectionStatus.Reconnecting(2).label)
    assertEquals("Reconnecting", ConnectionStatus.Reconnecting(0).label)
  }

  @Test
  fun everyStatusKeyIsKnownToTheBadgeStyle() {
    val keys =
      listOf(
        ConnectionStatus.Connecting,
        ConnectionStatus.Reconnecting(1),
        ConnectionStatus.NotConnected,
        ConnectionStatus.Offline,
        ConnectionStatus.ProtocolConflict,
      )
    for (status in keys) {
      assertNotEquals(
        "${status.styleKey} must be a known badge key",
        "Unknown",
        agentStatusStyle(status.styleKey, dark = false).label,
      )
    }
  }

  @Test
  fun composerSaysWhyItIsDisabled() {
    assertEquals("Connecting…", ConnectionStatus.Connecting.composerLabel())
    assertEquals("Reconnecting…", ConnectionStatus.Reconnecting(1).composerLabel())
    assertEquals("Offline", ConnectionStatus.Offline.composerLabel())
    assertEquals("Not connected", ConnectionStatus.NotConnected.composerLabel())
  }
}
