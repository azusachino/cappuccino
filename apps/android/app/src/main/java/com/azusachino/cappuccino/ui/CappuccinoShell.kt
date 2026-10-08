package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.OutputRow
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
import java.time.ZoneId
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
  SETTINGS(
    com.azusachino.cappuccino.R.string.settings,
    com.azusachino.cappuccino.R.drawable.ic_settings,
  ),
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
  val (color, text) =
    when (status.lowercase()) {
      "working" -> HerdrStatusWorking to "WORKING"
      "done" -> HerdrStatusDone to "DONE"
      "blocked" -> HerdrStatusBlocked to "BLOCKED"
      else -> HerdrStatusIdle to "IDLE"
    }
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = color.copy(alpha = 0.15f),
    border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
      Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

@Composable
fun CappuccinoShell(
  viewModel: ConnectedViewModel,
  timeZone: ZoneId = ZoneId.systemDefault(),
  requestConnect: (String, String) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  CappuccinoScreen(state, viewModel, timeZone, requestConnect)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CappuccinoScreen(
  state: ConnectedUiState,
  actions: ConnectedActions,
  timeZone: ZoneId = ZoneId.systemDefault(),
  requestConnect: (String, String) -> Unit,
) {
  var destination by rememberSaveable { mutableStateOf(Destination.CHATS) }
  var adding by rememberSaveable { mutableStateOf(false) }
  var label by rememberSaveable { mutableStateOf("") }
  var endpoint by rememberSaveable { mutableStateOf("") }
  var pendingAdd by rememberSaveable { mutableStateOf(false) }
  var previousProfileIds by rememberSaveable { mutableStateOf("") }
  val selected = state.selectedAgent
  val outputIdentity = Triple(state.activeProfileId, selected?.machineId, selected?.sessionId)
  val outputListState = key(outputIdentity) { rememberLazyListState() }
  val turns = state.conversationTurns
  val rows = state.stream.rows()
  val collapsedRows = remember(rows) { collapseOutputRows(rows) }
  val displayedContent: List<*> = if (turns.isNotEmpty()) turns else collapsedRows
  var hasNewOutput by remember(outputIdentity) { mutableStateOf(false) }
  var followingOutput by remember(outputIdentity) { mutableStateOf(true) }
  var automaticScroll by remember(outputIdentity) { mutableStateOf(false) }
  val outputScope = rememberCoroutineScope()

  LaunchedEffect(outputListState) {
    var observedManualScroll = false
    snapshotFlow {
      Triple(outputListState.isScrollInProgress, !outputListState.canScrollForward, automaticScroll)
    }
      .collect { (scrolling, atEnd, automatic) ->
        if (scrolling && !automatic) {
          observedManualScroll = true
          followingOutput = false
        } else if (!scrolling && !automatic && observedManualScroll) {
          observedManualScroll = false
          followingOutput = atEnd
          if (atEnd) hasNewOutput = false
        }
      }
  }
  LaunchedEffect(displayedContent, outputIdentity) {
    if (selected != null && displayedContent.isNotEmpty()) {
      if (followingOutput) {
        automaticScroll = true
        try {
          // A bottom marker handles tall last turns and collapsed stream rows alike.
          outputListState.scrollToItem(displayedContent.size)
          hasNewOutput = false
        } finally {
          automaticScroll = false
        }
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
          if (selected != null) {
            Row(
              modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                  selected.label,
                  fontWeight = FontWeight.Bold,
                  style = MaterialTheme.typography.titleMedium,
                  maxLines = 1,
                )
                Text(
                  "${state.profiles.firstOrNull { it.id == state.activeProfileId }?.label ?: "Machine"} · ${selected.sessionId}",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                )
              }
              StatusBadge(state.stream.agentStatus ?: selected.status)
            }
          } else {
            Text(stringResource(destination.title), fontWeight = FontWeight.Bold)
          }
        },
        navigationIcon = {
          if (selected != null) {
            IconButton(onClick = { actions.selectAgent(null) }) {
              Icon(
                painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_back),
                contentDescription = "Back",
              )
            }
          }
        },
        actions = {
          if (selected == null && state.activeProfileId != null) {
            IconButton(onClick = actions::refresh, enabled = !state.busy) {
              Icon(
                painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_refresh),
                contentDescription = "Refresh",
              )
            }
            IconButton(onClick = actions::disconnectProfile, enabled = !state.busy) {
              Icon(
                painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
                contentDescription = "Disconnect",
              )
            }
          }
        },
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
          ),
      )
    },
    floatingActionButton = {
      if (destination == Destination.MACHINES && selected == null) {
        FloatingActionButton(
          onClick = { adding = true },
          containerColor = MaterialTheme.colorScheme.primary,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
          Icon(
            painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_add),
            contentDescription = "Add machine",
          )
        }
      }
    },
    bottomBar = {
      NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
      ) {
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
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          ) {
            Column(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                "Recent agent output · Read-only",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
              )
              Text(
                "${state.profiles.firstOrNull { it.id == state.activeProfileId }?.label ?: "Machine"} · ${selected.sessionId} · ${selected.branch ?: "Branch unknown"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
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

          // If there is an active pending prompt on this agent, show the prompt card prominently
          state.stream.pendingPrompt?.let { card ->
            Box(Modifier.padding(horizontal = 16.dp)) {
              PromptCardView(
                prompt = card,
                onSelectOption = { idx, optId ->
                  actions.answerPrompt(card.promptId, idx, optId, "select_option")
                },
                onCancel = { actions.answerPrompt(card.promptId, null, null, "cancel") },
              )
            }
          }

          if (hasNewOutput)
            Box(
              modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
              contentAlignment = Alignment.CenterEnd,
            ) {
              Surface(
                onClick = {
                  outputScope.launch {
                    followingOutput = true
                    automaticScroll = true
                    try {
                      outputListState.animateScrollToItem(displayedContent.size)
                      hasNewOutput = false
                    } finally {
                      automaticScroll = false
                    }
                  }
                },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 4.dp,
              ) {
                Text(
                  "Jump to latest",
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                  color = MaterialTheme.colorScheme.onPrimary,
                  style = MaterialTheme.typography.labelSmall,
                  fontWeight = FontWeight.Bold,
                )
              }
            }
          SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
            if (turns.isNotEmpty()) {
              LazyColumn(
                state = outputListState,
                modifier =
                  Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("recentOutputList"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(
                  turns,
                  key = {
                    "${state.activeProfileId}:${selected.machineId}:${selected.sessionId}:${it.id}"
                  },
                ) { turn ->
                  ConversationTurnRow(turn, timeZone = timeZone)
                }
                item(key = "conversation-bottom") { Spacer(Modifier.height(1.dp)) }
              }
            } else {
              LazyColumn(
                state = outputListState,
                modifier =
                  Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("recentOutputList"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                items(collapsedRows, key = { "row:${it.key}" }) { row -> OutputItemRow(row) }
                item(key = "stream-bottom") { Spacer(Modifier.height(1.dp)) }
              }
            }
          }
          if (state.stream.entries.isEmpty() && state.conversationTurns.isEmpty())
            Text(
              "No recent output received.",
              Modifier.padding(16.dp),
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

          // Interactive prompt input bar
          PromptInputBar(
            onSend = { text -> actions.submitPrompt(text) },
            enabled = state.connection == ConnectionState.Connected,
          )
        }
        destination == Destination.CHATS -> {
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                "${state.connection.statusText()} · ${state.agents.size} agents",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              state.profiles
                .firstOrNull { it.id == state.activeProfileId }
                ?.let { active ->
                  Text(
                    active.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                  )
                }
            }
          }
          if (state.agents.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxWidth().weight(1f),
              contentAlignment = Alignment.Center,
            ) {
              Text(
                if (state.activeProfileId == null)
                  "Select or add a bridge in Machines to discover agents."
                else "No active agents found on this bridge.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
              )
            }
          } else {
            LazyColumn(
              Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(state.agents, key = { "${it.machineId}:${it.sessionId}" }) { agent ->
                AgentItemCard(
                  agent = agent,
                  machineLabel =
                    state.profiles.firstOrNull { it.id == state.activeProfileId }?.label
                      ?: "Machine",
                  onClick = { actions.selectAgent(agent) },
                )
              }
            }
          }
        }
        destination == Destination.ATTENTION -> {
          val pending = state.stream.pendingPrompt
          if (pending != null) {
            Column(
              Modifier.fillMaxSize().padding(16.dp),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              Text(
                "Requires Attention",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
              )
              PromptCardView(
                prompt = pending,
                onSelectOption = { idx, optId ->
                  actions.answerPrompt(pending.promptId, idx, optId, "select_option")
                },
                onCancel = { actions.answerPrompt(pending.promptId, null, null, "cancel") },
              )
            }
          } else {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                "No Pending Approvals",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
              )
              Text(
                "All agents are running smoothly. Any tool approval or ask_question prompts will appear here.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        destination == Destination.SETTINGS -> {
          SettingsScreen(
            themeMode = state.themeMode,
            onSelectTheme = actions::setThemeMode,
          )
        }
        else -> {
          // Destination.MACHINES
          Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
            ) {
              Text(
                "${state.connection.statusText()} · Protocol v1",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                "Capabilities: stream",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
              )
            }
          }

          val connection = state.connection
          if (connection is ConnectionState.Error) {
            Surface(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.errorContainer,
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
              ) {
                Text(
                  connection.message,
                  modifier = Modifier.weight(1f),
                  color = MaterialTheme.colorScheme.onErrorContainer,
                  style = MaterialTheme.typography.bodySmall,
                )
                if (state.activeProfileId != null) {
                  FilledTonalButton(
                    onClick = actions::retry,
                    modifier = Modifier.padding(start = 8.dp),
                  ) {
                    Text("Retry")
                  }
                }
              }
            }
          }

          if (state.profiles.isEmpty()) {
            Box(
              modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
              contentAlignment = Alignment.Center,
            ) {
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Text(
                  "No machines configured",
                  style = MaterialTheme.typography.titleMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                Text(
                  "Tap the + button below to connect to a private Herdr bridge.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodySmall,
                )
              }
            }
          } else {
            LazyColumn(
              Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              items(state.profiles, key = { it.id }) { profile ->
                val isSelected = state.activeProfileId == profile.id
                Surface(
                  onClick = { actions.selectProfile(profile.id) },
                  shape = RoundedCornerShape(10.dp),
                  color =
                    if (isSelected) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                  border =
                    BorderStroke(
                      1.dp,
                      if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                      else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    ),
                  modifier = Modifier.fillMaxWidth(),
                ) {
                  Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                  ) {
                    Box(
                      modifier =
                        Modifier.size(10.dp)
                          .background(
                            if (isSelected) HerdrStatusWorking else HerdrStatusIdle,
                            CircleShape,
                          )
                    )
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        profile.label,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                      )
                      Text(
                        "${profile.endpoint.base.host} · ${profile.machineId.toString().take(8)}…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                      )
                    }
                    IconButton(
                      onClick = { actions.removeProfile(profile.id) },
                      modifier = Modifier.size(32.dp),
                    ) {
                      Icon(
                        painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                      )
                    }
                  }
                }
              }
            }
          }

          if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
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
            value = label,
            onValueChange = { label = it },
            label = { Text("Label (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              ),
          )
          OutlinedTextField(
            value = endpoint,
            onValueChange = { endpoint = it },
            label = { Text("Private bridge URL") },
            placeholder = { Text("https://machine.example") },
            singleLine = true,
            isError = state.connection is ConnectionState.Error,
            modifier = Modifier.fillMaxWidth(),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              ),
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

@Composable
private fun SettingsScreen(
  themeMode: ThemeMode,
  onSelectTheme: (ThemeMode) -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Text(
      "Appearance",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.Bold,
      color = MaterialTheme.colorScheme.primary,
    )
    Surface(
      modifier = Modifier.fillMaxWidth(),
      shape = RoundedCornerShape(10.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
    ) {
      Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text(
          "Theme Mode",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          "Choose between Herdr lamp-lit dark mode, clean light mode, or match system settings.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          ThemeMode.entries.forEach { mode ->
            FilterChip(
              selected = themeMode == mode,
              onClick = { onSelectTheme(mode) },
              label = {
                Text(
                  when (mode) {
                    ThemeMode.SYSTEM -> "System"
                    ThemeMode.LIGHT -> "Light"
                    ThemeMode.DARK -> "Dark"
                  }
                )
              },
              colors =
                FilterChipDefaults.filterChipColors(
                  selectedContainerColor = MaterialTheme.colorScheme.primary,
                  selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                ),
            )
          }
        }
      }
    }
  }
}

