package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.OutputRow
import com.azusachino.cappuccino.core.PromptCard
import com.azusachino.cappuccino.core.visibleInChat
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
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
  SETTINGS(
    com.azusachino.cappuccino.R.string.settings,
    com.azusachino.cappuccino.R.drawable.ic_settings,
  ),
}

@Composable
fun StatusBadge(status: String, modifier: Modifier = Modifier) {
  val (color, text) =
    when (status.lowercase()) {
      "working" -> MaterialTheme.colorScheme.primary to "WORKING"
      "done" -> MaterialTheme.colorScheme.tertiary to "DONE"
      "blocked" -> MaterialTheme.colorScheme.error to "BLOCKED"
      else -> MaterialTheme.colorScheme.onSurfaceVariant to "IDLE"
    }
  // Tonal pill with a semantic status color; full/circle shape, no border stroke.
  Surface(modifier = modifier, shape = CircleShape, color = color.copy(alpha = 0.15f)) {
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
  val detailsProfile =
    if (detailsProfileId == null) selectedProfile
    else state.profiles.firstOrNull { it.id == detailsProfileId }
  val namingProfile = state.profiles.firstOrNull { it.id == namingProfileId } ?: selectedProfile
  var namingMachine by rememberSaveable { mutableStateOf(false) }
  var machineNameDraft by rememberSaveable { mutableStateOf("") }
  val outputIdentity = Triple(state.activeProfileId, selected?.machineId, selected?.sessionId)
  val conversationKey = "${state.activeProfileId}|${selected?.machineId}|${selected?.sessionId}"
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
  val restoringReadingPosition =
    remember(outputIdentity) {
      outputListState.firstVisibleItemIndex > 0 || outputListState.firstVisibleItemScrollOffset > 0
    }
  var followModes by rememberSaveable { mutableStateOf(emptyMap<String, Boolean>()) }
  val turns = state.conversationTurns
  val toolActivities = remember(turns) { toolActivityEntries(turns) }
  val chatTurns =
    remember(turns) {
      turns.filter { turn -> !turn.text.isNullOrBlank() || turn.parts.any { it.visibleInChat } }
    }
  var floatingControlsHeight by remember(conversationKey) { mutableStateOf(0.dp) }
  val controlDensity = androidx.compose.ui.platform.LocalDensity.current
  var showingToolActivity by remember(conversationKey) { mutableStateOf(false) }
  var toolActivityTarget by remember(conversationKey) { mutableStateOf<String?>(null) }
  val rows = state.stream.rows()
  val collapsedRows = remember(rows) { collapseOutputRows(rows) }
  val displayedContent: List<*> = if (turns.isNotEmpty()) chatTurns else collapsedRows
  val followContent: List<*> =
    if (turns.isNotEmpty()) com.azusachino.cappuccino.core.conversationFollowContent(turns)
    else collapsedRows
  var observedTools by remember(conversationKey) { mutableStateOf<List<ToolActivityEntry>?>(null) }
  var toolPulse by remember(conversationKey) { mutableStateOf(false) }
  LaunchedEffect(toolActivities) {
    if (toolActivities.isNotEmpty()) {
      val previous = observedTools
      observedTools = toolActivities
      val changed =
        previous != null &&
          toolActivities.any { entry ->
            previous.none {
              it.turnId == entry.turnId && it.partIndex == entry.partIndex && it.part == entry.part
            }
          }
      if (changed && !showingToolActivity)
        try {
          toolPulse = true
          kotlinx.coroutines.delay(1_200)
        } finally {
          toolPulse = false
        }
    }
  }
  var followPreferences by rememberSaveable { mutableStateOf(emptyMap<String, Boolean>()) }
  val followEnabled = followPreferences[conversationKey] ?: true
  var hasNewOutput by remember(outputIdentity) { mutableStateOf(false) }
  var followingOutput by
    remember(outputIdentity) { mutableStateOf(followModes[conversationKey] ?: true) }
  LaunchedEffect(conversationKey, followingOutput) {
    followModes = followModes + (conversationKey to followingOutput)
  }
  var automaticScroll by remember(outputIdentity) { mutableStateOf(false) }
  val outputScope = rememberCoroutineScope()

  LaunchedEffect(outputListState, followEnabled) {
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
          followingOutput = atEnd && followEnabled
          if (atEnd) hasNewOutput = false
        }
      }
  }
  LaunchedEffect(followContent, outputIdentity, followingOutput, followEnabled) {
    if (selected != null && followContent.isNotEmpty()) {
      if (followingOutput && followEnabled) {
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
      destination = Destination.MACHINES
    }
  }
  // Keep the old saved CHATS enum readable, but migrate it into the machine tree.
  LaunchedEffect(destination) {
    if (destination == Destination.CHATS) {
      destination = Destination.MACHINES
    }
  }
  LaunchedEffect(showingConnection) { if (showingConnection) followingOutput = false }
  BackHandler(enabled = selected != null && !showingConnection && !showingToolActivity) {
    actions.selectAgent(null)
  }
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

  mainScreenState.SaveableStateProvider("main-screen") {
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
                Row(
                  modifier = Modifier.weight(1f),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                  Icon(
                    painterResource(agentIconResource(selected.agent)),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                  )
                  Text(
                    selected.label,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                  )
                }
                StatusBadge(state.stream.agentStatus ?: selected.status)
              }
            } else {
              Text(
                stringResource(destination.title),
                fontWeight = FontWeight.Bold,
              )
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
          // M3 app bar defaults apply: no colors override (android-material3.md region 1).
          actions = {
            if (selectedProfile != null && selected != null) {
              TextButton(
                onClick = {
                  detailsProfileId = selectedProfile.id
                  showingConnection = true
                }
              ) {
                Text("Details")
              }
            }
          },
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
        if (selected == null)
          NavigationBar {
            listOf(Destination.MACHINES, Destination.ATTENTION, Destination.SETTINGS).forEach { item
              ->
              NavigationBarItem(
                selected = destination == item,
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
            // Transcript metadata as plain text lines under the app bar; cardless
            // (android-material3.md region 2).
            Column(
              modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                listOfNotNull(
                    selectedProfile?.label,
                    selected.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
                    selected.branch?.takeIf { it.isNotBlank() } ?: "Branch unknown",
                  )
                  .joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
              )
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

            // If there is an active pending prompt on this agent, show the prompt card prominently
            state.stream.pendingPrompt?.let { card ->
              Box(Modifier.padding(horizontal = 16.dp)) {
                PromptCardView(
                  prompt = card,
                  enabled =
                    state.bridgeDetails[state.activeProfileId]
                      ?.capabilities
                      ?.get("prompt_answers") != false,
                  onSelectOption = { idx, optId ->
                    actions.answerPrompt(card.promptId, idx, optId, "select_option")
                  },
                  onCancel = { actions.answerPrompt(card.promptId, null, null, "cancel") },
                )
              }
            }

            if (state.conversationSource == "scrollback") {
              Text(
                "Live terminal output · canonical session unavailable",
                Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            Row(
              Modifier.fillMaxWidth().padding(horizontal = 16.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Column(Modifier.weight(1f)) {
                Text("Follow live output", style = MaterialTheme.typography.bodyMedium)
                if (followEnabled && !followingOutput)
                  Text(
                    "Paused while reading",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
              }
              androidx.compose.material3.Switch(
                modifier = Modifier.semantics { contentDescription = "Follow live output" },
                checked = followEnabled,
                onCheckedChange = { enabled ->
                  followPreferences = followPreferences + (conversationKey to enabled)
                  followingOutput = enabled
                },
              )
            }
            readerState.SaveableStateProvider(conversationKey) {
              var historyNoticeShown by rememberSaveable { mutableStateOf(false) }
              val historyNotice = remember { androidx.compose.material3.SnackbarHostState() }
              val historyOmitted =
                turns.isNotEmpty() &&
                  (state.omittedConversationTurns > 0 || state.omittedToolOperations > 0)
              LaunchedEffect(historyOmitted) {
                if (historyOmitted && !historyNoticeShown) {
                  historyNoticeShown = true
                  historyNotice.showSnackbar(
                    "Earlier history not shown · recent 200 messages / 100 tool operations",
                    duration = androidx.compose.material3.SnackbarDuration.Short,
                  )
                }
              }
              Box(Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer(Modifier.fillMaxSize()) {
                  if (
                    turns.isEmpty() && state.conversationSource == null && restoringReadingPosition
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
                      verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                      items(
                        chatTurns,
                        key = {
                          "${state.activeProfileId}:${selected.machineId}:${selected.sessionId}:${it.id}"
                        },
                      ) { turn ->
                        ConversationTurnRow(
                          turn,
                          timeZone = timeZone,
                          assistantLabel = selected.agent.replaceFirstChar { it.uppercase() },
                          onInspect = { followingOutput = false },
                          showToolsInline = false,
                        )
                      }
                      item(key = "conversation-bottom") {
                        Spacer(Modifier.height(floatingControlsHeight + 24.dp))
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
                      item(key = "stream-bottom") {
                        Spacer(Modifier.height(floatingControlsHeight + 24.dp))
                      }
                    }
                  }
                }
                androidx.compose.material3.SnackbarHost(
                  historyNotice,
                  Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp),
                )
                ReaderFloatingControls(
                  toolActivities,
                  hasNewOutput,
                  newActivity = toolPulse,
                  onTools = {
                    toolActivityTarget = null
                    followingOutput = false
                    showingToolActivity = true
                  },
                  onLatest = {
                    outputScope.launch {
                      followingOutput = followEnabled
                      automaticScroll = true
                      try {
                        outputListState.animateScrollToItem(displayedContent.size)
                        hasNewOutput = false
                      } finally {
                        automaticScroll = false
                      }
                    }
                  },
                  modifier =
                    Modifier.align(Alignment.BottomEnd).padding(12.dp).onSizeChanged { size ->
                      floatingControlsHeight = with(controlDensity) { size.height.toDp() }
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

            // Interactive prompt input bar
            if (
              state.bridgeDetails[state.activeProfileId]?.capabilities?.get("prompt_input") == false
            ) {
              Text("Prompt input unsupported by this bridge", Modifier.padding(horizontal = 16.dp))
            }
            composerState.SaveableStateProvider(conversationKey) {
              PromptInputBar(
                onSend = { text -> actions.submitPrompt(text) },
                enabled =
                  state.connection == ConnectionState.Connected &&
                    state.bridgeDetails[state.activeProfileId]?.capabilities?.get("prompt_input") !=
                      false,
              )
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
                  enabled =
                    state.bridgeDetails[state.activeProfileId]
                      ?.capabilities
                      ?.get("prompt_answers") != false,
                  onSelectOption = { idx, optId ->
                    actions.answerPrompt(pending.promptId, idx, optId, "select_option")
                  },
                  onCancel = { actions.answerPrompt(pending.promptId, null, null, "cancel") },
                )
              }
            } else {
              Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                  "No question received",
                  style = MaterialTheme.typography.titleLarge,
                  modifier = Modifier.semantics { heading() },
                )
                Text(
                  "Questions are currently received only from the open agent stream. Other agents are not monitored here yet.",
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
          destination == Destination.SETTINGS -> {
            SettingsScreen(
              themeMode = state.themeMode,
              onSelectTheme = actions::setThemeMode,
              onManageMachines = { destination = Destination.MACHINES },
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

  if (showingToolActivity && selected != null) {
    ToolActivitySheet(
      toolActivities,
      toolActivityTarget,
      onDismiss = { showingToolActivity = false },
      earlierOmitted = state.omittedConversationTurns > 0 || state.omittedToolOperations > 0,
    )
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
                .background(HerdrStatusWorking, CircleShape)
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
    "claude" -> com.azusachino.cappuccino.R.drawable.ic_chat
    "codex" -> com.azusachino.cappuccino.R.drawable.ic_agent_code
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
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(12.dp),
          )
        }
      }

      if (!enabled) Text("Prompt answers unsupported by this bridge")
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
  // Native composer: OutlinedTextField with default shape and colors; connectivity and
  // nonblank-send semantics are exactly as merged (region 9).
  Row(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    OutlinedTextField(
      value = input,
      onValueChange = { input = it },
      placeholder = { Text("Prompt agent…") },
      modifier = Modifier.weight(1f).testTag("promptInput"),
      enabled = enabled,
      singleLine = true,
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
      )
    }
  }
}

@Composable
fun ConversationTurnRow(
  turn: ConversationTurn,
  modifier: Modifier = Modifier,
  timeZone: ZoneId = ZoneId.systemDefault(),
  assistantLabel: String = "Assistant",
  onInspect: () -> Unit = {},
  showToolsInline: Boolean = true,
) {
  val isUser = turn.role.lowercase() == "user"
  val parts = buildList {
    turn.text?.takeIf { it.isNotBlank() }?.let { add(ConversationPart.Text(it)) }
    addAll(turn.parts)
  }
  val visibleParts = parts.filter { it.visibleInChat }
  val timestamp = turn.timestamp?.let { formatConversationTimestamp(it, timeZone) }
  androidx.compose.foundation.layout.BoxWithConstraints(
    modifier.fillMaxWidth().padding(vertical = 2.dp)
  ) {
    val bubbleWidth = minOf(600.dp, (maxWidth - 32.dp).coerceAtLeast(0.dp))
    Column(
      Modifier.fillMaxWidth(),
      horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      if (visibleParts.isNotEmpty()) {
        Surface(
          modifier =
            Modifier.widthIn(max = bubbleWidth).semantics {
              contentDescription = if (isUser) "You" else assistantLabel
            },
          shape =
            androidx.compose.foundation.shape.RoundedCornerShape(
              topStart = 16.dp,
              topEnd = 16.dp,
              bottomStart = if (isUser) 16.dp else 4.dp,
              bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
          color =
            if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
          Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            visibleParts.forEachIndexed { index, part ->
              key(turn.id, index) {
                if (part is ConversationPart.Text) MessageBody(part.text, onInspect = onInspect)
                else ConversationDetails(part, onInspect)
              }
            }
            if (timestamp != null)
              Text(
                java.time.Instant.parse(turn.timestamp)
                  .atZone(timeZone)
                  .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
                Modifier.align(Alignment.End).semantics { contentDescription = timestamp },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
          }
        }
      }
      if (showToolsInline)
        parts.filterIsInstance<ConversationPart.ToolCall>().forEachIndexed { index, part ->
          key(turn.id, "tool", index) { ConversationDetails(part, onInspect) }
        }
    }
  }
}
