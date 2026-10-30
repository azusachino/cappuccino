package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.TransportBadge
import com.azusachino.cappuccino.io.machineConnection

@Composable
internal fun ConnectionDiagnostics(profile: MachineProfile, state: ConnectedUiState) {
  val colors = MaterialTheme.appColors
  val details = state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }
  val connection = state.machineConnection(profile).connection
  val current = connection == ConnectionState.Connected
  val status = machineInformationStatus(profile, state)
  val statusColor = connectionStatusColor(status, colors)

  Column(
    Modifier.fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    MachineCard("Machine & Routing") {
      MachineDetailRow("Name", profile.label.takeUnless { it == "iroh.invalid" } ?: "Machine")
      MachineDivider()
      MachineDetailRow("Status", status, valueColor = statusColor)
      MachineDivider()

      if (profile.endpoint.iroh != null && profile.endpoint.hasTailnet) {
        MachineDetailRow("Primary transport", "iroh P2P (default)")
        MachineDivider()
        MachineDetailRow("Fallback transport", "Tailnet (automatic)")
        MachineDivider()
        val currentBadge = state.machineBadges[profile.id]
        val (activeRoute, activeColor) =
          when (currentBadge) {
            TransportBadge.IROH_DIRECT -> "iroh direct (default)" to colors.ok
            TransportBadge.IROH_RELAYED -> "iroh relay (default)" to colors.ok
            TransportBadge.TAILNET -> "Tailnet fallback" to colors.warn
            else -> if (current) "Connected" to colors.ok else status to colors.muted
          }
        MachineDetailRow("Active connection", activeRoute, valueColor = activeColor)
        MachineDivider()
        MachineDetailField(
          "Tailnet endpoint",
          profile.endpoint.base.toString().removeSuffix("/"),
          isMono = true,
        )
      } else if (profile.endpoint.iroh != null) {
        MachineDetailRow("Primary transport", "iroh P2P (default)")
        MachineDivider()
        val currentBadge = state.machineBadges[profile.id]
        val (activeRoute, activeColor) =
          when (currentBadge) {
            TransportBadge.IROH_DIRECT -> "iroh P2P (direct QUIC)" to colors.ok
            TransportBadge.IROH_RELAYED -> "iroh P2P (relayed)" to colors.ok
            else -> if (current) "Connected" to colors.ok else status to colors.muted
          }
        MachineDetailRow("Active connection", activeRoute, valueColor = activeColor)
      } else {
        MachineDetailRow("Transport", "Tailnet only")
        MachineDivider()
        MachineDetailField(
          "Tailnet endpoint",
          profile.endpoint.base.toString().removeSuffix("/"),
          isMono = true,
        )
      }

      profile.endpoint.iroh?.let { iroh ->
        MachineDivider()
        MachineDetailField("iroh Node ID", iroh.endpointId, isMono = true)
        MachineDivider()
        MachineDetailField(
          "iroh relay",
          iroh.relayUrl ?: "Default N0 relay (aps1 Tokyo)",
          isMono = true,
        )
        if (iroh.directAddrs.isNotEmpty()) {
          MachineDivider()
          MachineDetailField(
            "Direct addresses (${iroh.directAddrs.size})",
            iroh.directAddrs.joinToString("\n"),
            isMono = true,
          )
        }
      }

      if (connection is ConnectionState.Error) {
        MachineDivider()
        MachineDetailField(
          "Connection problem",
          connection.message,
          isMono = true,
          isError = true,
        )
      }
    }

    MachineCard("Versions & Protocol") {
      MachineDetailRow("App version", BuildConfig.VERSION_NAME, isMono = true)
      MachineDivider()
      MachineDetailRow("Bridge version", details?.version ?: "Not reported", isMono = true)
      MachineDivider()
      MachineDetailRow("Protocol version", "${details?.protocol ?: 1}", isMono = true)
    }

    if (
      state.activeProfileId == profile.id && state.selectedAgent?.machineId == profile.machineId
    ) {
      val agent = state.selectedAgent
      MachineCard("Current Agent & Session") {
        MachineDetailRow("Agent name", agent.agent)
        agent.cwd?.let { cwd ->
          MachineDivider()
          MachineDetailField("Working directory", cwd, isMono = true)
        }
        agent.branch?.let { branch ->
          MachineDivider()
          MachineDetailRow("Branch", branch, isMono = true)
        }
        state.stream.error?.let { streamErr ->
          MachineDivider()
          MachineDetailField("Runtime problem", streamErr, isMono = true, isError = true)
        }
        if (state.connection is ConnectionState.Error) {
          MachineDivider()
          MachineDetailField(
            "Stream problem",
            state.connection.message,
            isMono = true,
            isError = true,
          )
        }
      }
    }
  }
}
