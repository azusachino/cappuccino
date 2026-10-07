package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.OutputRow
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
              StatusBadge(selected.status)
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
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween,
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
          if (hasNewOutput)
            Box(
              modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
              contentAlignment = Alignment.CenterEnd,
            ) {
              Surface(
                onClick = {
                  outputScope.launch {
                    val last = state.stream.rows().lastIndex
                    if (last >= 0) outputListState.animateScrollToItem(last)
                    hasNewOutput = false
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
            val rows = state.stream.rows()
            val collapsedRows = remember(rows) { collapseOutputRows(rows) }
            LazyColumn(
              state = outputListState,
              modifier =
                Modifier.fillMaxSize().padding(horizontal = 16.dp).testTag("recentOutputList"),
              verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              items(collapsedRows, key = { it.key }) { row -> OutputItemRow(row) }
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
            Text(
              if (state.activeProfileId == null)
                "Add a private bridge in Machines to discover agents."
              else "No agents found. Refresh to try again.",
              Modifier.padding(16.dp),
            )
          }
          LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            items(state.agents, key = { "${it.machineId}:${it.sessionId}" }) { agent ->
              AgentItemCard(
                agent = agent,
                machineLabel =
                  state.profiles.firstOrNull { it.id == state.activeProfileId }?.label ?: "Machine",
                onClick = { actions.selectAgent(agent) },
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
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
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
        destination == Destination.SETTINGS -> {
          SettingsScreen(
            themeMode = state.themeMode,
            onSelectTheme = actions::setThemeMode,
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
                headlineContent = { Text(profile.label, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                  Text("${profile.endpoint.base.host} · ${profile.machineId}")
                },
                trailingContent = {
                  TextButton(onClick = { actions.removeProfile(profile.id) }) { Text("Remove") }
                },
                colors =
                  ListItemDefaults.colors(
                    containerColor =
                      if (state.activeProfileId == profile.id)
                        MaterialTheme.colorScheme.surfaceVariant
                      else MaterialTheme.colorScheme.surface
                  ),
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
        Text(
          parsed.text,
          modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
          color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    TranscriptRole.AGENT -> {
      if (parsed.text.isNotBlank()) {
        Surface(
          modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
          shape = RoundedCornerShape(6.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
          border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
        ) {
          Text(
            parsed.text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
          )
        }
      }
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
    modifier =
      Modifier.fillMaxWidth()
        .padding(horizontal = 16.dp)
        .selectable(
          selected = false,
          onClick = onClick,
          role = Role.Button,
        ),
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
