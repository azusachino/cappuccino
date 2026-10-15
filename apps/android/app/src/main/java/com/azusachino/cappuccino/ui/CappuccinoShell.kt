package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.core.COMMON_SLASH_COMMANDS
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.OutputRow
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.TranscriptSegment
import com.azusachino.cappuccino.core.matchingSlashCommands
import com.azusachino.cappuccino.core.slashCommand
import com.azusachino.cappuccino.core.slashCommandWarning
import com.azusachino.cappuccino.core.transcriptSegments
import com.azusachino.cappuccino.core.visibleInChat
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.SessionStats
import com.azusachino.cappuccino.io.machineConnection
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
  APPEARANCE(
    com.azusachino.cappuccino.R.string.appearance,
    com.azusachino.cappuccino.R.drawable.ic_settings,
  ),
  SETTINGS(
    com.azusachino.cappuccino.R.string.settings,
    com.azusachino.cappuccino.R.drawable.ic_settings,
  ),
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
  val badge = agentStatusStyle(status, MaterialTheme.colorScheme.background.luminance() < 0.5f)
  Surface(modifier = modifier, shape = CircleShape, color = badge.background) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
      Box(modifier = Modifier.size(6.dp).background(badge.foreground, CircleShape))
      Text(
        text = badge.label,
        style = MaterialTheme.typography.labelSmall,
        color = badge.foreground,
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
  CappuccinoScreen(
    state,
    viewModel,
    timeZone,
    savedHistoryAction = viewModel::openSavedConversation,
    requestConnect = requestConnect,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CappuccinoScreen(
  state: ConnectedUiState,
  actions: ConnectedActions,
  timeZone: ZoneId = ZoneId.systemDefault(),
  savedHistoryAction: ((java.util.UUID, String) -> Unit)? = null,
  requestConnect: (String, String) -> Unit,
) {
  var destination by rememberSaveable { mutableStateOf(Destination.MACHINES) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  LaunchedEffect(actions, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        kotlinx.coroutines.delay(5_000)
        actions.refreshAll()
      }
    }
  }
  val machineTreeState = rememberLazyListState()
  val mainScreenState = rememberSaveableStateHolder()
  val composerState = rememberSaveableStateHolder()
  val readerState = rememberSaveableStateHolder()
  var machineMenuId by remember { mutableStateOf<String?>(null) }
  var confirmingNewSession by rememberSaveable { mutableStateOf(false) }
  var showingConnection by rememberSaveable { mutableStateOf(false) }
  var showingTechnical by rememberSaveable { mutableStateOf(false) }
  var detailsProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var namingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var adding by rememberSaveable { mutableStateOf(false) }
  var label by rememberSaveable { mutableStateOf("") }
  var endpoint by rememberSaveable { mutableStateOf("") }
  var pendingAdd by rememberSaveable { mutableStateOf(false) }
  var previousProfileIds by rememberSaveable { mutableStateOf("") }
  val selected = state.selectedAgent
  val selectedProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
  val selectedStatus =
    if (state.stream.pendingPrompt != null) "blocked"
    else (state.stream.agentStatus ?: selected?.status)?.lowercase()
  val selectedIdle = selectedStatus !in setOf("working", "blocked")
  val agentControl =
    state.offlineSavedAt == null &&
      state.connection == ConnectionState.Connected &&
      state.bridgeDetails[state.activeProfileId]?.capabilities?.get("agent_control") == true
  val detailsProfile =
    if (detailsProfileId == null) selectedProfile
    else state.profiles.firstOrNull { it.id == detailsProfileId }
  val namingProfile = state.profiles.firstOrNull { it.id == namingProfileId } ?: selectedProfile
  var namingMachine by rememberSaveable { mutableStateOf(false) }
  var machineNameDraft by rememberSaveable { mutableStateOf("") }
  val scopedConversation = selected?.let { state.conversationId ?: it.sessionId }
  val outputIdentity = Triple(state.activeProfileId, selected?.machineId, scopedConversation)
  val conversationKey = "${state.activeProfileId}|${selected?.machineId}|$scopedConversation"
  val outputStates =
    rememberSaveable(
      saver =
        mapSaver<MutableMap<String, LazyListState>>(
          save = { states ->
            states.mapValues { (_, value) ->
              listOf(value.firstVisibleItemIndex, value.firstVisibleItemScrollOffset)
            }
          },
          restore = { states ->
            states
              .mapValues { (_, value) ->
                val position = value as List<*>
                LazyListState(position[0] as Int, position[1] as Int)
              }
              .toMutableMap()
          },
        )
    ) {
      mutableMapOf<String, LazyListState>()
    }
  val outputListState = outputStates.getOrPut(conversationKey) { LazyListState() }
  // Following is being near the bottom, as in pi-pocket: no separate switch to keep in sync.
  val followSlack = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() }
  val nearBottom by
    remember(outputListState, followSlack) {
      derivedStateOf {
        val layout = outputListState.layoutInfo
        val last = layout.visibleItemsInfo.lastOrNull()
        !outputListState.canScrollForward ||
          (last != null &&
            last.index == layout.totalItemsCount - 1 &&
            last.offset + last.size - layout.viewportEndOffset <= followSlack)
      }
    }
  val restoringReadingPosition =
    remember(outputIdentity) {
      outputListState.firstVisibleItemIndex > 0 || outputListState.firstVisibleItemScrollOffset > 0
    }
  var followModes by rememberSaveable { mutableStateOf(emptyMap<String, Boolean>()) }
  val turns = state.conversationTurns
  val chatTurns =
    remember(turns) {
      turns.filter { turn -> !turn.text.isNullOrBlank() || turn.parts.isNotEmpty() }
    }
  // pi-pocket: everything after the last person's message is the newest turn.
  val newestTurnIds =
    remember(chatTurns) {
      val lastUser = chatTurns.indexOfLast { it.role.lowercase() == "user" }
      chatTurns.drop(lastUser + 1).map { it.id }.toSet()
    }
  val rows = state.stream.rows()
  val collapsedRows = remember(rows) { collapseOutputRows(rows) }
  val displayedContent: List<*> = if (turns.isNotEmpty()) chatTurns else collapsedRows
  val followContent: List<*> =
    if (turns.isNotEmpty()) com.azusachino.cappuccino.core.conversationFollowContent(turns)
    else collapsedRows
  var hasNewOutput by remember(outputIdentity) { mutableStateOf(false) }
  var followingOutput by
    remember(outputIdentity) { mutableStateOf(followModes[conversationKey] ?: true) }
  LaunchedEffect(conversationKey, followingOutput) {
    followModes = followModes + (conversationKey to followingOutput)
  }
  var automaticScroll by remember(outputIdentity) { mutableStateOf(false) }
  var textLayoutRevision by remember(outputIdentity) { mutableStateOf(0) }
  val onTextLayoutChanged: () -> Unit = remember(outputIdentity) { { textLayoutRevision += 1 } }
  var observedFollowContent by remember(outputIdentity) { mutableStateOf<List<*>?>(null) }
  val outputScope = rememberCoroutineScope()

  // A finger or fling owns the position; following is decided only once it settles.
  LaunchedEffect(outputListState) {
    var observedManualScroll = false
    snapshotFlow { Triple(outputListState.isScrollInProgress, nearBottom, automaticScroll) }
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
  LaunchedEffect(
    followContent,
    displayedContent.size,
    textLayoutRevision,
    outputIdentity,
    followingOutput,
  ) {
    val newContent = observedFollowContent != followContent
    observedFollowContent = followContent
    if (selected != null && followContent.isNotEmpty()) {
      if (followingOutput) {
        automaticScroll = true
        try {
          // A bottom marker handles tall last turns and collapsed stream rows alike.
          outputListState.scrollToItem(displayedContent.size, 10000)
          hasNewOutput = false
        } finally {
          automaticScroll = false
        }
      } else if (newContent) {
        hasNewOutput = true
      }
    }
  }
  LaunchedEffect(state.addedProfileIds, pendingAdd) {
    if (pendingAdd && state.addedProfileIds.any { it !in previousProfileIds.split('|') }) {
      adding = false
      pendingAdd = false
      endpoint = ""
      destination = Destination.MACHINES
    }
  }
  // Keep the old saved CHATS enum readable, but migrate it into the machine tree.
  LaunchedEffect(destination) {
    if (destination == Destination.CHATS || destination == Destination.ATTENTION) {
      destination = Destination.MACHINES
    }
  }
  LaunchedEffect(showingConnection) { if (showingConnection) followingOutput = false }
  BackHandler(enabled = selected == null && destination == Destination.APPEARANCE) {
    destination = Destination.SETTINGS
  }
  BackHandler(enabled = selected != null && !showingConnection) { actions.selectAgent(null) }
  if (showingConnection && detailsProfile != null) {
    if (showingTechnical) {
      TechnicalConnectionScreen(detailsProfile, state, onBack = { showingTechnical = false })
      return
    }
    MachineInformation(
      profile = detailsProfile,
      state = state,
      onDismiss = {
        showingConnection = false
        showingTechnical = false
      },
      onTechnicalDetails = { showingTechnical = true },
      onRename = {
        showingConnection = false
        namingProfileId = detailsProfile.id
        machineNameDraft = detailsProfile.label
        namingMachine = true
      },
    )
  }

  if (namingMachine && namingProfile != null) {
    AlertDialog(
      onDismissRequest = { namingMachine = false },
      title = { Text("Machine name") },
      text = {
        OutlinedTextField(
          value = machineNameDraft,
          onValueChange = { machineNameDraft = it },
          label = { Text("Name") },
          singleLine = true,
        )
      },
      confirmButton = {
        TextButton(
          enabled = machineNameDraft.isNotBlank(),
          onClick = {
            actions.renameProfile(namingProfile.id, machineNameDraft)
            namingMachine = false
          },
        ) {
          Text("Save")
        }
      },
      dismissButton = { TextButton(onClick = { namingMachine = false }) { Text("Cancel") } },
    )
  }

  if (confirmingNewSession && selected != null) {
    AlertDialog(
      onDismissRequest = { confirmingNewSession = false },
      title = { Text("Start a new session?") },
      text = {
        Text(
          "${selected.agent.replaceFirstChar { it.uppercase() }} starts a fresh conversation in " +
            "the same terminal."
        )
      },
      confirmButton = {
        TextButton(
          enabled = agentControl && selectedIdle,
          onClick = {
            actions.controlAgent("new_session")
            confirmingNewSession = false
          },
        ) {
          Text("New session")
        }
      },
      dismissButton = { TextButton(onClick = { confirmingNewSession = false }) { Text("Cancel") } },
    )
  }

  mainScreenState.SaveableStateProvider("main-screen") {
    Scaffold(
      topBar = {
        TopAppBar(
          title = {
            if (selected != null) {
              val status =
                agentStatusStyle(
                  if (state.stream.pendingPrompt != null) "blocked"
                  else state.stream.agentStatus ?: selected.status,
                  MaterialTheme.colorScheme.background.luminance() < 0.5f,
                )
              Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    painterResource(agentIconResource(selected.agent)),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.appColors.accent,
                  )
                  Text(
                    selected.label,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
                // Status keeps its color as a square beside its word, never color alone.
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  StatusSquare(status.background)
                  Text(
                    listOfNotNull(
                        status.label,
                        selectedProfile?.label,
                        selected.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf {
                          it.isNotBlank()
                        },
                        selected.branch?.takeIf { it.isNotBlank() },
                      )
                      .joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.appColors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                }
              }
            } else {
              Text(
                stringResource(destination.title),
                fontWeight = FontWeight.Bold,
              )
            }
          },
          navigationIcon = {
            if (selected == null && destination == Destination.APPEARANCE) {
              IconButton(onClick = { destination = Destination.SETTINGS }) {
                Icon(
                  painterResource(com.azusachino.cappuccino.R.drawable.ic_back),
                  contentDescription = "Back to settings",
                )
              }
            } else if (selected != null) {
              IconButton(onClick = { actions.selectAgent(null) }) {
                Icon(
                  painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_back),
                  contentDescription = "Back",
                )
              }
            }
          },
          // M3 app bar defaults apply: no colors override (android-material3.md region 1).
          actions = {
            if (destination == Destination.MACHINES && selected == null) {
              IconButton(onClick = { adding = true }) {
                Icon(
                  painterResource(com.azusachino.cappuccino.R.drawable.ic_add),
                  contentDescription = "Add machine",
                )
              }
            }
            if (selectedProfile != null && selected != null) {
              IconButton(
                onClick = {
                  detailsProfileId = selectedProfile.id
                  showingConnection = true
                }
              ) {
                Icon(
                  painterResource(com.azusachino.cappuccino.R.drawable.ic_more),
                  contentDescription = "Connection details",
                )
              }
            }
          },
        )
      },
      bottomBar = {
        if (selected == null)
          NavigationBar {
            listOf(Destination.MACHINES, Destination.SETTINGS).forEach { item ->
              NavigationBarItem(
                selected =
                  destination == item ||
                    (item == Destination.SETTINGS && destination == Destination.APPEARANCE),
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
        verticalArrangement = Arrangement.spacedBy(if (selected == null) 12.dp else 4.dp),
      ) {
        when {
          selected != null -> {
            state.offlineSavedAt?.let { savedAt ->
              Text(
                "Saved on this device · ${formatConversationTimestamp(java.time.Instant.ofEpochMilli(savedAt).toString(), timeZone)} · Read-only",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            // Transcript metadata as plain text lines under the app bar; cardless
            // (android-material3.md region 2).
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              if (state.conversationSource in setOf("scrollback", "terminal_scrollback"))
                Text(
                  "Recent output · Scrollback fallback",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              else if (turns.isEmpty() && rows.isNotEmpty() && state.conversationSource == null)
                Text(
                  "Recent output · History not verified",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (val connection = state.connection) {
              ConnectionState.Connecting ->
                Column(
                  Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                  verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Text(
                    "Connecting…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                  LinearProgressIndicator(Modifier.fillMaxWidth())
                }
              ConnectionState.Recovering ->
                Text(
                  "Reconnecting; earlier output may be missing",
                  Modifier.padding(16.dp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              is ConnectionState.Error ->
                Text(
                  connection.message,
                  Modifier.padding(16.dp),
                  color = MaterialTheme.colorScheme.error,
                )
              else -> Unit
            }

            if (state.conversationSource == "scrollback") {
              Text(
                "Live terminal output · canonical session unavailable",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
              readerState.SaveableStateProvider(conversationKey) {
                val historyOmitted =
                  state.omittedConversationTurns > 0 || state.omittedToolOperations > 0
                Box(Modifier.weight(1f).fillMaxWidth()) {
                  SelectionContainer(Modifier.fillMaxSize()) {
                    if (
                      turns.isEmpty() &&
                        state.conversationSource == null &&
                        restoringReadingPosition
                    ) {
                      Box(Modifier.fillMaxSize()) {
                        Text("Loading history…", Modifier.padding(16.dp))
                      }
                    } else if (turns.isNotEmpty()) {
                      LazyColumn(
                        state = outputListState,
                        modifier =
                          Modifier.fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag("recentOutputList"),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                      ) {
                        items(
                          chatTurns,
                          key = {
                            "${state.activeProfileId}:${selected.machineId}:${selected.sessionId}:${it.id}"
                          },
                        ) { turn ->
                          if (historyOmitted && turn.id == chatTurns.firstOrNull()?.id) {
                            Text(
                              "Older messages aren't loaded.",
                              Modifier.padding(vertical = 12.dp),
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                          }
                          ConversationTurnRow(
                            turn,
                            timeZone = timeZone,
                            assistantLabel = selected.agent.replaceFirstChar { it.uppercase() },
                            onInspect = { followingOutput = false },
                            showToolsInline = true,
                            onTextSizeChanged = onTextLayoutChanged,
                            newest = turn.id in newestTurnIds,
                          )
                        }
                        // The live line lives in the bottom marker so follow targets stay put.
                        item(key = "conversation-bottom") {
                          Column {
                            val liveStatus =
                              if (state.stream.pendingPrompt != null) "blocked"
                              else (state.stream.agentStatus ?: selected.status).lowercase()
                            if (
                              state.offlineSavedAt == null &&
                                liveStatus in setOf("working", "blocked")
                            )
                              LiveStatusLine(
                                if (liveStatus == "working") "Working"
                                else "Waiting for your answer",
                                if (liveStatus == "working") MaterialTheme.appColors.working
                                else MaterialTheme.appColors.warn,
                                state.stream.agentStatusDetail,
                              )
                            Spacer(Modifier.height(8.dp))
                          }
                        }
                      }
                    } else {
                      LazyColumn(
                        state = outputListState,
                        modifier =
                          Modifier.fillMaxSize()
                            .padding(horizontal = 16.dp)
                            .testTag("recentOutputList"),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                      ) {
                        items(collapsedRows, key = { "row:${it.key}" }) { row ->
                          OutputItemRow(row, onInspect = { followingOutput = false })
                        }
                        item(key = "stream-bottom") { Spacer(Modifier.height(8.dp)) }
                      }
                    }
                  }
                  if (!nearBottom && displayedContent.isNotEmpty())
                    JumpToLatest(
                      newOutput = hasNewOutput,
                      modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                      onClick = {
                        outputScope.launch {
                          followingOutput = true
                          automaticScroll = true
                          try {
                            outputListState.scrollToItem(displayedContent.size, 10000)
                            hasNewOutput = false
                          } finally {
                            automaticScroll = false
                          }
                        }
                      },
                    )
                }
              }
              if (state.stream.entries.isEmpty() && state.conversationTurns.isEmpty())
                Text(
                  "No recent output received.",
                  Modifier.padding(16.dp),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

              // A waiting prompt sits by the composer, after the output that asked it, so the
              // transcript keeps the top of the screen and the answer is within thumb reach.
              state.actionError?.let {
                Text(
                  it,
                  Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                  color = MaterialTheme.colorScheme.error,
                )
              }
              state.stream.pendingPrompt?.let { card ->
                val answersEnabled =
                  state.bridgeDetails[state.activeProfileId]?.capabilities?.get("prompt_answers") !=
                    false
                Box(
                  Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("pendingPrompt")
                ) {
                  if (card.questions.isNotEmpty())
                    QuestionFormCard(
                      prompt = card,
                      enabled = answersEnabled,
                      onSubmit = { answers -> actions.answerQuestions(card.promptId, answers) },
                      onCancel = { actions.answerPrompt(card.promptId, null, null, "cancel") },
                    )
                  else
                    PromptCardView(
                      prompt = card,
                      enabled = answersEnabled,
                      onSelectOption = { idx, optId ->
                        actions.answerPrompt(card.promptId, idx, optId, "select_option")
                      },
                      onCancel = { actions.answerPrompt(card.promptId, null, null, "cancel") },
                    )
                }
              }

              // Existing live prompt route; saved readers remain read-only.
              if (
                state.bridgeDetails[state.activeProfileId]?.capabilities?.get("prompt_input") ==
                  false
              ) {
                Text("This bridge can't receive messages.", Modifier.padding(horizontal = 16.dp))
              }
              if (state.offlineSavedAt == null)
                composerState.SaveableStateProvider(conversationKey) {
                  PromptInputBar(
                    onSend = { text, confirmed -> actions.submitPrompt(text, confirmed) },
                    onStop =
                      if (agentControl && selectedStatus == "working") {
                        { actions.controlAgent("interrupt") }
                      } else null,
                    onNewSession =
                      if (agentControl) {
                        { confirmingNewSession = true }
                      } else null,
                    newSessionEnabled = selectedIdle,
                    model = state.sessionStats?.model,
                    thinkingLevel = state.sessionStats?.thinkingLevel,
                    placeholder = "Message ${selected.agent.replaceFirstChar { it.uppercase() }}…",
                    enabled =
                      state.connection == ConnectionState.Connected &&
                        state.bridgeDetails[state.activeProfileId]
                          ?.capabilities
                          ?.get("prompt_input") != false,
                  )
                  state.sessionStats?.let { SessionStatusLine(it) }
                }
            }
          }
          destination == Destination.APPEARANCE -> {
            AppAppearanceSettings(
              state.themeMode,
              state.appPalette,
              actions::setThemeMode,
              actions::setAppPalette,
            )
          }
          destination == Destination.SETTINGS -> {
            SettingsScreen(
              themeMode = state.themeMode,
              appPalette = state.appPalette,
              onOpenAppearance = { destination = Destination.APPEARANCE },
              onSelectTheme = actions::setThemeMode,
              onReloadMachines = actions::refreshAll,
              savedConversations = state.savedConversations,
              savedMachineNames = state.profiles.associate { it.machineId to it.label },
              offlineBytes = state.offlineBytes,
              offlineProblem = state.offlineProblem,
              onOpenSaved = savedHistoryAction,
              timeZone = timeZone,
              connections =
                state.profiles.map { profile ->
                  SettingsConnection(
                    profile.id,
                    profile.label,
                    state.machineConnection(profile).connection.statusText(),
                  )
                },
              onConnectionDetails = { profileId ->
                detailsProfileId = profileId
                showingConnection = true
              },
            )
          }
          else -> {
            // Destination.MACHINES
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
                state = machineTreeState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                items(state.profiles, key = { it.id }) { profile ->
                  val machine = state.machineConnection(profile)
                  Column {
                    Surface(
                      onClick = {
                        detailsProfileId = profile.id
                        showingConnection = true
                      },
                      shape = MaterialTheme.shapes.medium,
                      modifier = Modifier.fillMaxWidth(),
                    ) {
                      ListItem(
                        headlineContent = {
                          Text(
                            profile.label.takeUnless { it == profile.endpoint.base.host }
                              ?: "Saved machine",
                            fontWeight = FontWeight.SemiBold,
                          )
                        },
                        supportingContent = { Text(machine.connection.statusText()) },
                        leadingContent = {
                          Icon(
                            painterResource(com.azusachino.cappuccino.R.drawable.ic_machine),
                            contentDescription = null,
                          )
                        },
                        trailingContent = {
                          Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                              onClick = { actions.refreshProfile(profile.id) },
                              enabled = !machine.busy,
                            ) {
                              Icon(
                                painterResource(com.azusachino.cappuccino.R.drawable.ic_refresh),
                                contentDescription = "Refresh ${profile.label}",
                              )
                            }
                            IconButton(
                              onClick = { actions.disconnectMachine(profile.id) },
                              enabled = machine.connection != ConnectionState.Disconnected,
                            ) {
                              Icon(
                                painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
                                contentDescription = "Disconnect ${profile.label}",
                              )
                            }
                            Box {
                              IconButton(onClick = { machineMenuId = profile.id }) {
                                Icon(
                                  painterResource(com.azusachino.cappuccino.R.drawable.ic_more),
                                  contentDescription = "Machine actions",
                                )
                              }
                              DropdownMenu(
                                expanded = machineMenuId == profile.id,
                                onDismissRequest = { machineMenuId = null },
                              ) {
                                DropdownMenuItem(
                                  text = { Text("Connection details") },
                                  onClick = {
                                    detailsProfileId = profile.id
                                    showingConnection = true
                                    machineMenuId = null
                                  },
                                )
                                DropdownMenuItem(
                                  text = { Text("Rename") },
                                  onClick = {
                                    namingProfileId = profile.id
                                    machineNameDraft = profile.label
                                    namingMachine = true
                                    machineMenuId = null
                                  },
                                )
                                DropdownMenuItem(
                                  text = { Text("Remove") },
                                  onClick = {
                                    actions.removeProfile(profile.id)
                                    machineMenuId = null
                                  },
                                )
                              }
                            }
                          }
                        },
                      )
                    }
                    Column(Modifier.padding(start = 24.dp)) {
                      if (
                        machine.busy && machine.agents.isEmpty() ||
                          machine.connection == ConnectionState.Connecting
                      ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                      } else if (machine.connection != ConnectionState.Connected) {
                        if (machine.connection is ConnectionState.Error)
                          Text(
                            machine.connection.message,
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.error,
                          )
                      } else {
                        val agents = machine.agents.filter { it.machineId == profile.machineId }
                        if (agents.isEmpty()) Text("No active agents", Modifier.padding(16.dp))
                        agents.forEach { agent ->
                          key(profile.id, agent.sessionId) {
                            AgentItemCard(
                              agent,
                              onClick = {
                                actions.selectProfile(profile.id)
                                actions.selectAgent(agent)
                              },
                            )
                          }
                        }
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
            label = { Text("Bridge address") },
            placeholder = { Text("https://machine.example") },
            singleLine = true,
            isError = state.addError != null,
            modifier = Modifier.fillMaxWidth(),
            colors =
              OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surface,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
              ),
          )
          state.addError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
private fun OutputItemRow(row: OutputRow, onInspect: () -> Unit = {}) {
  if (row.gap) {
    // Gap markers are plain centered lines on the background (region 5d).
    Text(
      row.text,
      modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
      textAlign = TextAlign.Center,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.labelSmall,
    )
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
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.primaryContainer,
        ) {
          Text(
            parsed.text,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }
    }
    TranscriptRole.TOOL -> {
      Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Text(
          parsed.toolName ?: "tool",
          color = MaterialTheme.colorScheme.onSurface,
          fontFamily = JetBrainsMono,
          style = MaterialTheme.typography.bodySmall,
        )
        parsed.toolArg?.let { arg ->
          Text(
            "($arg)",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = JetBrainsMono,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
          )
        }
      }
    }
    TranscriptRole.STATUS -> {
      if (parsed.text.isNotBlank()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          // Small semantic-color status dot; its visuals carry no semantics of their own.
          Box(
            modifier =
              Modifier.size(6.dp)
                .background(
                  agentStatusStyle(
                      "working",
                      MaterialTheme.colorScheme.background.luminance() < 0.5f,
                    )
                    .background,
                  CircleShape,
                )
                .clearAndSetSemantics {}
          )
          Text(
            parsed.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
          )
        }
      }
    }
    TranscriptRole.AGENT -> {
      if (parsed.text.isNotBlank()) {
        Surface(
          shape = MaterialTheme.shapes.large,
          color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Column(Modifier.padding(12.dp)) {
            MessageBody(parsed.text, markdown = false, onInspect = onInspect)
          }
        }
      }
    }
  }
}

@Composable
private fun AgentItemCard(
  agent: AgentRow,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
  ) {
    ListItem(
      headlineContent = { Text(agent.label, fontWeight = FontWeight.SemiBold) },
      supportingContent = {
        Text(
          listOfNotNull(agent.agent.replaceFirstChar { it.uppercase() }, agent.branch)
            .joinToString(" · ")
        )
      },
      leadingContent = {
        Icon(
          painterResource(agentIconResource(agent.agent)),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
        )
      },
      trailingContent = { StatusBadge(agent.status) },
    )
  }
}

internal fun agentIconResource(agent: String): Int =
  when (agent.lowercase()) {
    "pi" -> com.azusachino.cappuccino.R.drawable.ic_agent_pi
    "agy",
    "antigravity",
    "antigravity-cli" -> com.azusachino.cappuccino.R.drawable.ic_agent_spark
    "claude" -> com.azusachino.cappuccino.R.drawable.ic_agent_claude
    else -> com.azusachino.cappuccino.R.drawable.ic_machine
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
  enabled: Boolean = true,
) {
  // Approval stays prominent as a default ElevatedCard — no custom border (region 6).
  ElevatedCard(modifier = modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(text = prompt.title, style = MaterialTheme.typography.titleMedium)
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
            text = prompt.type.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
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
        // Command on a tonal monospace block, no border.
        Surface(
          shape = MaterialTheme.shapes.small,
          color = MaterialTheme.colorScheme.surfaceContainer,
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text(
            text = cmd,
            fontFamily = JetBrainsMono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      if (!enabled) Text("This bridge can't accept answers.")
      if (prompt.options.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          prompt.options.forEachIndexed { index, option ->
            FilledTonalButton(
              onClick = { onSelectOption(index, option.id) },
              enabled = enabled,
              modifier = Modifier.fillMaxWidth(),
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
        enabled = enabled,
        modifier = Modifier.align(Alignment.End),
      ) {
        Text("Cancel")
      }
    }
  }
}

@Composable
fun PromptInputBar(
  /**
   * Sends the draft; the flag is true once the owner confirmed the agent command it starts with.
   */
  onSend: (String, Boolean) -> Unit,
  enabled: Boolean,
  modifier: Modifier = Modifier,
  placeholder: String = "Message agent…",
  onStop: (() -> Unit)? = null,
  onNewSession: (() -> Unit)? = null,
  newSessionEnabled: Boolean = true,
  model: String? = null,
  thinkingLevel: String? = null,
) {
  var input by rememberSaveable { mutableStateOf("") }
  var commandsOpen by remember { mutableStateOf(false) }
  var historyOpen by remember { mutableStateOf(false) }
  var isFocused by remember { mutableStateOf(false) }
  var promptHistory by rememberSaveable { mutableStateOf(listOf<String>()) }
  // An agent command can cost far more than a message; it waits here, draft kept, until confirmed.
  var confirmingCommand by rememberSaveable { mutableStateOf<String?>(null) }
  confirmingCommand?.let { command ->
    AlertDialog(
      onDismissRequest = { confirmingCommand = null },
      title = { Text("Run $command?") },
      text = { Text(slashCommandWarning(command)) },
      confirmButton = {
        TextButton(
          onClick = {
            val textToSend =
              if (input.isNotBlank() && slashCommand(input) == command) input else command
            if (promptHistory.firstOrNull() != textToSend) {
              promptHistory =
                (listOf(textToSend) + promptHistory.filter { it != textToSend }).take(20)
            }
            onSend(textToSend, true)
            input = ""
            confirmingCommand = null
          }
        ) {
          Text("Run $command")
        }
      },
      dismissButton = { TextButton(onClick = { confirmingCommand = null }) { Text("Cancel") } },
    )
  }
  val colors = MaterialTheme.appColors
  val canSend = enabled && input.isNotBlank()
  val slashSuggestions = remember(input) { matchingSlashCommands(input) }

  Column(modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
    // 1. Quick Action Chips Row
    Row(
      Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      QuickActionChip(
        label = "Continue",
        enabled = enabled,
        onClick = {
          if (promptHistory.firstOrNull() != "continue") {
            promptHistory =
              (listOf("continue") + promptHistory.filter { it != "continue" }).take(20)
          }
          onSend("continue", false)
        },
        colors = colors,
      )
      if (onStop != null) {
        QuickActionChip(
          label = "Interrupt",
          enabled = true,
          onClick = onStop,
          colors = colors,
          isError = true,
        )
      }
      QuickActionChip(
        label = "Compact",
        enabled = enabled,
        onClick = { confirmingCommand = "/compact" },
        colors = colors,
      )
      if (onNewSession != null) {
        QuickActionChip(
          label = "New Session",
          enabled = newSessionEnabled,
          onClick = onNewSession,
          colors = colors,
        )
      }
      QuickActionChip(
        label = "Status",
        enabled = enabled,
        onClick = { confirmingCommand = "/status" },
        colors = colors,
      )
      QuickActionChip(
        label = "Help",
        enabled = enabled,
        onClick = { confirmingCommand = "/help" },
        colors = colors,
      )
    }

    // 2. Slash command suggestions overlay (when typing '/')
    if (slashSuggestions.isNotEmpty() && isFocused) {
      Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.line),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
      ) {
        Column(Modifier.padding(vertical = 4.dp)) {
          slashSuggestions.take(4).forEach { item ->
            Row(
              Modifier.fillMaxWidth()
                .clickable { input = "${item.command} " }
                .padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Text(
                item.command,
                style =
                  MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = colors.accent,
              )
              Spacer(Modifier.widthIn(min = 8.dp))
              Text(
                item.description,
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    }

    // 3. Rounded Input Container
    Column(
      Modifier.fillMaxWidth()
        .background(colors.card, RoundedCornerShape(16.dp))
        .border(1.dp, if (isFocused) colors.accent else colors.line, RoundedCornerShape(16.dp))
        .clip(RoundedCornerShape(16.dp))
    ) {
      Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        androidx.compose.foundation.text.BasicTextField(
          value = input,
          onValueChange = { input = it },
          modifier =
            Modifier.weight(1f).onFocusChanged { isFocused = it.isFocused }.testTag("promptInput"),
          enabled = enabled,
          maxLines = 6,
          textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.text),
          cursorBrush = androidx.compose.ui.graphics.SolidColor(colors.accent),
          decorationBox = { field ->
            Box {
              if (input.isEmpty())
                Text(
                  if (enabled) placeholder else "Not connected",
                  style = MaterialTheme.typography.bodyMedium,
                  color = colors.faint,
                )
              field()
            }
          },
        )
        if (input.isNotEmpty()) {
          IconButton(onClick = { input = "" }, modifier = Modifier.size(28.dp)) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
              contentDescription = "Clear draft",
              modifier = Modifier.size(16.dp),
              tint = colors.muted,
            )
          }
        }
      }

      // Bottom control bar inside the rounded card
      Row(
        Modifier.fillMaxWidth().padding(start = 6.dp, end = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        // Commands button (slash command picker + new session)
        Box {
          IconButton(onClick = { commandsOpen = true }, modifier = Modifier.size(36.dp)) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_grid),
              contentDescription = "Commands",
              modifier = Modifier.size(18.dp),
              tint = colors.muted,
            )
          }
          DropdownMenu(expanded = commandsOpen, onDismissRequest = { commandsOpen = false }) {
            if (onNewSession != null) {
              DropdownMenuItem(
                text = { Text("New session") },
                enabled = newSessionEnabled,
                onClick = {
                  commandsOpen = false
                  onNewSession()
                },
              )
              HorizontalDivider(color = colors.line)
            }
            COMMON_SLASH_COMMANDS.forEach { item ->
              DropdownMenuItem(
                text = {
                  Column {
                    Text(
                      item.command,
                      style =
                        MaterialTheme.typography.labelMedium.copy(
                          fontFamily = FontFamily.Monospace
                        ),
                      color = colors.accent,
                    )
                    Text(
                      item.description,
                      style = MaterialTheme.typography.bodySmall,
                      color = colors.muted,
                    )
                  }
                },
                onClick = {
                  commandsOpen = false
                  input = "${item.command} "
                },
              )
            }
          }
        }

        // Prompt History button (when prompts have been sent)
        if (promptHistory.isNotEmpty()) {
          Box {
            IconButton(onClick = { historyOpen = true }, modifier = Modifier.size(36.dp)) {
              Icon(
                painterResource(com.azusachino.cappuccino.R.drawable.ic_history),
                contentDescription = "Prompt history",
                modifier = Modifier.size(18.dp),
                tint = colors.muted,
              )
            }
            DropdownMenu(expanded = historyOpen, onDismissRequest = { historyOpen = false }) {
              Text(
                "Recent Prompts",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
              )
              promptHistory.take(8).forEach { item ->
                DropdownMenuItem(
                  text = {
                    Text(
                      item,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      style = MaterialTheme.typography.bodySmall,
                    )
                  },
                  onClick = {
                    historyOpen = false
                    input = item
                  },
                )
              }
            }
          }
        }

        if (model != null)
          Text(
            listOfNotNull(shortModel(model), thinkingLevel).joinToString(" · "),
            Modifier.padding(horizontal = 6.dp).widthIn(max = 160.dp),
            style = MaterialTheme.typography.labelSmall,
            color = colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )

        Spacer(Modifier.weight(1f))

        if (onStop != null)
          IconButton(onClick = onStop, modifier = Modifier.size(36.dp).testTag("stopAgent")) {
            Box(
              Modifier.size(28.dp)
                .background(
                  MaterialTheme.colorScheme.error.copy(alpha = 0.15f),
                  RoundedCornerShape(8.dp),
                )
                .border(
                  1.dp,
                  MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
                  RoundedCornerShape(8.dp),
                ),
              contentAlignment = Alignment.Center,
            ) {
              Box(
                Modifier.size(10.dp)
                  .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp))
                  .semantics { contentDescription = "Stop" }
              )
            }
          }

        IconButton(
          onClick = {
            if (input.isNotBlank()) {
              val trimmed = input.trim()
              if (promptHistory.firstOrNull() != trimmed) {
                promptHistory = (listOf(trimmed) + promptHistory.filter { it != trimmed }).take(20)
              }
              val command = slashCommand(input)
              if (command != null) confirmingCommand = command
              else {
                onSend(input, false)
                input = ""
              }
            }
          },
          enabled = canSend,
          modifier = Modifier.size(36.dp),
        ) {
          Box(
            Modifier.size(30.dp)
              .background(if (canSend) colors.accent else colors.card2, CircleShape),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painterResource(com.azusachino.cappuccino.R.drawable.ic_arrow_downward),
              modifier = Modifier.size(16.dp).rotate(180f),
              contentDescription = "Send",
              tint = if (canSend) MaterialTheme.colorScheme.onPrimary else colors.faint,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun QuickActionChip(
  label: String,
  enabled: Boolean,
  onClick: () -> Unit,
  colors: AppColors,
  isError: Boolean = false,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = RoundedCornerShape(8.dp),
    color = if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.12f) else colors.card2,
    border =
      BorderStroke(
        1.dp,
        if (isError) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else colors.line,
      ),
    modifier = Modifier.height(26.dp),
  ) {
    Row(Modifier.padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
      Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color =
          if (!enabled) colors.faint
          else if (isError) MaterialTheme.colorScheme.error else colors.text,
      )
    }
  }
}

/** A model id as people say it: "opus 5.5" for `claude-opus-5-5`. */
internal fun shortModel(model: String): String =
  model.removePrefix("claude-").replace(Regex("-(\\d+)-(\\d+)$"), " $1.$2")

/** Under the message box: how full the context is and how much came from the cache. */
@Composable
internal fun SessionStatusLine(stats: SessionStats) {
  val tokens = stats.contextTokens ?: return
  val colors = MaterialTheme.appColors
  val window = stats.contextWindow
  val percent = window?.let { (tokens * 100 / it).coerceIn(0, 100).toInt() }
  Row(
    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text("context", style = MaterialTheme.typography.labelSmall, color = colors.muted)
    if (percent != null)
      Box(Modifier.size(width = 48.dp, height = 4.dp).background(colors.card2)) {
        Box(
          Modifier.fillMaxHeight()
            .fillMaxWidth(percent / 100f)
            .background(if (percent >= 80) colors.warn else colors.accent)
        )
      }
    Text(
      listOfNotNull(
          percent?.let { "$it%" },
          formatTokens(tokens) + (window?.let { " / " + formatTokens(it) } ?: ""),
        )
        .joinToString(" · "),
      style = MaterialTheme.typography.labelSmall,
      color = colors.muted,
    )
    stats.cachePercent?.let {
      Text("cache $it%", style = MaterialTheme.typography.labelSmall, color = colors.muted)
    }
  }
}

/** 1234 as "1.2K", 200000 as "200K", 1234567 as "1.2M". */
internal fun formatTokens(count: Long): String =
  when {
    count >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", count / 1_000.0)
    else -> count.toString()
  }.replace(".0", "")

@Composable
fun ConversationTurnRow(
  turn: ConversationTurn,
  modifier: Modifier = Modifier,
  timeZone: ZoneId = ZoneId.systemDefault(),
  assistantLabel: String = "Assistant",
  onInspect: () -> Unit = {},
  showToolsInline: Boolean = true,
  onTextSizeChanged: (() -> Unit)? = null,
  newest: Boolean = false,
) {
  val textModifier =
    remember(onTextSizeChanged) {
      if (onTextSizeChanged == null) Modifier else Modifier.onSizeChanged { onTextSizeChanged() }
    }
  val isUser = turn.role.lowercase() == "user"
  val parts = buildList {
    turn.text?.takeIf { it.isNotBlank() }?.let { add(ConversationPart.Text(it)) }
    addAll(turn.parts)
  }
  val shownParts = parts.filter {
    it.visibleInChat || (showToolsInline && it is ConversationPart.ToolCall)
  }
  val segments = remember(shownParts) { transcriptSegments(shownParts) }
  if (segments.isEmpty()) return
  Column(
    modifier.fillMaxWidth().then(textModifier).semantics {
      contentDescription = if (isUser) "You" else assistantLabel
    },
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    segments.forEachIndexed { index, segment ->
      key(turn.id, index) {
        when (segment) {
          is TranscriptSegment.Activity -> ActivityGroup(segment.parts, newest, onInspect)
          is TranscriptSegment.Prose -> {
            val part = segment.part
            if (part is ConversationPart.ResponseStatus) ResponseStatusRow(part)
            // Thinking and tool calls arrive grouped as activity, never as prose.
            else if (part !is ConversationPart.Text) Unit
            else if (isUser)
              UserPromptRow(
                body = {
                  MessageBody(
                    part.text,
                    modifier = textModifier,
                    markdown = false,
                    onInspect = onInspect,
                    transcript = true,
                  )
                },
                time =
                  turn.timestamp
                    ?.takeIf { index == 0 }
                    ?.let {
                      java.time.Instant.parse(it)
                        .atZone(timeZone)
                        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
                    },
                timeDescription = turn.timestamp?.let { formatConversationTimestamp(it, timeZone) },
              )
            else
              MessageBody(
                part.text,
                onInspect = onInspect,
                modifier = textModifier,
                transcript = true,
              )
          }
        }
      }
    }
  }
}
