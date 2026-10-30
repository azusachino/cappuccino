package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.machineConnection

@Composable
internal fun ConnectionDiagnostics(profile: MachineProfile, state: ConnectedUiState) {
  val details = state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }
  val connection = state.machineConnection(profile).connection
  Column(
    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    DetailGroup("Machine & Transport") {
      DetailRow("Name", profile.label.takeUnless { it == "iroh.invalid" } ?: "Machine")
      if (profile.endpoint.iroh != null && profile.endpoint.hasTailnet) {
        DetailRow("Primary transport", "iroh P2P (default)")
        DetailRow("Fallback transport", "Tailnet (automatic fallback)")
        DetailRow("Tailnet URL", profile.endpoint.base.toString().removeSuffix("/"), true)
        val active =
          when (state.machineBadges[profile.id]) {
            com.azusachino.cappuccino.io.TransportBadge.IROH_DIRECT ->
              "iroh P2P (direct QUIC) — default active"
            com.azusachino.cappuccino.io.TransportBadge.IROH_RELAYED ->
              "iroh P2P (relayed) — default active"
            com.azusachino.cappuccino.io.TransportBadge.TAILNET ->
              "Tailnet — automatic fallback active"
            else ->
              if (connection == com.azusachino.cappuccino.io.ConnectionState.Connected) "Connected"
              else "Connecting..."
          }
        DetailRow("Active connection", active)
      } else if (profile.endpoint.iroh != null) {
        DetailRow("Primary transport", "iroh P2P (default)")
        val active =
          when (state.machineBadges[profile.id]) {
            com.azusachino.cappuccino.io.TransportBadge.IROH_DIRECT -> "iroh P2P (direct QUIC)"
            com.azusachino.cappuccino.io.TransportBadge.IROH_RELAYED -> "iroh P2P (relayed)"
            else ->
              if (connection == com.azusachino.cappuccino.io.ConnectionState.Connected) "Connected"
              else "Connecting..."
          }
        DetailRow("Active connection", active)
      } else {
        DetailRow("Transport", "Tailnet only")
        DetailRow("Tailnet URL", profile.endpoint.base.toString().removeSuffix("/"), true)
      }
      DetailRow("Status", machineInformationStatus(profile, state))
      if (connection is ConnectionState.Error) DetailRow("Problem", connection.message, true)
    }
    DetailGroup("Versions") {
      DetailRow("App", BuildConfig.VERSION_NAME)
      DetailRow("Bridge", details?.version ?: "Not reported")
    }
    if (
      state.activeProfileId == profile.id && state.selectedAgent?.machineId == profile.machineId
    ) {
      DetailGroup("Current agent") {
        state.selectedAgent.cwd?.let { DetailRow("Working directory", it, true) }
        state.selectedAgent.branch?.let { DetailRow("Branch", it) }
        state.stream.error?.let { DetailRow("Runtime problem", it, true) }
        if (state.connection is ConnectionState.Error)
          DetailRow("Stream problem", state.connection.message, true)
      }
    }
  }
}

@Composable
private fun DetailGroup(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      title,
      Modifier.padding(start = 12.dp),
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
    )
    Surface(
      shape = MaterialTheme.shapes.medium,
      color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
      Column { content() }
    }
  }
}

@Composable
private fun DetailRow(label: String, value: String, longValue: Boolean = false) {
  ListItem(
    headlineContent = { Text(label, style = MaterialTheme.typography.bodyMedium) },
    supportingContent =
      if (longValue) {
        { Text(value) }
      } else null,
    trailingContent =
      if (!longValue) {
        {
          Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else null,
    colors =
      androidx.compose.material3.ListItemDefaults.colors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
      ),
  )
  HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}
