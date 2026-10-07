package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
import kotlinx.coroutines.launch

private enum class Destination(val title: Int, val icon: Int) {
  CHATS(com.azusachino.cappuccino.R.string.chats, com.azusachino.cappuccino.R.drawable.ic_chat),
  ATTENTION(
    com.azusachino.cappuccino.R.string.attention,
    com.azusachino.cappuccino.R.drawable.ic_attention,
  ),
  MACHINES(
    com.azusachino.cappuccino.R.string.machines,
    com.azusachino.cappuccino.R.drawable.ic_machine,
  ),
}

@Composable
fun CappuccinoShell(viewModel: ConnectedViewModel, requestConnect: (String, String) -> Unit) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  CappuccinoScreen(state, viewModel, requestConnect)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CappuccinoScreen(
  state: ConnectedUiState,
  actions: ConnectedActions,
  requestConnect: (String, String) -> Unit,
) {
  var destination by rememberSaveable { mutableStateOf(Destination.CHATS) }
  var adding by rememberSaveable { mutableStateOf(false) }
  var label by rememberSaveable { mutableStateOf("") }
  var endpoint by rememberSaveable { mutableStateOf("") }
  var pendingAdd by rememberSaveable { mutableStateOf(false) }
  var previousProfileIds by rememberSaveable { mutableStateOf("") }
  val selected = state.selectedAgent
  val outputListState = rememberLazyListState()
  var hasNewOutput by remember { mutableStateOf(false) }
  var followingOutput by remember { mutableStateOf(true) }
  val outputScope = rememberCoroutineScope()
  LaunchedEffect(outputListState) {
    var layoutReady = false
    snapshotFlow {
      val info = outputListState.layoutInfo
      info.totalItemsCount to
        ((info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2)
    }
      .collect { (total, nearEnd) ->
        if (total > 0) {
          if (layoutReady) followingOutput = nearEnd else layoutReady = true
        }
      }
  }
  LaunchedEffect(selected?.sessionId) {
    followingOutput = true
    hasNewOutput = false
  }
  LaunchedEffect(state.stream.entries.size, selected?.sessionId, state.connection) {
    val rows = state.stream.rows()
    if (rows.isNotEmpty()) {
      if (followingOutput || rows.size <= 1) {
        outputListState.scrollToItem(rows.lastIndex)
        hasNewOutput = false
      } else {
        hasNewOutput = true
      }
    }
  }
  LaunchedEffect(state.addedProfileIds, pendingAdd) {
    if (pendingAdd && state.addedProfileIds.any { it !in previousProfileIds.split('|') }) {
      adding = false
      pendingAdd = false
      endpoint = ""
    }
  }
  BackHandler(enabled = selected != null) { actions.selectAgent(null) }

  Scaffold(
    topBar = {
      TopAppBar(
        title = {
          Text(if (selected != null) selected.label else stringResource(destination.title))
        }
      )
    },
    bottomBar = {
      NavigationBar {
        Destination.entries.forEach { item ->
          NavigationBarItem(
            selected = destination == item && selected == null,
            onClick = {
              actions.selectAgent(null)
              destination = item
            },
            icon = { Icon(painterResource(item.icon), contentDescription = null) },
            label = { Text(stringResource(item.title)) },
          )
        }
      }
    },
  ) { contentPadding ->
    Column(
      Modifier.fillMaxSize().padding(contentPadding).imePadding(),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      when {
        selected != null -> {
          Text(
            "Recent agent output · Read-only",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.secondary,
          )
          Text(
            "${state.profiles.firstOrNull { it.id == state.activeProfileId }?.label ?: "Machine"} · ${selected.sessionId} · ${selected.branch ?: "Branch unknown"}",
            Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.labelMedium,
          )
          when (val connection = state.connection) {
            ConnectionState.Connecting -> Text("Connecting…", Modifier.padding(16.dp))
            ConnectionState.Recovering ->
              Text(
                "Reconnecting; earlier output may be missing",
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.tertiary,
              )
            is ConnectionState.Error ->
              Text(
                connection.message,
                Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.error,
              )
            else -> Unit
          }
          if (hasNewOutput)
            TextButton(
              onClick = {
                outputScope.launch {
                  val last = state.stream.rows().lastIndex
                  if (last >= 0) outputListState.animateScrollToItem(last)
                  hasNewOutput = false
                }
              },
              modifier = Modifier.align(Alignment.End).padding(end = 16.dp),
            ) {
              Text("Jump to latest")
            }
          SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
              state = outputListState,
              modifier =
                Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("recentOutputList"),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(state.stream.rows(), key = { it.key }) { row ->
                Text(
                  row.text,
                  color =
                    if (row.gap) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface,
                  fontFamily = if (row.gap) FontFamily.Default else FontFamily.Monospace,
                )
              }
            }
          }
          if (state.stream.entries.isEmpty())
            Text(
              "No recent output received.",
              Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        destination == Destination.CHATS -> {
          Text(
            "${state.connection.statusText()} · ${state.agents.size} agents",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          if (state.agents.isEmpty()) {
            Text(
              if (state.activeProfileId == null)
                "Add a private bridge in Machines to discover agents."
              else "No agents found. Refresh to try again.",
              Modifier.padding(16.dp),
            )
          }
          LazyColumn(Modifier.weight(1f)) {
            items(state.agents, key = { "${it.machineId}:${it.sessionId}" }) { agent ->
              ListItem(
                headlineContent = { Text(agent.label) },
                supportingContent = {
                  Text(
                    "${state.profiles.firstOrNull { it.id == state.activeProfileId }?.label ?: "Machine"} · ${agent.sessionId} · ${agent.status}${agent.branch?.let { " · $it" } ?: ""}"
                  )
                },
                modifier =
                  Modifier.fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(
                      selected = false,
                      onClick = { actions.selectAgent(agent) },
                      role = Role.Button,
                    ),
              )
            }
          }
          Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TextButton(onClick = { destination = Destination.MACHINES }) { Text("Machines") }
            TextButton(
              onClick = actions::refresh,
              enabled = state.activeProfileId != null && !state.busy,
            ) {
              Text("Refresh")
            }
          }
          Text(
            "Sending is not supported by this bridge yet",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Button(onClick = {}, enabled = false, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Send unavailable")
          }
        }
        destination == Destination.ATTENTION ->
          Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
              "Approvals unavailable",
              style = MaterialTheme.typography.titleLarge,
              modifier = Modifier.semantics { heading() },
            )
            Text(
              "This bridge does not expose tool approvals or alerts. Pending requests cannot be determined.",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        else -> {
          if (state.profiles.isEmpty()) {
            Text(
              "No machines added",
              Modifier.padding(16.dp),
              style = MaterialTheme.typography.titleLarge,
            )
            Text(
              "Connect to a private HTTPS bridge. The phone only reads existing agent output.",
              Modifier.padding(horizontal = 16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          LazyColumn(Modifier.weight(1f)) {
            items(state.profiles, key = { it.id }) { profile ->
              ListItem(
                headlineContent = { Text(profile.label) },
                supportingContent = {
                  Text("${profile.endpoint.base.host} · ${profile.machineId}")
                },
                trailingContent = {
                  TextButton(onClick = { actions.removeProfile(profile.id) }) { Text("Remove") }
                },
                modifier =
                  Modifier.fillMaxWidth()
                    .selectable(
                      selected = state.activeProfileId == profile.id,
                      onClick = { actions.selectProfile(profile.id) },
                      role = Role.RadioButton,
                    ),
              )
            }
          }
          val connection = state.connection
          if (connection is ConnectionState.Error)
            Text(
              connection.message,
              Modifier.padding(horizontal = 16.dp),
              color = MaterialTheme.colorScheme.error,
            )
          if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
          Button(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("Add machine")
          }
          if (state.activeProfileId != null)
            TextButton(
              onClick = actions::retry,
              modifier = Modifier.padding(horizontal = 16.dp),
            ) {
              Text("Retry connection")
            }
        }
      }
    }
  }

  if (adding) {
    AlertDialog(
      onDismissRequest = {
        if (!state.busy) {
          adding = false
          pendingAdd = false
        }
      },
      title = { Text("Add machine") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedTextField(
            label,
            { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
          )
          OutlinedTextField(
            endpoint,
            { endpoint = it },
            label = { Text("Private bridge URL") },
            placeholder = { Text("https://machine.example") },
            singleLine = true,
            isError = state.connection is ConnectionState.Error,
          )
          if (state.connection is ConnectionState.Error)
            Text(state.connection.message, color = MaterialTheme.colorScheme.error)
          if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            previousProfileIds = state.addedProfileIds.joinToString("|")
            pendingAdd = true
            requestConnect(label, endpoint)
          },
          enabled = !state.busy,
        ) {
          Text("Connect")
        }
      },
      dismissButton = {
        TextButton(
          onClick = {
            adding = false
            pendingAdd = false
          },
          enabled = !state.busy,
        ) {
          Text("Cancel")
        }
      },
    )
  }
}

private fun ConnectionState.statusText(): String =
  when (this) {
    ConnectionState.Disconnected -> "Not connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Connected -> "Connected"
    ConnectionState.Recovering -> "Recovering"
    is ConnectionState.Error -> "Connection error"
  }
