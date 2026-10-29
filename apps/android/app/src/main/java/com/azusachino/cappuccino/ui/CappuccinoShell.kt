package com.azusachino.cappuccino.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.azusachino.cappuccino.io.ConnectedActions
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.agentDraftKey
import com.azusachino.cappuccino.io.agentSendKey
import com.azusachino.cappuccino.io.canMutate
import com.azusachino.cappuccino.io.label
import com.azusachino.cappuccino.io.machineConnection
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
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
  HISTORY(
    com.azusachino.cappuccino.R.string.history,
    com.azusachino.cappuccino.R.drawable.ic_history,
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
  requestIrohConnect: (String, String) -> Unit = { _, _ -> },
  requestConnect: (String, String) -> Unit,
) {
  val state by viewModel.state.collectAsStateWithLifecycle()
  CappuccinoScreen(
    state,
    viewModel,
    timeZone,
    savedHistoryAction = viewModel::openSavedConversation,
    requestConnect = requestConnect,
    requestIrohConnect = requestIrohConnect,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CappuccinoScreen(
  state: ConnectedUiState,
  actions: ConnectedActions,
  timeZone: ZoneId = ZoneId.systemDefault(),
  savedHistoryAction: ((java.util.UUID, String) -> Unit)? = null,
  requestIrohConnect: (String, String) -> Unit = { _, _ -> },
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
  val hazeState = rememberHazeState()
  // Floating islands: lists scroll beneath them, so their content padding clears both.
  val chromeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp
  val dockBottom = 116.dp
  val mainScreenState = rememberSaveableStateHolder()
  val composerState = rememberSaveableStateHolder()
  val readerState = rememberSaveableStateHolder()
  var machineMenuId by remember { mutableStateOf<String?>(null) }
  var showingConnection by rememberSaveable { mutableStateOf(false) }
  var showingHistory by rememberSaveable { mutableStateOf(false) }
  var confirmingClearAll by rememberSaveable { mutableStateOf(false) }
  var historyMachineId by rememberSaveable { mutableStateOf<java.util.UUID?>(null) }
  var showingTechnical by rememberSaveable { mutableStateOf(false) }
  var detailsProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var namingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var adding by rememberSaveable { mutableStateOf(false) }
  var label by rememberSaveable { mutableStateOf("") }
  var endpoint by rememberSaveable { mutableStateOf("") }
  var pendingAdd by rememberSaveable { mutableStateOf(false) }
  var previousProfileIds by rememberSaveable { mutableStateOf("") }
  var irohMode by rememberSaveable { mutableStateOf(false) }
  var payload by rememberSaveable { mutableStateOf("") }
  var scanError by rememberSaveable { mutableStateOf<String?>(null) }
  // Existing-machine pairing: iroh pairing for a tailnet machine, or a tailnet URL for iroh-only.
  var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  var editingIroh by rememberSaveable { mutableStateOf(true) }
  var editingUrl by rememberSaveable { mutableStateOf("") }
  var pendingEdit by rememberSaveable { mutableStateOf(false) }
  val context = LocalContext.current
  val selected = state.selectedAgent
  fun mutate(send: (ConnectedActions) -> Unit) {
    if (selected != null && actions.acceptsMutation(state.activeProfileId, selected)) send(actions)
  }
  val selectedProfile = state.profiles.firstOrNull { it.id == state.activeProfileId }
  val selectedStatus =
    if (!state.streamLive) "unknown"
    else if (state.stream.pendingPrompt != null) "blocked"
    else (state.stream.agentStatus ?: selected?.status)?.lowercase()
  val selectedIdle = selectedStatus !in setOf("working", "blocked")
  val agentControl =
    state.offlineSavedAt == null &&
      state.connection == ConnectionState.Connected &&
      state.canMutate &&
      state.bridgeDetails[state.activeProfileId]?.capabilities?.get("agent_control") == true
  val detailsProfile =
    if (detailsProfileId == null) selectedProfile
    else state.profiles.firstOrNull { it.id == detailsProfileId }
  val namingProfile = state.profiles.firstOrNull { it.id == namingProfileId } ?: selectedProfile
  var namingMachine by rememberSaveable { mutableStateOf(false) }
  var machineNameDraft by rememberSaveable { mutableStateOf("") }
  val scopedConversation = selected?.let {
    state.conversationId ?: it.nativeSessionRef ?: it.sessionId
  }
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
  var observedTextLayoutRevision by remember(outputIdentity) { mutableStateOf(textLayoutRevision) }
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
    val layoutChanged = observedTextLayoutRevision != textLayoutRevision
    observedFollowContent = followContent
    observedTextLayoutRevision = textLayoutRevision
    if (selected != null && followContent.isNotEmpty()) {
      if (followingOutput && (newContent || layoutChanged)) {
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
      payload = ""
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
  BackHandler(enabled = selected == null && destination == Destination.HISTORY) {
    destination = Destination.MACHINES
  }
  BackHandler(enabled = selected != null && !showingConnection && !showingHistory) {
    if (state.offlineSavedAt != null) {
      actions.closeSavedConversation()
    } else {
      actions.selectAgent(null)
    }
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

  if (showingHistory) {
    val historyForAgent = selected?.let { agent ->
      state.savedConversations.filter {
        it.agent.machineId == agent.machineId && it.agent.agent == agent.agent
      }
    }
    val targetMachineId = selected?.machineId ?: historyMachineId
    val conversationsToShow =
      if (!historyForAgent.isNullOrEmpty()) historyForAgent
      else if (targetMachineId != null)
        state.savedConversations.filter { it.agent.machineId == targetMachineId }
      else state.savedConversations

    ConversationHistoryDialog(
      conversations = conversationsToShow,
      savedMachineNames = state.profiles.associate { it.machineId to it.label },
      currentConversationId = state.conversationId,
      timeZone = timeZone,
      onSelect = { machineId, conversationId ->
        showingHistory = false
        historyMachineId = null
        savedHistoryAction?.invoke(machineId, conversationId)
      },
      onDismiss = {
        showingHistory = false
        historyMachineId = null
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

  if (confirmingClearAll) {
    AlertDialog(
      onDismissRequest = { confirmingClearAll = false },
      title = { Text("Clear all saved conversations?") },
      text = { Text("Saved conversations stay only on this device and cannot be recovered.") },
      confirmButton = {
        TextButton(
          onClick = {
            confirmingClearAll = false
            actions.clearSavedConversations(null)
          }
        ) {
          Text("Clear", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { confirmingClearAll = false }) { Text("Cancel") } },
    )
  }

  mainScreenState.SaveableStateProvider("main-screen") {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { contentPadding ->
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
          Modifier.align(Alignment.TopCenter)
            .fillMaxWidth()
            .zIndex(2f)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .statusBarsPadding()
            .glassBackdrop(
              hazeState = hazeState,
              backgroundColor = MaterialTheme.colorScheme.background,
              shape = RoundedCornerShape(28.dp),
            )
        ) {
          TopAppBar(
            colors =
              TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent
              ),
            title = {
              if (selected != null) {
                val status =
                  agentStatusStyle(
                    selectedStatus ?: "unknown",
                    MaterialTheme.colorScheme.background.luminance() < 0.5f,
                  )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                  ) {
                    AgentAvatar(selected.agent, 24.dp)
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
                    if (state.offlineSavedAt != null) {
                      StatusSquare(MaterialTheme.appColors.muted)
                      Text(
                        "Saved history · Read-only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.appColors.muted,
                      )
                    } else {
                      StatusSquare(status.background)
                      Text(
                        status.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.appColors.muted,
                      )
                    }
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
              if (selected != null) {
                IconButton(
                  onClick = {
                    if (state.offlineSavedAt != null) actions.closeSavedConversation()
                    else actions.selectAgent(null)
                  },
                  modifier = Modifier.pressScale(),
                ) {
                  Icon(
                    painter = painterResource(com.azusachino.cappuccino.R.drawable.ic_back),
                    contentDescription =
                      if (state.offlineSavedAt != null) "Back to history" else "Back",
                  )
                }
              }
            },
            // Liquid glass chrome: transcript blurs behind the bar (owner decision
            // 2026-10-27, superseding the no-colors-override note in android-material3.md).
            actions = {
              if (
                destination == Destination.HISTORY &&
                  selected == null &&
                  state.savedConversations.isNotEmpty()
              ) {
                IconButton(
                  onClick = { confirmingClearAll = true },
                  modifier = Modifier.pressScale().testTag("clearAllHistory"),
                ) {
                  Icon(
                    painterResource(com.azusachino.cappuccino.R.drawable.ic_trash),
                    contentDescription = "Clear all saved conversations",
                  )
                }
              }
              if (destination == Destination.MACHINES && selected == null) {
                IconButton(onClick = { adding = true }, modifier = Modifier.pressScale()) {
                  Icon(
                    painterResource(com.azusachino.cappuccino.R.drawable.ic_add),
                    contentDescription = "Add machine",
                  )
                }
              }
              if (selectedProfile != null && selected != null) {
                IconButton(
                  onClick = { showingHistory = true },
                  modifier = Modifier.testTag("conversationHistoryButton"),
                ) {
                  Icon(
                    painterResource(com.azusachino.cappuccino.R.drawable.ic_history),
                    contentDescription = "Conversation history",
                  )
                }
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
        }
        if (selected == null) {
          BottomDock(
            items =
              listOf(Destination.MACHINES, Destination.HISTORY, Destination.SETTINGS).map {
                DockItem(it.icon, stringResource(it.title))
              },
            selectedIndex =
              when (destination) {
                Destination.MACHINES -> 0
                Destination.HISTORY -> 1
                else -> 2
              },
            onSelect = { index ->
              actions.selectAgent(null)
              destination =
                listOf(Destination.MACHINES, Destination.HISTORY, Destination.SETTINGS)[index]
            },
            hazeState = hazeState,
            backgroundColor = MaterialTheme.colorScheme.background,
            modifier =
              Modifier.align(Alignment.BottomCenter)
                .zIndex(2f)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
          )
        }
        Column(
          Modifier.fillMaxSize().hazeSource(hazeState).navigationBarsPadding().imePadding(),
          verticalArrangement = Arrangement.spacedBy(if (selected == null) 12.dp else 4.dp),
        ) {
          when {
            selected != null -> {
              state.offlineSavedAt?.let { savedAt ->
                val colors = MaterialTheme.appColors
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = colors.card2,
                  border = BorderStroke(1.dp, colors.line),
                  modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                  ) {
                    Column(modifier = Modifier.weight(1f)) {
                      Text(
                        "Viewing saved conversation",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text,
                      )
                      Text(
                        "Saved ${formatConversationTimestamp(java.time.Instant.ofEpochMilli(savedAt).toString(), timeZone) ?: "earlier"} · Read-only",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted,
                      )
                    }
                    FilledTonalButton(
                      onClick = {
                        selectedProfile?.let { profile ->
                          actions.selectProfile(profile.id)
                          actions.selectAgent(selected)
                        }
                      },
                      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                      Text("Live session")
                    }
                  }
                }
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
              val bannerInset =
                if (
                  state.connection is ConnectionState.Connecting ||
                    state.connection is ConnectionState.Recovering ||
                    state.connection is ConnectionState.Error
                )
                  12.dp
                else chromeTop
              when (val connection = state.connection) {
                ConnectionState.Connecting ->
                  Column(
                    Modifier.fillMaxWidth().padding(top = chromeTop).padding(horizontal = 16.dp),
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
                  WarningBanner(
                    "Reconnecting; earlier output may be missing",
                    Modifier.padding(top = chromeTop),
                  )
                is ConnectionState.Error ->
                  ErrorBanner(connection.message, Modifier.padding(top = chromeTop))
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
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                          Text("Loading history…", Modifier.padding(16.dp))
                        }
                      } else if (turns.isNotEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
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
                                val liveStatus = selectedStatus
                                if (
                                  state.offlineSavedAt == null &&
                                    liveStatus in setOf("working", "blocked")
                                )
                                  LiveStatusLine(
                                    MaterialTheme.appColors.working,
                                    state.stream.agentStatusDetail,
                                  )
                                Spacer(Modifier.height(8.dp))
                              }
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
                          contentPadding = PaddingValues(top = bannerInset, bottom = 12.dp),
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
                state.actionError?.let { ErrorBanner(it) }
                state.stream.pendingPrompt?.let { card ->
                  val answersEnabled =
                    state.connection == ConnectionState.Connected &&
                      state.canMutate &&
                      state.bridgeDetails[state.activeProfileId]
                        ?.capabilities
                        ?.get("prompt_answers") != false
                  Box(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("pendingPrompt")
                  ) {
                    if (card.questions.isNotEmpty())
                      QuestionFormCard(
                        prompt = card,
                        enabled = answersEnabled,
                        onSubmit = { answers ->
                          mutate { it.answerQuestions(card.promptId, answers) }
                        },
                        onCancel = {
                          mutate { it.answerPrompt(card.promptId, null, null, "cancel") }
                        },
                      )
                    else
                      PromptCardView(
                        prompt = card,
                        enabled = answersEnabled,
                        onSelectOption = { idx, optId ->
                          mutate { it.answerPrompt(card.promptId, idx, optId, "select_option") }
                        },
                        onCancel = {
                          mutate { it.answerPrompt(card.promptId, null, null, "cancel") }
                        },
                      )
                  }
                }

                if (state.streamLive && !state.canMutate) {
                  Text(
                    "Session-bound actions unavailable. Update the bridge or reselect a session with native identity.",
                    Modifier.padding(horizontal = 16.dp),
                  )
                }
                // Existing live prompt route; saved readers remain read-only.
                if (
                  state.bridgeDetails[state.activeProfileId]?.capabilities?.get("prompt_input") ==
                    false
                ) {
                  Text("This bridge can't receive messages.", Modifier.padding(horizontal = 16.dp))
                }
                val draftKey = agentDraftKey(selected)
                val sendState =
                  state.promptSends[agentSendKey(state.activeProfileId ?: "", selected)]
                val composerKey = agentSendKey(state.activeProfileId ?: "", selected)
                if (state.offlineSavedAt == null)
                  composerState.SaveableStateProvider(composerKey) {
                    PromptInputBar(
                      onSend = { text, confirmed -> mutate { it.submitPrompt(text, confirmed) } },
                      pending = sendState != null && sendState.outcome == null,
                      agentWorking = selectedStatus == "working",
                      outcome = sendState?.outcome,
                      onStop =
                        if (agentControl && selectedStatus == "working") {
                          { mutate { it.controlAgent("interrupt") } }
                        } else null,
                      model = state.sessionStats?.model,
                      thinkingLevel = state.sessionStats?.thinkingLevel,
                      placeholder =
                        "Message ${selected.agent.replaceFirstChar { it.uppercase() }}…",
                      enabled =
                        state.connection == ConnectionState.Connected &&
                          state.canMutate &&
                          state.bridgeDetails[state.activeProfileId]
                            ?.capabilities
                            ?.get("prompt_input") != false,
                      draft = state.drafts[draftKey] ?: "",
                      onDraftChange = { text -> actions.setDraft(draftKey, text) },
                    )
                    state.sessionStats?.let { SessionStatusLine(it, agent = selected.agent) }
                  }
              }
            }
            destination == Destination.HISTORY -> {
              HistoryScreen(
                conversations = state.savedConversations,
                savedMachineNames = state.profiles.associate { it.machineId to it.label },
                currentConversationId = state.conversationId,
                timeZone = timeZone,
                onOpenSaved = { machineId, convoId ->
                  savedHistoryAction?.invoke(machineId, convoId)
                },
                onDeleteSaved = actions::deleteSavedConversation,
                contentPadding = PaddingValues(top = chromeTop, bottom = dockBottom),
              )
            }
            destination == Destination.SETTINGS -> {
              SettingsScreen(
                themeMode = state.themeMode,
                onSelectTheme = actions::setThemeMode,
                onReloadMachines = actions::refreshAll,
                contentPadding = PaddingValues(top = chromeTop, bottom = dockBottom),
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
              if (state.profiles.isEmpty()) {
                Box(
                  modifier =
                    Modifier.fillMaxWidth().weight(1f).padding(top = chromeTop).padding(24.dp),
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
                  contentPadding = PaddingValues(top = chromeTop, bottom = dockBottom),
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
                          supportingContent = {
                            Text(
                              listOfNotNull(
                                  machine.connection.statusText(),
                                  state.machineBadges[profile.id]?.label(),
                                )
                                .joinToString(" · "),
                              maxLines = 1,
                              overflow = TextOverflow.Ellipsis,
                            )
                          },
                          leadingContent = {
                            Icon(
                              painterResource(com.azusachino.cappuccino.R.drawable.ic_machine),
                              contentDescription = null,
                            )
                          },
                          trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                              // Retry is offered only where the row is in error; the banner it
                              // replaced used to carry this.
                              if (machine.connection is ConnectionState.Error) {
                                IconButton(
                                  onClick = { actions.refreshProfile(profile.id) },
                                  enabled = !machine.busy,
                                ) {
                                  Icon(
                                    painterResource(
                                      com.azusachino.cappuccino.R.drawable.ic_refresh
                                    ),
                                    contentDescription = "Retry ${profile.label}",
                                  )
                                }
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
                                    text = { Text("Conversation history") },
                                    onClick = {
                                      historyMachineId = profile.machineId
                                      showingHistory = true
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
                                  if (profile.endpoint.iroh == null) {
                                    DropdownMenuItem(
                                      text = { Text("Pair over iroh") },
                                      onClick = {
                                        editingProfileId = profile.id
                                        editingIroh = true
                                        payload = ""
                                        scanError = null
                                        pendingEdit = false
                                        machineMenuId = null
                                      },
                                    )
                                  }
                                  if (
                                    profile.endpoint.iroh != null && !profile.endpoint.hasTailnet
                                  ) {
                                    DropdownMenuItem(
                                      text = { Text("Add tailnet URL") },
                                      onClick = {
                                        editingProfileId = profile.id
                                        editingIroh = false
                                        editingUrl = ""
                                        pendingEdit = false
                                        machineMenuId = null
                                      },
                                    )
                                  }
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
                          // The row's status line carries the error; details live in the sheet.
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

              if (state.busy)
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
          }
        }
      }
    }
  }

  val editing = state.profiles.firstOrNull { it.id == editingProfileId }
  if (editing != null) {
    AlertDialog(
      onDismissRequest = { if (!state.busy) editingProfileId = null },
      title = { Text(if (editingIroh) "Pair over iroh" else "Add tailnet URL") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          if (editingIroh) {
            PairingPayloadInput(
              payload = payload,
              onPayload = { payload = it },
              scanError = scanError,
              onScanError = { scanError = it },
              busy = state.busy,
            )
          } else {
            OutlinedTextField(
              value = editingUrl,
              onValueChange = { editingUrl = it },
              label = { Text("Bridge address") },
              placeholder = { Text("https://machine.tailnet.ts.net") },
              keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                  keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                  autoCorrectEnabled = false,
                ),
              singleLine = true,
              enabled = !state.busy,
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
          }
          state.addError?.let { ErrorBanner(it, modifier = Modifier.padding(top = 4.dp)) }
          if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            pendingEdit = true
            if (editingIroh) actions.addIrohPairing(editing.id, payload)
            else actions.addTailnetUrl(editing.id, editingUrl)
          },
          enabled =
            !state.busy && (if (editingIroh) payload.isNotBlank() else editingUrl.isNotBlank()),
        ) {
          Text("Connect")
        }
      },
      dismissButton = {
        TextButton(onClick = { editingProfileId = null }, enabled = !state.busy) { Text("Cancel") }
      },
    )
  }
  // Close only after a busy round finishes without an error; a failed attempt keeps the dialog
  // open.
  LaunchedEffect(state.busy, pendingEdit) {
    if (pendingEdit && !state.busy && state.addError == null) {
      pendingEdit = false
      editingProfileId = null
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
            label = { Text("Label") },
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
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { irohMode = false }, enabled = irohMode) { Text("Bridge URL") }
            TextButton(onClick = { irohMode = true }, enabled = !irohMode) {
              Text("Pair over iroh")
            }
          }
          if (!irohMode)
            OutlinedTextField(
              value = endpoint,
              onValueChange = { endpoint = it },
              label = { Text("Bridge address") },
              placeholder = { Text("https://machine.tailnet.ts.net") },
              keyboardOptions =
                androidx.compose.foundation.text.KeyboardOptions(
                  keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                  autoCorrectEnabled = false,
                ),
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
          if (irohMode) {
            PairingPayloadInput(
              payload = payload,
              onPayload = { payload = it },
              scanError = scanError,
              onScanError = { scanError = it },
              busy = state.busy,
            )
          }
          state.addError?.let { ErrorBanner(it, modifier = Modifier.padding(top = 4.dp)) }
          if (state.busy) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            previousProfileIds = state.addedProfileIds.joinToString("|")
            pendingAdd = true
            if (irohMode) requestIrohConnect(label, payload) else requestConnect(label, endpoint)
          },
          enabled = !state.busy && (if (irohMode) payload.isNotBlank() else endpoint.isNotBlank()),
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
