package com.azusachino.cappuccino.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
  val context = LocalContext.current
  val details = state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }
  val connection = state.machineConnection(profile).connection
  val current = connection == ConnectionState.Connected
  val status = machineInformationStatus(profile, state)
  val statusColor = connectionStatusColor(status, colors)

  var previewItem by remember { mutableStateOf<Pair<String, String>?>(null) }
  val onPreview = { label: String, value: String -> previewItem = Pair(label, value) }
  val onCopy = { label: String, value: String ->
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
  }

  Column(
    Modifier.fillMaxWidth()
      .verticalScroll(rememberScrollState())
      .padding(top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    MachineCard("Machine & Routing") {
      val name = profile.label.takeUnless { it == "iroh.invalid" } ?: "Machine"
      MachineDetailRow(
        "Name",
        name,
        onClick = { onPreview("Name", name) },
        onLongClick = { onCopy("Name", name) },
      )
      MachineDivider()
      MachineDetailRow(
        "Status",
        status,
        valueColor = statusColor,
        onClick = { onPreview("Status", status) },
        onLongClick = { onCopy("Status", status) },
      )
      MachineDivider()

      if (profile.endpoint.iroh != null && profile.endpoint.hasTailnet) {
        val primary = "iroh P2P (default)"
        MachineDetailRow(
          "Primary route",
          primary,
          onClick = { onPreview("Primary route", primary) },
          onLongClick = { onCopy("Primary route", primary) },
        )
        MachineDivider()
        val fallback = "Tailnet (automatic)"
        MachineDetailRow(
          "Fallback route",
          fallback,
          onClick = { onPreview("Fallback route", fallback) },
          onLongClick = { onCopy("Fallback route", fallback) },
        )
        MachineDivider()
        val currentBadge = state.machineBadges[profile.id]
        val (activeRoute, activeColor) =
          when (currentBadge) {
            TransportBadge.IROH_DIRECT -> "iroh direct (default)" to colors.ok
            TransportBadge.IROH_RELAYED -> "iroh relay (default)" to colors.ok
            TransportBadge.TAILNET -> "Tailnet fallback" to colors.warn
            else -> if (current) "Connected" to colors.ok else status to colors.muted
          }
        MachineDetailRow(
          "Active route",
          activeRoute,
          valueColor = activeColor,
          onClick = { onPreview("Active route", activeRoute) },
          onLongClick = { onCopy("Active route", activeRoute) },
        )
        MachineDivider()
        val tailnet = profile.endpoint.base.toString().removeSuffix("/")
        MachineDetailField(
          "Tailnet address",
          tailnet,
          isMono = true,
          onClick = { onPreview("Tailnet address", tailnet) },
          onLongClick = { onCopy("Tailnet address", tailnet) },
        )
      } else if (profile.endpoint.iroh != null) {
        val primary = "iroh P2P (default)"
        MachineDetailRow(
          "Primary route",
          primary,
          onClick = { onPreview("Primary route", primary) },
          onLongClick = { onCopy("Primary route", primary) },
        )
        MachineDivider()
        val currentBadge = state.machineBadges[profile.id]
        val (activeRoute, activeColor) =
          when (currentBadge) {
            TransportBadge.IROH_DIRECT -> "iroh P2P (direct QUIC)" to colors.ok
            TransportBadge.IROH_RELAYED -> "iroh P2P (relayed)" to colors.ok
            else -> if (current) "Connected" to colors.ok else status to colors.muted
          }
        MachineDetailRow(
          "Active route",
          activeRoute,
          valueColor = activeColor,
          onClick = { onPreview("Active route", activeRoute) },
          onLongClick = { onCopy("Active route", activeRoute) },
        )
      } else {
        val primary = "Tailnet only"
        MachineDetailRow(
          "Primary route",
          primary,
          onClick = { onPreview("Primary route", primary) },
          onLongClick = { onCopy("Primary route", primary) },
        )
        MachineDivider()
        val tailnet = profile.endpoint.base.toString().removeSuffix("/")
        MachineDetailField(
          "Tailnet address",
          tailnet,
          isMono = true,
          onClick = { onPreview("Tailnet address", tailnet) },
          onLongClick = { onCopy("Tailnet address", tailnet) },
        )
      }

      profile.endpoint.iroh?.let { iroh ->
        MachineDivider()
        MachineDetailField(
          "Iroh node ID",
          iroh.endpointId,
          isMono = true,
          onClick = { onPreview("Iroh node ID", iroh.endpointId) },
          onLongClick = { onCopy("Iroh node ID", iroh.endpointId) },
        )
        MachineDivider()
        val relay = iroh.relayUrl ?: "Default N0 relay (aps1 Tokyo)"
        MachineDetailField(
          "Iroh relay",
          relay,
          isMono = true,
          onClick = { onPreview("Iroh relay", relay) },
          onLongClick = { onCopy("Iroh relay", relay) },
        )
        if (iroh.directAddrs.isNotEmpty()) {
          MachineDivider()
          val addrs = iroh.directAddrs.joinToString("\n")
          MachineDetailField(
            "Direct addresses (${iroh.directAddrs.size})",
            addrs,
            isMono = true,
            onClick = { onPreview("Direct addresses", addrs) },
            onLongClick = { onCopy("Direct addresses", addrs) },
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
          onClick = { onPreview("Connection problem", connection.message) },
          onLongClick = { onCopy("Connection problem", connection.message) },
        )
      }
    }

    MachineCard("Versions & Protocol") {
      MachineDetailRow(
        "App version",
        BuildConfig.VERSION_NAME,
        isMono = true,
        onClick = { onPreview("App version", BuildConfig.VERSION_NAME) },
        onLongClick = { onCopy("App version", BuildConfig.VERSION_NAME) },
      )
      MachineDivider()
      val bridgeVer = details?.version ?: "Not reported"
      MachineDetailRow(
        "Bridge version",
        bridgeVer,
        isMono = true,
        onClick = { onPreview("Bridge version", bridgeVer) },
        onLongClick = { onCopy("Bridge version", bridgeVer) },
      )
      MachineDivider()
      val protoVer = "v${details?.protocol ?: 1}"
      MachineDetailRow(
        "Protocol version",
        protoVer,
        isMono = true,
        onClick = { onPreview("Protocol version", protoVer) },
        onLongClick = { onCopy("Protocol version", protoVer) },
      )
    }

    if (
      state.activeProfileId == profile.id && state.selectedAgent?.machineId == profile.machineId
    ) {
      val agent = state.selectedAgent
      MachineCard("Current Agent & Session") {
        MachineDetailRow(
          "Agent name",
          agent.agent,
          onClick = { onPreview("Agent name", agent.agent) },
          onLongClick = { onCopy("Agent name", agent.agent) },
        )
        agent.cwd?.let { cwd ->
          MachineDivider()
          MachineDetailField(
            "Working directory",
            cwd,
            isMono = true,
            onClick = { onPreview("Working directory", cwd) },
            onLongClick = { onCopy("Working directory", cwd) },
          )
        }
        agent.branch?.let { branch ->
          MachineDivider()
          MachineDetailRow(
            "Branch",
            branch,
            isMono = true,
            onClick = { onPreview("Branch", branch) },
            onLongClick = { onCopy("Branch", branch) },
          )
        }
        state.stream.error?.let { streamErr ->
          MachineDivider()
          MachineDetailField(
            "Runtime problem",
            streamErr,
            isMono = true,
            isError = true,
            onClick = { onPreview("Runtime problem", streamErr) },
            onLongClick = { onCopy("Runtime problem", streamErr) },
          )
        }
        if (state.connection is ConnectionState.Error) {
          MachineDivider()
          MachineDetailField(
            "Stream problem",
            state.connection.message,
            isMono = true,
            isError = true,
            onClick = { onPreview("Stream problem", state.connection.message) },
            onLongClick = { onCopy("Stream problem", state.connection.message) },
          )
        }
      }
    }
  }

  previewItem?.let { (label, value) ->
    ValuePreviewDialog(
      title = label,
      value = value,
      onDismiss = { previewItem = null },
    )
  }
}
