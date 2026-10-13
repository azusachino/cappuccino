package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.machineConnection

internal fun machineInformationStatus(profile: MachineProfile, state: ConnectedUiState): String =
  when (val connection = state.machineConnection(profile).connection) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering -> "Reconnecting"
    ConnectionState.Disconnected -> "Not connected"
    is ConnectionState.Error ->
      if (connection.message.startsWith("Incompatible bridge protocol")) "Protocol conflict"
      else "Needs attention"
  }

internal fun machineInformationWarnings(
  profile: MachineProfile,
  state: ConnectedUiState,
): List<String> {
  if (state.machineConnection(profile).connection != ConnectionState.Connected) return emptyList()
  val capabilities =
    state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }?.capabilities
      ?: return emptyList()
  return listOf(
      "agent_discovery" to "Agent discovery is unavailable.",
      "live_output" to "Live output is unavailable.",
      "conversation_history" to "Conversation history is unavailable.",
      "prompt_input" to "Prompt input is unavailable on this bridge.",
      "prompt_answers" to "Approval and question answers are unavailable.",
    )
    .filter { capabilities[it.first] == false }
    .map { it.second }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MachineInformation(
  profile: MachineProfile,
  state: ConnectedUiState,
  onDismiss: () -> Unit,
  onTechnicalDetails: () -> Unit,
  onRename: () -> Unit,
) {
  val details = state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }
  val connection = state.machineConnection(profile).connection
  val current = connection == ConnectionState.Connected
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
      Text(
        profile.label,
        Modifier.padding(horizontal = 24.dp),
        style = MaterialTheme.typography.headlineSmall,
      )
      Text(
        machineInformationStatus(profile, state),
        Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (connection is ConnectionState.Error) {
        Text(
          if (machineInformationStatus(profile, state) == "Protocol conflict")
            "The app and bridge use incompatible protocols. Update to compatible builds."
          else "The connection or an action needs attention. See Technical details.",
          Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
          color = MaterialTheme.colorScheme.error,
        )
      }
      machineInformationWarnings(profile, state).forEach { warning ->
        Text(
          warning,
          Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
          color = MaterialTheme.colorScheme.error,
        )
      }
      ListItem(
        headlineContent = { Text("App version") },
        trailingContent = {
          Text(BuildConfig.VERSION_NAME, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
      )
      ListItem(
        headlineContent = { Text("Bridge version") },
        trailingContent = {
          Text(
            details?.version ?: "Not reported",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        },
        supportingContent = if (details != null && !current) ({ Text("Last verified") }) else null,
      )
      Surface(onClick = onTechnicalDetails, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text("Technical details") }, trailingContent = { Text("›") })
      }
      Surface(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
        ListItem(headlineContent = { Text("Rename") }, trailingContent = { Text("›") })
      }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TechnicalConnectionScreen(
  profile: MachineProfile,
  state: ConnectedUiState,
  onBack: () -> Unit,
) {
  BackHandler(onBack = onBack)
  Scaffold(
    topBar = {
      CenterAlignedTopAppBar(
        title = { Text("Technical details") },
        navigationIcon = {
          IconButton(onClick = onBack) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_back),
              contentDescription = "Back",
            )
          }
        },
      )
    }
  ) { padding ->
    SelectionContainer(Modifier.padding(padding).padding(16.dp)) {
      ConnectionDiagnostics(profile, state)
    }
  }
}