private fun collapseOutputRows(rows: List<OutputRow>): List<OutputRow> {
  if (rows.isEmpty()) return emptyList()
  val result = mutableListOf<OutputRow>()
  for (row in rows) {
    if (row.gap) {
      result += row
      continue
    }
    val currentParsed = TranscriptParser.parseLine(row.text)
    if (currentParsed.role == TranscriptRole.STATUS) {
      if (currentParsed.text.isBlank()) continue
      // Chrome/status lines (working · ..., [hh:mm:ss] ... running) represent transient TUI state.
      // Remove any previously recorded status lines anywhere in the transcript to keep only the
      // latest.
      result.removeAll {
        !it.gap && TranscriptParser.parseLine(it.text).role == TranscriptRole.STATUS
      }
      result += row
      continue
    }

    if (currentParsed.role == TranscriptRole.USER) {
      // If the last non-gap row was already the identical user prompt, collapse it
      val lastNonGap = result.lastOrNull { !it.gap }
      if (lastNonGap != null) {
        val lastParsed = TranscriptParser.parseLine(lastNonGap.text)
        if (lastParsed.role == TranscriptRole.USER && lastParsed.text == currentParsed.text) {
          result[result.lastIndexOf(lastNonGap)] = row
          continue
        }
      }
    }

    val last = result.lastOrNull()
    if (last != null && !last.gap && shouldCollapseStreamTails(last.text, row.text)) {
      result[result.lastIndex] = row
    } else {
      result += row
    }
  }
  return result
}

