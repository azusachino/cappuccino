package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.core.*
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class BridgeStreamError(message: String) : Exception(message)

/** Bridge reported a recoverable upstream loss; retried within the existing stream budget. */
private class BridgeUpstreamUnavailableError(message: String) : Exception(message)

sealed interface ConnectionState {
  data object Disconnected : ConnectionState

  data object Connecting : ConnectionState

  data object Connected : ConnectionState

  data object Recovering : ConnectionState

  data class Error(val message: String) : ConnectionState
}

data class MachineConnection(
  val connection: ConnectionState = ConnectionState.Disconnected,
  val agents: List<AgentRow> = emptyList(),
  val busy: Boolean = false,
)

fun ConnectedUiState.machineConnection(profile: MachineProfile): MachineConnection =
  machines[profile.id]
    ?: if (activeProfileId == profile.id)
      MachineConnection(connection, agents.filter { it.machineId == profile.machineId }, busy)
    else MachineConnection()

data class PromptSendState(val text: String, val outcome: String? = null)

/** Live observation and an advertised binding contract are both required for writes. */
val ConnectedUiState.canMutate: Boolean
  get() =
    connection == ConnectionState.Connected &&
      streamLive &&
      bridgeDetails[activeProfileId]?.capabilities?.get("mutation_binding") == true &&
      selectedAgent?.let { MutationBinding.from(it) } != null

/**
 * Drafts bind to the native session so a replacement occupant of the same locator never inherits
 * one.
 */
internal fun agentDraftKey(agent: AgentRow): String =
  "${agent.machineId}:${agent.sessionId}:${agent.nativeSessionRef}"

internal fun agentSendKey(profileId: String, agent: AgentRow): String =
  "$profileId:${agentDraftKey(agent)}"

data class ConnectedUiState(
  val profiles: List<MachineProfile> = emptyList(),
  val bridgeDetails: Map<String, BridgeDetails> = emptyMap(),
  val machines: Map<String, MachineConnection> = emptyMap(),
  val machineBadges: Map<String, TransportBadge> = emptyMap(),
  val addError: String? = null,
  val addedProfileIds: Set<String> = emptySet(),
  val activeProfileId: String? = null,
  val agents: List<AgentRow> = emptyList(),
  val selectedAgent: AgentRow? = null,
  val stream: StreamState = StreamState(),
  /**
   * True only after a fresh authoritative agent_status on the current stream connection;
   * stream_open is transport, not live state, so actions stay disabled until then (C2).
   */
  val streamLive: Boolean = false,
  val conversationTurns: List<ConversationTurn> = emptyList(),
  val omittedConversationTurns: Int = 0,
  val omittedToolOperations: Int = 0,
  val conversationSource: String? = null,
  val sessionStats: SessionStats? = null,
  /** Why the last Stop, New session or answer failed; the connection itself is fine. */
  val actionError: String? = null,
  val savedConversations: List<SavedConversationInfo> = emptyList(),
  val offlineBytes: Long? = null,
  val offlineProblem: String? = null,
  val offlineSavedAt: Long? = null,
  val conversationId: String? = null,
  val connection: ConnectionState = ConnectionState.Disconnected,
  val busy: Boolean = false,
  val themeMode: com.azusachino.cappuccino.ui.ThemeMode =
    com.azusachino.cappuccino.ui.ThemeMode.SYSTEM,
  val drafts: Map<String, String> = emptyMap(),
  val promptSends: Map<String, PromptSendState> = emptyMap(),
)

interface ConnectedActions {
  /** Recheck the rendered native selection at click time; unsupported controllers fail closed. */
  fun acceptsMutation(profileId: String?, agent: AgentRow): Boolean = false

  fun addMachine(label: String, url: String)

  /** Pairs over iroh with a payload from the QR scan or paste, then adds the machine. */
  fun addIrohMachine(label: String, payload: String) = Unit

  /** Adds an iroh pairing to an existing machine; its tailnet URL, if any, is kept. */
  fun addIrohPairing(profileId: String, payload: String) = Unit

  /** Adds a tailnet URL to an existing machine; its iroh pairing, if any, is kept. */
  fun addTailnetUrl(profileId: String, url: String) = Unit

  fun renameProfile(id: String, label: String)

  fun selectProfile(id: String)

  fun disconnectProfile()

  fun refreshProfile(id: String) = refresh()

  fun disconnectMachine(id: String) = disconnectProfile()

  fun refreshAll() = refresh()

  fun refresh()

  fun selectAgent(agent: AgentRow?)

  /** Dismisses an open saved conversation and returns to its list; no connection is touched. */
  fun closeSavedConversation() = Unit

  fun removeProfile(id: String)

  fun retry()

  fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode)

  fun submitPrompt(text: String, confirmed: Boolean = false)

  fun setDraft(key: String, text: String) = Unit

  fun controlAgent(action: String) = Unit

  fun answerQuestions(
    promptId: String,
    answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
  ) = Unit

  fun answerPrompt(promptId: String, optionIndex: Int?, optionId: String?, action: String?)

  fun loadConversation()

  fun deleteSavedConversation(machineId: UUID, conversationId: String) = Unit

  fun clearSavedConversations(machineId: UUID? = null) = Unit
}

