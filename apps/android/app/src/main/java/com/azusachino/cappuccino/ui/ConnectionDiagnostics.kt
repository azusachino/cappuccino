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
    DetailGroup("Machine") {
      DetailRow("Name", profile.label)
      DetailRow("Address", profile.endpoint.base.toString(), true)
      DetailRow("Connection", machineInformationStatus(profile, state))
      if (connection is ConnectionState.Error)
        DetailRow("Connection problem", connection.message, true)
    }
    DetailGroup("Versions") {
      DetailRow("App version", BuildConfig.VERSION_NAME)
      DetailRow("Bridge version", details?.version ?: "Not reported")
      DetailRow("Protocol", "${details?.protocol?.toString() ?: "Not verified"} · App requires 1")
    }
    DetailGroup("Bridge support") {
      for ((key, label) in
        listOf(
          "agent_discovery" to "Agent discovery",
          "live_output" to "Live output",
          "conversation_history" to "Conversation history",
          "prompt_input" to "Prompt input",
          "prompt_answers" to "Approval / question answers",
        )) {
        DetailRow(
          label,
          when (details?.capabilities?.get(key)) {
            true -> "Supported"
            false -> "Unsupported"
            null -> "Not reported"
          },
        )
      }
    }
    if (
      state.activeProfileId == profile.id && state.selectedAgent?.machineId == profile.machineId
    ) {
      DetailGroup("Current agent") {
        state.conversationTurns
          .flatMap { it.parts }
          .filterIsInstance<com.azusachino.cappuccino.core.ConversationPart.ResponseStatus>()
          .lastOrNull { it.status == "failed" }
          ?.let { issue ->
            DetailRow(
              "Last response issue",
              issue.detail ?: "Failure reported without details",
              true,
            )
          }
        state.selectedAgent.cwd?.let { DetailRow("Working directory", it, true) }
        state.selectedAgent.branch?.let { DetailRow("Branch", it) }
        state.conversationSource?.let {
          DetailRow(
            "History",
            when (it) {
              "canonical_log" -> "Canonical session"
              "scrollback",
              "terminal_scrollback" -> "Scrollback fallback"
              else -> "Not recognized"
            },
          )
        }
        state.stream.error?.let { DetailRow("Runtime problem", it, true) }
        if (state.connection is ConnectionState.Error)
          DetailRow("Action / stream problem", state.connection.message, true)
      }
    }
    Text(
      if (connection == ConnectionState.Connected)
        "Metadata from the verified connection. Advertised support does not establish per-agent readiness or health."
      else
        "Last verified metadata, if available. Advertised support does not establish per-agent readiness or health.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