private fun shouldCollapseStreamTails(prevText: String, currentText: String): Boolean {
  if (prevText == currentText) return true
  val p = prevText.trim()
  val c = currentText.trim()
  if (p.isEmpty() || c.isEmpty()) return false
  if (c.startsWith(p) || p.startsWith(c)) return true
  val pParsed = TranscriptParser.parseLine(p)
  val cParsed = TranscriptParser.parseLine(c)
  if (pParsed.role == TranscriptRole.STATUS && cParsed.role == TranscriptRole.STATUS) return true
  if (pParsed.role == TranscriptRole.TOOL && cParsed.role == TranscriptRole.TOOL) {
    if (pParsed.toolName == cParsed.toolName && pParsed.toolName != null) return true
  }
  return false
}

@Composable
private fun OutputItemRow(row: OutputRow) {
  if (row.gap) {
    Box(
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      contentAlignment = Alignment.Center,
    ) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
      ) {
        Text(
          row.text,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
          color = MaterialTheme.colorScheme.primary,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    return
  }

  val parsed = remember(row.text) { TranscriptParser.parseLine(row.text) }

  when (parsed.role) {
    TranscriptRole.USER -> {
      Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        contentAlignment = Alignment.CenterEnd,
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Text(
              "❯",
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.bodySmall,
            )
            Text(
              parsed.text,
              color = MaterialTheme.colorScheme.onSurface,
              fontWeight = FontWeight.Medium,
              style = MaterialTheme.typography.bodySmall,
            )
          }
        }
      }
    }
    TranscriptRole.TOOL -> {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
      ) {
        Row(
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            parsed.toolGlyph ?: "●",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelSmall,
          )
          Text(
            parsed.toolName ?: "tool",
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
          parsed.toolArg?.let { arg ->
            Text(
              "($arg)",
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.bodySmall,
              maxLines = 1,
            )
          }
        }
      }
    }
    TranscriptRole.STATUS -> {
      if (parsed.text.isNotBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
          border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            Box(modifier = Modifier.size(6.dp).background(HerdrStatusWorking, CircleShape))
            Text(
              parsed.text,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              fontFamily = FontFamily.Monospace,
              style = MaterialTheme.typography.labelSmall,
              maxLines = 2,
            )
          }
        }
      }
    }
    TranscriptRole.AGENT -> {
      if (parsed.text.isNotBlank()) {
        AgentMessageContent(parsed.text)
      }
    }
  }
}