class ConnectedViewModel
internal constructor(
  private val store: MachineProfileStore,
  private val bridgeFor: (Endpoint) -> BridgeTransport,
  private val retryPause: suspend (Long) -> Unit,
  private val savedStateHandle: SavedStateHandle,
  private val offlineHistory: OfflineConversations? = null,
  private val offlineDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO,
  private val onMonitorPause: ((String, Boolean) -> Unit)? = null,
  private val irohPairing: (suspend (IrohPairing) -> Unit)? = null,
  private val transportBadge: (Endpoint) -> TransportBadge = { TransportBadge.NONE },
  private val clock: () -> Long = { System.currentTimeMillis() },
) : ViewModel(), ConnectedActions {
  private val mutableState =
    MutableStateFlow(
      ConnectedUiState(
        profiles = store.read(),
        themeMode = store.readThemeMode(),
      )
    )
  val state: StateFlow<ConnectedUiState> = mutableState.asStateFlow()
  private val machineJobs = mutableMapOf<String, Job>()
  private val machineEpochs = mutableMapOf<String, Long>()
  private val lastRefreshAt = mutableMapOf<String, Long>()
  private val disconnectedMachines = mutableSetOf<String>()
  private var addJob: Job? = null
  private var addEpoch = 0L
  private var operation: Job? = null
  private var streamJob: Job? = null
  private var conversationJob: Job? = null
  private var conversationRequested = false
  private var foreground = false
  private var epoch = 0L
  private var pendingRestore = savedStateHandle.get<String>(SELECTED_PROFILE) != null

  private var offlineIndexJob: Job? = null

  init {
    reloadSavedHistory()
  }

  private fun reloadSavedHistory() {
    val history = offlineHistory ?: return
    offlineIndexJob?.cancel()
    offlineIndexJob = viewModelScope.launch {
      try {
        val (saved, bytes) =
          withContext(offlineDispatcher) {
            history.list().map {
              SavedConversationInfo(it.agent, it.savedAt, it.history.turns.size, it.conversationId)
            } to history.bytes()
          }
        mutableState.value =
          mutableState.value.copy(
            savedConversations = saved,
            offlineBytes = bytes,
            offlineProblem = null,
          )
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        mutableState.value =
          mutableState.value.copy(offlineProblem = "Couldn't load saved conversations.")
      }
    }
  }

  fun openSavedConversation(machineId: UUID, conversationId: String) {
    val history = offlineHistory ?: return
    val profile = mutableState.value.profiles.firstOrNull { it.machineId == machineId } ?: return
    cancelOwnedWork()
    clearSavedSelection()
    val token = epoch
    operation = viewModelScope.launch {
      try {
        val saved =
          withContext(offlineDispatcher) { history.read(machineId, conversationId) }
            ?: return@launch
        if (
          token != epoch ||
            mutableState.value.profiles.none { it.id == profile.id && it.machineId == machineId }
        )
          return@launch
        mutableState.value =
          mutableState.value.copy(
            activeProfileId = profile.id,
            selectedAgent = saved.agent,
            stream = StreamState(),
            conversationTurns = saved.history.turns,
            omittedConversationTurns = saved.history.omittedMessages,
            omittedToolOperations = saved.history.omittedTools,
            conversationSource = "offline_snapshot",
            sessionStats = null,
            actionError = null,
            conversationId = saved.conversationId,
            offlineSavedAt = saved.savedAt,
            connection = ConnectionState.Disconnected,
            busy = false,
          )
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (token == epoch)
          mutableState.value =
            mutableState.value.copy(offlineProblem = "Couldn't open this saved conversation.")
      }
    }
  }

  override fun deleteSavedConversation(machineId: UUID, conversationId: String) {
    val history = offlineHistory ?: return
    viewModelScope.launch {
      withContext(offlineDispatcher) { history.delete(machineId, conversationId) }
      val current = mutableState.value
      if (current.offlineSavedAt != null && current.conversationId == conversationId) {
        clearSavedSelection()
        mutableState.value =
          current.copy(
            selectedAgent = null,
            offlineSavedAt = null,
            conversationId = null,
            conversationTurns = emptyList(),
          )
      }
      reloadSavedHistory()
    }
  }

  override fun clearSavedConversations(machineId: UUID?) {
    val history = offlineHistory ?: return
    viewModelScope.launch {
      val machineIds = if (machineId != null) setOf(machineId) else null
      withContext(offlineDispatcher) { history.clearAll(machineIds) }
      val current = mutableState.value
      if (
        current.offlineSavedAt != null &&
          (machineId == null || current.selectedAgent?.machineId == machineId)
      ) {
        clearSavedSelection()
        mutableState.value =
          current.copy(
            selectedAgent = null,
            offlineSavedAt = null,
            conversationId = null,
            conversationTurns = emptyList(),
          )
      }
      reloadSavedHistory()
    }
  }

  fun openNotificationAgent(profileId: String, paneId: String, nativeReference: String) {
    val profile = mutableState.value.profiles.firstOrNull { it.id == profileId } ?: return
    val token = epoch
    viewModelScope.launch {
      try {
        val client = bridgeFor(profile.endpoint)
        client.verify(profile.machineId)
        val agent =
          client.agents(profile.machineId).singleOrNull {
            it.paneId == paneId && it.nativeSessionRef == nativeReference
          } ?: return@launch
        if (token != epoch || mutableState.value.profiles.none { it == profile }) return@launch
        selectProfile(profile.id)
        selectAgent(agent)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        // An unavailable or replaced session is not attached by locator guess.
      }
    }
  }

  override fun renameProfile(id: String, label: String) {
    val name = label.trim().takeIf { it.isNotEmpty() } ?: return
    val profile = mutableState.value.profiles.firstOrNull { it.id == id } ?: return
    store.save(profile.copy(label = name))
    mutableState.value = mutableState.value.copy(profiles = store.read())
  }

  private fun recordMachineName(
    profile: MachineProfile,
    reportedName: String?,
    details: BridgeDetails?,
  ) {
    if (details != null && details.machineId == profile.machineId) {
      mutableState.value =
        mutableState.value.copy(
          bridgeDetails = mutableState.value.bridgeDetails + (profile.id to details)
        )
    }
    val name = reportedName?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val current = store.read().firstOrNull { it.id == profile.id } ?: return
    // Migrate only old URL-derived defaults; never overwrite an owner-given name.
    if (current.label != current.endpoint.base.host) return
    store.save(current.copy(label = name))
    mutableState.value = mutableState.value.copy(profiles = store.read())
  }

  override fun addMachine(label: String, url: String) =
    startAdd(label) { Endpoint.parse(url, allowLocalHttp = BuildConfig.DEBUG) }

  override fun addIrohMachine(label: String, payload: String) =
    startAdd(label) {
      val pairing = parseIrohPairing(payload)
      val redeem = irohPairing ?: throw IllegalStateException("iroh is unavailable")
      redeem(pairing)
      val irohEndpoint = Endpoint.iroh(pairing.endpoint)
      val tailnetUrl = pairing.tailnetUrl?.takeIf { it.isNotBlank() }
      if (tailnetUrl != null) {
        irohEndpoint.withTailnet(Endpoint.parse(tailnetUrl, allowLocalHttp = BuildConfig.DEBUG))
      } else {
        irohEndpoint
      }
    }

  override fun addIrohPairing(profileId: String, payload: String) =
    updateEndpoint(profileId) { endpoint ->
      val pairing = parseIrohPairing(payload)
      val redeem = irohPairing ?: throw IllegalStateException("iroh is unavailable")
      redeem(pairing)
      val withIroh = endpoint.withIroh(pairing.endpoint)
      val tailnetUrl = pairing.tailnetUrl?.takeIf { it.isNotBlank() }
      if (!withIroh.hasTailnet && tailnetUrl != null) {
        withIroh.withTailnet(Endpoint.parse(tailnetUrl, allowLocalHttp = BuildConfig.DEBUG))
      } else {
        withIroh
      }
    }

  override fun addTailnetUrl(profileId: String, url: String) =
    updateEndpoint(profileId) { endpoint ->
      endpoint.withTailnet(Endpoint.parse(url, allowLocalHttp = BuildConfig.DEBUG))
    }

  /**
   * Replaces a saved machine's endpoint once the new route verifies as the same machine. The change
   * runs before anything is saved, so a failed pairing leaves the stored profile untouched.
   */
  private fun updateEndpoint(profileId: String, change: suspend (Endpoint) -> Endpoint) {
    val profile = mutableState.value.profiles.firstOrNull { it.id == profileId } ?: return
    addEpoch++
    val token = addEpoch
    addJob?.cancel()
    mutableState.value = mutableState.value.copy(busy = true, addError = null)
    addJob = viewModelScope.launch {
      try {
        val endpoint = change(profile.endpoint)
        bridgeFor(endpoint).verify(profile.machineId)
        if (token != addEpoch) return@launch
        store.save(profile.copy(endpoint = endpoint))
        mutableState.value =
          mutableState.value.copy(profiles = store.read(), addError = null, busy = false)
        refreshProfile(profile.id)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (token == addEpoch)
          mutableState.value =
            mutableState.value.copy(addError = error.message ?: "Connection failed", busy = false)
      }
    }
  }

  private fun startAdd(label: String, endpointFor: suspend () -> Endpoint) {
    addEpoch++
    val token = addEpoch
    addJob?.cancel()
    mutableState.value = mutableState.value.copy(busy = true, addError = null)
    addJob = viewModelScope.launch {
      try {
        val endpoint = endpointFor()
        val client = bridgeFor(endpoint)
        val machineId = client.verify()
        if (token != addEpoch) return@launch
        val profile =
          MachineProfile(
            UUID.randomUUID().toString(),
            label.ifBlank { client.machineName ?: endpoint.base.host },
            endpoint,
            machineId,
          )
        store.save(profile)
        reloadSavedHistory()
        mutableState.value =
          mutableState.value.copy(
            profiles = store.read(),
            addedProfileIds = mutableState.value.addedProfileIds + profile.id,
            bridgeDetails =
              mutableState.value.bridgeDetails +
                listOfNotNull(client.bridgeDetails?.let { profile.id to it }).toMap(),
            activeProfileId = mutableState.value.activeProfileId ?: profile.id,
            connection =
              if (mutableState.value.activeProfileId == null) ConnectionState.Connected
              else mutableState.value.connection,
            busy = false,
          )
        refreshProfile(profile.id)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (token == addEpoch)
          mutableState.value =
            mutableState.value.copy(
              addError = error.message ?: "Connection failed",
              connection =
                if (mutableState.value.activeProfileId == null)
                  ConnectionState.Error(error.message ?: "Connection failed")
                else mutableState.value.connection,
              busy = false,
            )
      }
    }
  }

  override fun selectProfile(id: String) {
    mutableState.value = mutableState.value.copy(offlineSavedAt = null, conversationId = null)
    cancelOwnedWork()
    clearSavedSelection()
    val profile = mutableState.value.profiles.firstOrNull { it.id == id } ?: return
    mutableState.value =
      mutableState.value.copy(
        activeProfileId = id,
        selectedAgent = null,
        stream = StreamState(),
        streamLive = false,
        connection = mutableState.value.machineConnection(profile).connection,
        agents = mutableState.value.machineConnection(profile).agents,
        conversationTurns = emptyList(),
        conversationSource = null,
        sessionStats = null,
        actionError = null,
        busy = false,
      )
    if (mutableState.value.machineConnection(profile).connection != ConnectionState.Connected)
      refreshProfile(id)
  }

  override fun disconnectProfile() {
    mutableState.value.activeProfileId?.let { disconnectMachine(it) }
  }

  override fun disconnectMachine(id: String) {
    onMonitorPause?.invoke(id, true)
    disconnectedMachines.add(id)
    machineEpochs[id] = (machineEpochs[id] ?: 0L) + 1
    machineJobs.remove(id)?.cancel()
    if (mutableState.value.activeProfileId == id) {
      cancelOwnedWork()
      clearSavedSelection()
      mutableState.value =
        mutableState.value.copy(
          selectedAgent = null,
          agents = emptyList(),
          stream = StreamState(),
          streamLive = false,
          conversationTurns = emptyList(),
          conversationSource = null,
          sessionStats = null,
          actionError = null,
          connection = ConnectionState.Disconnected,
          busy = false,
        )
    }
    mutableState.value =
      mutableState.value.copy(machines = mutableState.value.machines + (id to MachineConnection()))
  }

  override fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
    store.saveThemeMode(mode)
    mutableState.value = mutableState.value.copy(themeMode = mode)
  }

  override fun refresh() {
    activeProfile()?.let { refreshProfile(it.id) }
  }

  override fun refreshAll() {
    val now = clock()
    mutableState.value.profiles
      .filter { it.id !in disconnectedMachines }
      .forEach { profile ->
        if (machineJobs[profile.id]?.isActive == true) return@forEach
        val badge = transportBadge(profile.endpoint)
        if (mutableState.value.machineBadges[profile.id] != badge) {
          mutableState.value =
            mutableState.value.copy(
              machineBadges = mutableState.value.machineBadges + (profile.id to badge)
            )
        }
        // A relayed machine polls at the slower cadence; every other machine refreshes each tick.
        val due =
          badge != TransportBadge.IROH_RELAYED ||
            now - (lastRefreshAt[profile.id] ?: 0L) >= pollIntervalMs(badge)
        if (due) refreshProfile(profile.id, background = true)
      }
  }

  override fun refreshProfile(id: String) = refreshProfile(id, background = false)

  private fun refreshProfile(id: String, background: Boolean) {
    val profile = mutableState.value.profiles.firstOrNull { it.id == id } ?: return
    onMonitorPause?.invoke(id, false)
    lastRefreshAt[id] = clock()
    disconnectedMachines.remove(id)
    machineJobs.remove(id)?.cancel()
    val token = (machineEpochs[id] ?: 0L) + 1
    machineEpochs[id] = token
    val previous = mutableState.value.machineConnection(profile)
    mutableState.value =
      mutableState.value.copy(
        machines =
          mutableState.value.machines +
            (id to
              previous.copy(
                connection =
                  if (previous.connection == ConnectionState.Connected || background)
                    previous.connection
                  else ConnectionState.Connecting,
                busy = !background,
              ))
      )
    machineJobs[id] = viewModelScope.launch { refresh(profile) }
  }

  private suspend fun refresh(profile: MachineProfile) {
    val token = epoch
    val machineToken = machineEpochs[profile.id]
    try {
      val client = bridgeFor(profile.endpoint)
      client.verify(profile.machineId)
      val agents = client.agents(profile.machineId)
      if (
        machineToken != machineEpochs[profile.id] ||
          profile.id in disconnectedMachines ||
          mutableState.value.profiles.none { it.id == profile.id }
      )
        return
      recordMachineName(profile, client.machineName, client.bridgeDetails)
      mutableState.value =
        mutableState.value.copy(
          machines =
            mutableState.value.machines +
              (profile.id to MachineConnection(ConnectionState.Connected, agents))
        )
      if (
        token != epoch ||
          mutableState.value.activeProfileId != profile.id ||
          mutableState.value.offlineSavedAt != null
      )
        return
      val old = mutableState.value.selectedAgent
      val selected = old?.let { target ->
        agents.firstOrNull {
          it.machineId == target.machineId &&
            it.sessionId == target.sessionId &&
            // A reused locator with a different native session is a replacement.
            it.nativeSessionRef == target.nativeSessionRef
        }
      }
      val replacedLocators =
        old != null &&
          selected == null &&
          agents.any { it.machineId == old.machineId && it.sessionId == old.sessionId }
      if (replacedLocators) {
        cancelOwnedWork()
        clearSavedSelection()
      }
      mutableState.value =
        mutableState.value.copy(
          agents = agents,
          selectedAgent = selected,
          connection =
            when {
              replacedLocators ->
                ConnectionState.Error("Selected session was replaced; select it again")
              old == null -> ConnectionState.Connected
              else -> mutableState.value.connection
            },
          busy = false,
          stream = if (replacedLocators) StreamState() else mutableState.value.stream,
          conversationTurns =
            if (replacedLocators) emptyList() else mutableState.value.conversationTurns,
          conversationSource =
            if (replacedLocators) null else mutableState.value.conversationSource,
          sessionStats = if (replacedLocators) null else mutableState.value.sessionStats,
        )
    } catch (error: Exception) {
      if (error is kotlinx.coroutines.CancellationException) throw error
      if (machineToken != machineEpochs[profile.id] || profile.id in disconnectedMachines) return
      val failed = ConnectionState.Error(error.message ?: "Bridge unavailable")
      mutableState.value =
        mutableState.value.copy(
          machines = mutableState.value.machines + (profile.id to MachineConnection(failed))
        )
      if (token == epoch && mutableState.value.activeProfileId == profile.id)
        mutableState.value = mutableState.value.copy(connection = failed, busy = false)
    }
  }

  override fun selectAgent(agent: AgentRow?) {
    mutableState.value = mutableState.value.copy(offlineSavedAt = null, conversationId = null)
    cancelOwnedWork()
    val profile = activeProfile()
    if (agent != null && profile?.machineId != agent.machineId) {
      mutableState.value =
        mutableState.value.copy(
          selectedAgent = null,
          stream = StreamState(),
          streamLive = false,
          conversationTurns = emptyList(),
          conversationSource = null,
          sessionStats = null,
          actionError = null,
          connection = ConnectionState.Error("Agent belongs to a different machine"),
        )
      return
    }
    mutableState.value =
      mutableState.value.copy(
        selectedAgent = agent,
        stream = StreamState(),
        streamLive = false,
        conversationTurns = emptyList(),
        conversationSource = null,
        sessionStats = null,
        actionError = null,
      )
    if (agent == null) clearSavedSelection()
    else {
      savedStateHandle[SELECTED_PROFILE] = profile?.id
      savedStateHandle[SELECTED_MACHINE] = agent.machineId.toString()
      savedStateHandle[SELECTED_LOCATOR] = agent.sessionId
      savedStateHandle[SELECTED_NATIVE_REF] = agent.nativeSessionRef
      pendingRestore = true
    }
    if (foreground && agent != null && profile != null) {
      loadConversation()
      openStream(profile, agent, allowLegacyInitial = true)
    }
  }

  private fun applyConversationSnapshot(snapshot: ConversationSnapshot) =
    com.azusachino.cappuccino.core
      .boundedConversation(if (snapshot.source == "canonical_log") snapshot.turns else emptyList())
      .also { recent ->
        mutableState.value =
          mutableState.value.copy(
            conversationTurns = recent.turns,
            omittedConversationTurns = recent.omittedMessages,
            omittedToolOperations = recent.omittedTools,
            conversationSource = snapshot.source,
            sessionStats = snapshot.stats,
            conversationId = snapshot.conversationId,
            offlineSavedAt = null,
          )
      }

  override fun loadConversation() {
    if (mutableState.value.offlineSavedAt != null) return
    val profile = activeProfile() ?: return
    val agent = mutableState.value.selectedAgent ?: return
    if (!foreground) return
    conversationRequested = true
    if (conversationJob?.isActive == true) return
    val client = bridgeFor(profile.endpoint)
    val token = epoch
    conversationJob = viewModelScope.launch {
      do {
        conversationRequested = false
        try {
          if (offlineHistory != null && client.verify(profile.machineId) != profile.machineId)
            throw ProtocolException("Machine identity changed")
          val snapshot = client.conversationSnapshot(agent.sessionId)
          if (
            (snapshot.machineId != null && snapshot.machineId != profile.machineId) ||
              (snapshot.conversationId != null && snapshot.machineId != profile.machineId)
          )
            throw ProtocolException("Conversation machine identity changed")
          val current = mutableState.value
          if (
            !isActive ||
              token != epoch ||
              !foreground ||
              current.activeProfileId != profile.id ||
              current.selectedAgent?.machineId != agent.machineId ||
              current.selectedAgent.sessionId != agent.sessionId
          )
            return@launch
          // Terminal snapshots already arrive through the stream. Reconstructing a
          // second, unstable Markdown history from them flashes/rekeys the screen.
          if (
            snapshot.conversationId != null &&
              (!isCanonicalConversationId(snapshot.conversationId) ||
                !snapshot.conversationId.startsWith("${agent.agent}:"))
          )
            throw ProtocolException("Canonical conversation provider mismatch")
          val recent = applyConversationSnapshot(snapshot)
          if (
            snapshot.source == "canonical_log" &&
              offlineHistory != null &&
              snapshot.conversationId == null
          ) {
            mutableState.value =
              mutableState.value.copy(
                offlineProblem =
                  "This bridge/session doesn't provide a canonical conversation ID. Offline history wasn't saved."
              )
          }
          if (
            snapshot.source == "canonical_log" &&
              offlineHistory != null &&
              snapshot.conversationId != null
          ) {
            try {
              withContext(offlineDispatcher) {
                offlineHistory.save(profile.machineId, agent, recent, snapshot.conversationId)
              }
              reloadSavedHistory()
            } catch (error: Exception) {
              if (error is kotlinx.coroutines.CancellationException) throw error
              mutableState.value =
                mutableState.value.copy(offlineProblem = "Couldn't save this conversation offline.")
            }
          }
        } catch (error: Exception) {
          if (error is kotlinx.coroutines.CancellationException) throw error
          // Keep earlier turns; on a newly opened view allow verified stream scrollback
          // rather than leaving a restored reader waiting for unavailable history.
          if (
            isActive &&
              token == epoch &&
              foreground &&
              mutableState.value.activeProfileId == profile.id &&
              mutableState.value.selectedAgent?.sessionId == agent.sessionId
          ) {
            mutableState.value =
              mutableState.value.copy(
                conversationSource = mutableState.value.conversationSource ?: "terminal_scrollback"
              )
          }
        }
      } while (isActive && conversationRequested && token == epoch && foreground)
    }
  }

  override fun setDraft(key: String, text: String) {
    val updated =
      if (text.isEmpty()) mutableState.value.drafts - key
      else mutableState.value.drafts + (key to text)
    mutableState.value = mutableState.value.copy(drafts = updated)
  }

  override fun acceptsMutation(profileId: String?, agent: AgentRow): Boolean {
    val current = mutableState.value.selectedAgent ?: return false
    return actionsLive() &&
      mutableState.value.activeProfileId == profileId &&
      current.machineId == agent.machineId &&
      current.sessionId == agent.sessionId &&
      current.paneId == agent.paneId &&
      current.nativeSessionRef == agent.nativeSessionRef
  }

  override fun submitPrompt(text: String, confirmed: Boolean) {
    if (mutableState.value.offlineSavedAt != null) return
    if (!actionsLive()) return
    val profile = activeProfile() ?: return
    val agent = mutableState.value.selectedAgent ?: return
    // /new waits for an idle agent; the composer refuses it too, this keeps it off the bridge.
    val agentStatus = mutableState.value.stream.agentStatus ?: agent.status
    if (busyAgentCommandReason(text, agentStatus.equals("working", ignoreCase = true)) != null)
      return
    val binding = MutationBinding.from(agent) ?: return
    val requestEpoch = epoch
    val key = agentSendKey(profile.id, agent)
    if (
      mutableState.value.promptSends[key]?.outcome == null && key in mutableState.value.promptSends
    )
      return
    val draftKey = agentDraftKey(agent)
    val submittedDraft = mutableState.value.drafts[draftKey].orEmpty()
    updatePromptSend(key, PromptSendState(text))
    val client = bridgeFor(profile.endpoint)
    viewModelScope.launch {
      var result: PromptSendState
      try {
        client.submitPrompt(agent.sessionId, text, confirmed, binding)
        result = PromptSendState(text, "Sent to bridge; agent admission is not confirmed.")
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) {
          result =
            PromptSendState(
              text,
              "Delivery unknown; draft retained. Do not resend unless verified.",
            )
        } else {
          val rejected = error is BridgeHttpRejectionException
          val outcome =
            if (rejected) "Rejected by bridge; draft retained."
            else "Delivery unknown; draft retained. Do not resend unless verified."
          result = PromptSendState(text, outcome)
          val currentDraft = mutableState.value.drafts[draftKey].orEmpty()
          if (currentDraft == submittedDraft) setDraft(draftKey, text)
          if (
            requestEpoch == epoch &&
              mutableState.value.activeProfileId == profile.id &&
              mutableState.value.selectedAgent?.sessionId == agent.sessionId &&
              mutableState.value.selectedAgent?.nativeSessionRef == agent.nativeSessionRef
          ) {
            mutableState.value = mutableState.value.copy(actionError = error.message ?: outcome)
            invalidateMutationSource(error)
          }
        }
      }
      val current = mutableState.value.promptSends[key]
      if (current?.outcome == null) updatePromptSend(key, result)
      if (result.outcome?.startsWith("Sent to bridge") == true) {
        val currentDraft = mutableState.value.drafts[draftKey].orEmpty()
        if (currentDraft == submittedDraft) setDraft(draftKey, "")
      }
    }
  }

  private fun updatePromptSend(key: String, send: PromptSendState) {
    mutableState.value =
      mutableState.value.copy(promptSends = mutableState.value.promptSends + (key to send))
  }

  override fun controlAgent(action: String) {
    val profile = activeProfile() ?: return
    if (!actionsLive()) return
    // Each request gets a fresh client; the capability comes from the machine's verified details.
    if (mutableState.value.bridgeDetails[profile.id]?.capabilities?.get("agent_control") != true)
      return
    runAgentAction("Failed to control the agent") { client, agent, binding ->
      client.controlAgent(agent.sessionId, action, binding)
    }
  }

  override fun answerPrompt(
    promptId: String,
    optionIndex: Int?,
    optionId: String?,
    action: String?,
  ) =
    runAgentAction("Failed to answer prompt") { client, agent, binding ->
      client.answerPrompt(agent.sessionId, promptId, optionIndex, optionId, action, binding)
    }

  override fun answerQuestions(
    promptId: String,
    answers: List<com.azusachino.cappuccino.core.QuestionAnswer>,
  ) =
    runAgentAction("Failed to answer the question") { client, agent, binding ->
      client.answerQuestions(agent.sessionId, promptId, answers, binding)
    }

  /**
   * An owner action on the selected agent. Its failure (a stale question, a busy agent) is reported
   * on its own; the connection stays as it is.
   */
  private fun runAgentAction(
    failure: String,
    send: suspend (BridgeTransport, AgentRow, MutationBinding) -> Unit,
  ) {
    if (mutableState.value.offlineSavedAt != null) return
    if (!actionsLive()) return
    val profile = activeProfile() ?: return
    val agent = mutableState.value.selectedAgent ?: return
    val binding = MutationBinding.from(agent) ?: return
    val client = bridgeFor(profile.endpoint)
    val requestEpoch = epoch
    mutableState.value = mutableState.value.copy(actionError = null)
    viewModelScope.launch {
      try {
        send(client, agent, binding)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (requestEpoch != epoch || mutableState.value.activeProfileId != profile.id) return@launch
        mutableState.value = mutableState.value.copy(actionError = error.message ?: failure)
        invalidateMutationSource(error)
      }
    }
  }

  private fun invalidateMutationSource(error: Exception) {
    if (
      error !is BridgeStaleSessionException &&
        error !is BridgeMutationOutcomeUnknownException &&
        error !is BridgeMutationUnsupportedException
    )
      return
    cancelOwnedWork()
    mutableState.value =
      mutableState.value.copy(
        streamLive = false,
        busy = false,
        connection = ConnectionState.Error(error.message ?: "Reselect the session before writing"),
        stream =
          mutableState.value.stream.copy(
            pendingPrompt = null,
            agentStatus = null,
            agentStatusDetail = null,
          ),
      )
  }

  fun setForeground(value: Boolean) {
    if (foreground == value) return
    foreground = value
    if (!value) {
      if (savedStateHandle.get<String>(SELECTED_PROFILE) != null) pendingRestore = true
      cancelOwnedWork()
      stopMachineJobs()
      mutableState.value =
        mutableState.value.copy(
          connection = ConnectionState.Disconnected,
          streamLive = false,
          stream =
            mutableState.value.stream.copy(
              pendingPrompt = null,
              agentStatus = null,
              agentStatusDetail = null,
            ),
          busy = false,
        )
    } else {
      if (pendingRestore) {
        restoreSelection()
        // Invalid saved ownership must not silently fall back to another profile.
        if (pendingRestore) {
          mutableState.value.profiles
            .filter {
              it.id != mutableState.value.activeProfileId && it.id !in disconnectedMachines
            }
            .forEach { refreshProfile(it.id) }
        }
      } else {
        if (mutableState.value.activeProfileId == null)
          mutableState.value =
            mutableState.value.copy(activeProfileId = mutableState.value.profiles.firstOrNull()?.id)
        refreshAll()
      }
    }
  }

  override fun removeProfile(id: String) {
    disconnectMachine(id)
    if (savedStateHandle.get<String>(SELECTED_PROFILE) == id) clearSavedSelection()
    store.remove(id)
    val current = mutableState.value
    mutableState.value =
      current.copy(
        profiles = store.read(),
        machines = current.machines - id,
        bridgeDetails = current.bridgeDetails - id,
        activeProfileId = current.activeProfileId.takeUnless { it == id },
      )
    reloadSavedHistory()
  }

  override fun retry() {
    if (mutableState.value.offlineSavedAt != null) return
    if (savedStateHandle.get<String>(SELECTED_PROFILE) != null && foreground) {
      cancelOwnedWork()
      pendingRestore = true
      restoreSelection()
      return
    }
    val profile = activeProfile() ?: return
    val selected = mutableState.value.selectedAgent
    cancelOwnedWork()
    if (foreground && selected != null) openStream(profile, selected)
    else {
      mutableState.value =
        mutableState.value.copy(busy = true, connection = ConnectionState.Connecting)
      operation = viewModelScope.launch { refresh(profile) }
    }
  }

  private fun actionsLive() = mutableState.value.canMutate

  private fun openStream(
    profile: MachineProfile,
    agent: AgentRow,
    allowLegacyInitial: Boolean = false,
  ) {
    cancelStream()
    val token = epoch
    val client = bridgeFor(profile.endpoint)
    streamJob = viewModelScope.launch {
      val retryBudget = StreamRetryBudget()
      var hasAttached = false
      while (foreground && token == epoch) {
        // Invalidate before any suspending handshake, not only after it succeeds.
        mutableState.value =
          mutableState.value.copy(
            streamLive = false,
            stream =
              mutableState.value.stream.copy(
                pendingPrompt = null,
                agentStatus = null,
                agentStatusDetail = null,
              ),
          )
        try {
          client.verify(profile.machineId)
          if (token != epoch) return@launch
          recordMachineName(profile, client.machineName, client.bridgeDetails)
          // Native identity must still match before attaching. Preserve explicitly selected
          // legacy views, but never treat two missing refs as proof for automatic recovery.
          val agents = client.agents(profile.machineId)
          if (!foreground || token != epoch) return@launch
          val bound = agents.firstOrNull {
            it.machineId == agent.machineId &&
              it.sessionId == agent.sessionId &&
              (agent.paneId == null || it.paneId == agent.paneId) &&
              ((agent.nativeSessionRef != null && it.nativeSessionRef == agent.nativeSessionRef) ||
                (allowLegacyInitial &&
                  !hasAttached &&
                  agent.nativeSessionRef == null &&
                  it.nativeSessionRef == null))
          }
          if (bound == null) {
            if (agent.nativeSessionRef == null) {
              // A legacy row without native identity cannot be bound; never invent one. Keep the
              // selection visible but fail closed until the owner reselects.
              mutableState.value =
                mutableState.value.copy(
                  agents = agents,
                  busy = false,
                  streamLive = false,
                  connection =
                    ConnectionState.Error(
                      "Selected session has no verified native identity; select it again"
                    ),
                )
            } else {
              epoch++
              mutableState.value =
                mutableState.value.copy(
                  agents = agents,
                  selectedAgent = null,
                  stream = StreamState(),
                  streamLive = false,
                  busy = false,
                  connection =
                    ConnectionState.Error(
                      "Selected session is missing or has changed; select it again"
                    ),
                )
              clearSavedSelection()
            }
            return@launch
          }
          if (hasAttached || !allowLegacyInitial) {
            // Serialize recovery history before opening a new stream. A cancelled previous
            // refresh cannot publish later: loadConversation checks its own job's liveness.
            conversationJob?.cancel()
            conversationJob = null
            conversationRequested = false
            val expectedConversation = mutableState.value.conversationId
            val snapshot = client.conversationSnapshot(agent.sessionId)
            if (!isActive || token != epoch || !foreground) return@launch
            if (snapshot.source != "canonical_log")
              throw java.io.IOException("Canonical history unavailable; retry to enable actions")
            val identity = snapshot.conversationId
            if (
              snapshot.machineId != profile.machineId ||
                identity == null ||
                !isCanonicalConversationId(identity) ||
                !identity.startsWith("${agent.agent}:") ||
                (expectedConversation != null && identity != expectedConversation)
            )
              throw ProtocolException("Recovery history identity changed or is unavailable")
            applyConversationSnapshot(snapshot)
          }
          hasAttached = true
          var opened = false
          client.stream(agent.sessionId).collect { event ->
            val selected = mutableState.value.selectedAgent
            if (
              token != epoch ||
                selected?.machineId != agent.machineId ||
                selected.sessionId != agent.sessionId
            )
              return@collect
            if (event is StreamEvent.Open) opened = true
            if (event is StreamEvent.AgentStatus && !opened)
              throw ProtocolException("Agent status arrived before stream_open")
            val current = mutableState.value
            val reduced = reduce(current.stream, event, agent.sessionId)
            val streamError = (event as? StreamEvent.Error)?.let { "${it.code}: ${it.message}" }
            val freshBaseline =
              event is StreamEvent.AgentStatus && event.sessionId == agent.sessionId
            mutableState.value =
              current.copy(
                stream = reduced,
                streamLive = if (freshBaseline) true else current.streamLive,
                connection =
                  when {
                    streamError != null -> ConnectionState.Error(streamError)
                    // Only a fresh agent_status marks the stream live; other events are transport.
                    freshBaseline -> ConnectionState.Connected
                    else -> current.connection
                  },
              )
            if (streamError != null) {
              if (event.code == UPSTREAM_UNAVAILABLE_CODE)
                throw BridgeUpstreamUnavailableError(streamError)
              throw BridgeStreamError(streamError)
            }
            if (
              event is StreamEvent.Open ||
                event is StreamEvent.Entries ||
                event is StreamEvent.Reset ||
                event is StreamEvent.AgentStatus
            )
              loadConversation()
          }
          break
        } catch (error: Exception) {
          if (error is kotlinx.coroutines.CancellationException) throw error
          if (!foreground || token != epoch || mutableState.value.activeProfileId != profile.id)
            return@launch
          // Every loss, including terminal failures and retry exhaustion, invalidates actions
          // and pending cards. Keep history available without presenting it as live state.
          mutableState.value =
            mutableState.value.copy(
              streamLive = false,
              stream =
                mutableState.value.stream.copy(
                  pendingPrompt = null,
                  agentStatus = null,
                  agentStatusDetail = null,
                ),
            )
          if (error is ProtocolException || error is SSLException || error is BridgeStreamError) {
            mutableState.value =
              mutableState.value.copy(
                connection = ConnectionState.Error(error.message ?: "Bridge protocol or TLS error")
              )
            break
          }
          val nextDelay = retryBudget.nextDelayMillis()
          if (nextDelay == null || !foreground || token != epoch) {
            mutableState.value =
              mutableState.value.copy(
                connection = ConnectionState.Error(error.message ?: "Stream failed")
              )
            break
          }
          mutableState.value = mutableState.value.copy(connection = ConnectionState.Recovering)
          retryPause(nextDelay)
        }
      }
    }
  }

  private fun restoreSelection() {
    val profileId = savedStateHandle.get<String>(SELECTED_PROFILE) ?: return
    val machineId =
      savedStateHandle.get<String>(SELECTED_MACHINE)?.let {
        runCatching { UUID.fromString(it) }.getOrNull()
      }
    val locator = savedStateHandle.get<String>(SELECTED_LOCATOR)
    val nativeRef = savedStateHandle.get<String>(SELECTED_NATIVE_REF)
    val profile = mutableState.value.profiles.firstOrNull { it.id == profileId }
    if (profile == null || profile.machineId != machineId || locator.isNullOrBlank()) {
      clearRestoredSelection("Saved agent selection is no longer available")
      return
    }
    // A saved selection without native identity cannot be matched safely; never guess.
    if (nativeRef.isNullOrBlank()) {
      clearRestoredSelection("Saved selection must be selected again")
      return
    }
    epoch++
    val restoreToken = epoch
    mutableState.value =
      mutableState.value.copy(
        activeProfileId = profileId,
        connection = ConnectionState.Recovering,
        streamLive = false,
        busy = true,
      )
    operation = viewModelScope.launch {
      try {
        val client = bridgeFor(profile.endpoint)
        client.verify(profile.machineId)
        val agents = client.agents(profile.machineId)
        if (restoreToken != epoch || !foreground) return@launch
        recordMachineName(profile, client.machineName, client.bridgeDetails)
        mutableState.value =
          mutableState.value.copy(
            machines =
              mutableState.value.machines +
                (profile.id to MachineConnection(ConnectionState.Connected, agents))
          )
        val match = agents.firstOrNull {
          it.machineId == machineId && it.sessionId == locator && it.nativeSessionRef == nativeRef
        }
        if (match == null) {
          mutableState.value = mutableState.value.copy(agents = agents)
          clearRestoredSelection("Saved agent is missing or has changed")
        } else {
          mutableState.value =
            mutableState.value.copy(
              agents = agents,
              selectedAgent = match,
              busy = false,
              connection = ConnectionState.Connected,
            )
          pendingRestore = false
          openStream(profile, match)
        }
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (restoreToken == epoch && foreground) {
          if (error is ProtocolException)
            clearRestoredSelection(error.message ?: "Saved machine identity changed")
          else
            mutableState.value =
              mutableState.value.copy(
                connection =
                  ConnectionState.Error(error.message ?: "Could not restore saved agent"),
                busy = false,
              )
        }
      }
    }
  }

  override fun closeSavedConversation() {
    cancelOwnedWork()
    clearSavedSelection()
    mutableState.value =
      mutableState.value.copy(
        selectedAgent = null,
        stream = StreamState(),
        streamLive = false,
        conversationTurns = emptyList(),
        omittedConversationTurns = 0,
        omittedToolOperations = 0,
        conversationSource = null,
        sessionStats = null,
        conversationId = null,
        offlineSavedAt = null,
        busy = false,
      )
  }

  private fun clearSavedSelection() {
    savedStateHandle[SELECTED_PROFILE] = null
    savedStateHandle[SELECTED_MACHINE] = null
    savedStateHandle[SELECTED_LOCATOR] = null
    savedStateHandle[SELECTED_NATIVE_REF] = null
    pendingRestore = false
  }

  private fun clearRestoredSelection(message: String) {
    mutableState.value =
      mutableState.value.copy(
        selectedAgent = null,
        stream = StreamState(),
        streamLive = false,
        busy = false,
        connection = ConnectionState.Error(message),
      )
    clearSavedSelection()
  }

  private fun activeProfile() =
    mutableState.value.profiles.firstOrNull { it.id == mutableState.value.activeProfileId }

  private fun cancelStream() {
    streamJob?.cancel()
    streamJob = null
  }

  private fun cancelOwnedWork() {
    epoch++
    operation?.cancel()
    operation = null
    cancelStream()
    conversationJob?.cancel()
    conversationJob = null
    conversationRequested = false
  }

  private fun stopMachineJobs() {
    machineJobs.keys.toList().forEach { id ->
      machineEpochs[id] = (machineEpochs[id] ?: 0L) + 1
      machineJobs.remove(id)?.cancel()
    }
    mutableState.value =
      mutableState.value.copy(
        machines =
          mutableState.value.machines.mapValues { (_, value) ->
            value.copy(connection = ConnectionState.Disconnected, busy = false)
          }
      )
  }

  companion object {
    const val SELECTED_PROFILE = "selected_profile_id"
    const val SELECTED_MACHINE = "selected_machine_id"
    const val SELECTED_LOCATOR = "selected_agent_locator"
    const val SELECTED_NATIVE_REF = "selected_agent_native_ref"
    const val RESTORE_PENDING = "selection_restore_pending"
  }

  override fun onCleared() {
    cancelOwnedWork()
    stopMachineJobs()
    addJob?.cancel()
  }
}