@Composable
private fun AgentMessageContent(text: String) {
  val trimmed = text.trim()
  when {
    trimmed.startsWith("### ") -> {
      Text(
        text = trimmed.removePrefix("### ").trim(),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    trimmed.startsWith("## ") -> {
      Text(
        text = trimmed.removePrefix("## ").trim(),
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    trimmed.startsWith("# ") -> {
      Text(
        text = trimmed.removePrefix("# ").trim(),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
      )
    }
    trimmed.startsWith("• ") || trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
      val bulletText = trimmed.substring(2).trim()
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          "•",
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.bodyMedium,
        )
        Text(
          bulletText,
          color = MaterialTheme.colorScheme.onSurface,
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
    trimmed.startsWith("```") || (trimmed.startsWith("    ") && !trimmed.startsWith("     ")) -> {
      Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
      ) {
        Text(
          text = trimmed.removePrefix("```").removeSuffix("```").trim(),
          modifier = Modifier.padding(8.dp),
          color = MaterialTheme.colorScheme.onSurface,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.bodySmall,
        )
      }
    }
    else -> {
      Text(
        text = trimmed,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
      )
    }
  }
}

@Composable
private fun AgentItemCard(
  agent: AgentRow,
  machineLabel: String,
  onClick: () -> Unit,
) {
  val railColor =
    when (agent.status.lowercase()) {
      "working" -> HerdrStatusWorking
      "done" -> HerdrStatusDone
      "blocked" -> HerdrStatusBlocked
      else -> HerdrStatusIdle
    }
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    shape = RoundedCornerShape(10.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(modifier = Modifier.width(4.dp).fillMaxHeight().background(railColor))
      Box(
        modifier =
          Modifier.padding(start = 12.dp, end = 8.dp)
            .size(36.dp)
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .border(
              1.dp,
              MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
              RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text =
            when (agent.agent.lowercase()) {
              "pi" -> "π"
              "agy" -> "A"
              "claude" -> "C"
              "codex" -> "X"
              else -> agent.agent.take(1).uppercase()
            },
          color = MaterialTheme.colorScheme.primary,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium,
        )
      }
      Column(
        modifier = Modifier.weight(1f).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          agent.label,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            "$machineLabel · ${agent.sessionId} · ${agent.status}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          agent.branch?.let { branch ->
            Surface(
              shape = RoundedCornerShape(4.dp),
              color = MaterialTheme.colorScheme.surface,
              border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
            ) {
              Text(
                branch,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
              )
            }
          }
        }
      }
      StatusBadge(agent.status, modifier = Modifier.padding(end = 12.dp))
    }
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

@Composable
fun PromptCardView(
  prompt: PromptCard,
  onSelectOption: (Int, String) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.surfaceVariant,
    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = prompt.title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
        )
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
        ) {
          Text(
            text = prompt.type.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
          )
        }
      }

      prompt.message?.let { msg ->
        Text(
          text = msg,
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }

      prompt.command?.let { cmd ->
        Surface(
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surface,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = cmd,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(8.dp),
          )
        }
      }

      if (prompt.options.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          prompt.options.forEachIndexed { index, option ->
            FilledTonalButton(
              onClick = { onSelectOption(index, option.id) },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(8.dp),
            ) {
              Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.Start,
              ) {
                Text(
                  text = "${index + 1}. ${option.label}",
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                )
                option.description?.let { desc ->
                  Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              }
            }
          }
        }
      }

      TextButton(
        onClick = onCancel,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text("Cancel / Dismiss")
      }
    }
  }
}

@Composable
fun PromptInputBar(
  onSend: (String) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
) {
  var input by rememberSaveable { mutableStateOf("") }
  Surface(
    modifier = modifier.fillMaxWidth(),
    tonalElevation = 3.dp,
    color = MaterialTheme.colorScheme.surface,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        placeholder = { Text("Prompt agent…") },
        modifier = Modifier.weight(1f),
        enabled = enabled,
        singleLine = true,
        shape = RoundedCornerShape(20.dp),
        colors =
          OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
          ),
      )
      IconButton(
        onClick = {
          if (input.isNotBlank()) {
            onSend(input)
            input = ""
          }
        },
        enabled = enabled && input.isNotBlank(),
      ) {
        Icon(
          painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_chat),
          contentDescription = "Send",
          tint =
            if (enabled && input.isNotBlank()) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline,
        )
      }
    }
  }
}

@Composable
fun ConversationTurnRow(
  turn: ConversationTurn,
  modifier: Modifier = Modifier,
  timeZone: ZoneId = ZoneId.systemDefault(),
) {
  val isUser = turn.role.lowercase() == "user"
  Column(
    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color =
        if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        else MaterialTheme.colorScheme.surfaceVariant,
      border =
        BorderStroke(
          1.dp,
          if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
          else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
      modifier = Modifier.fillMaxWidth(0.92f),
    ) {
      Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (isUser) "You" else "Assistant",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color =
              if (isUser) MaterialTheme.colorScheme.primary
              else MaterialTheme.colorScheme.onSurface,
          )
          turn.timestamp
            ?.let { formatConversationTimestamp(it, timeZone) }
            ?.let { timestamp ->
              Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
        }

        turn.text?.let { MarkdownText(it) }
        turn.parts.forEachIndexed { index, part ->
          key(turn.id, index) {
            when (part) {
              is ConversationPart.Text -> MarkdownText(part.text)
              else -> ConversationDetails(part)
            }
          }
        }
      }
    }
  }
}
