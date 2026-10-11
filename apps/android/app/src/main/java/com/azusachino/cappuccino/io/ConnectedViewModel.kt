package com.azusachino.cappuccino.io

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.core.*
import java.util.UUID
import javax.net.ssl.SSLException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private class BridgeStreamError(message: String) : Exception(message)

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

data class ConnectedUiState(
  val profiles: List<MachineProfile> = emptyList(),
  val bridgeDetails: Map<String, BridgeDetails> = emptyMap(),
  val machines: Map<String, MachineConnection> = emptyMap(),
  val addError: String? = null,
  val addedProfileIds: Set<String> = emptySet(),
  val activeProfileId: String? = null,
  val agents: List<AgentRow> = emptyList(),
  val selectedAgent: AgentRow? = null,
  val stream: StreamState = StreamState(),
  val conversationTurns: List<ConversationTurn> = emptyList(),
  val omittedConversationTurns: Int = 0,
  val omittedToolOperations: Int = 0,
  val conversationSource: String? = null,
  val connection: ConnectionState = ConnectionState.Disconnected,
  val busy: Boolean = false,
  val themeMode: com.azusachino.cappuccino.ui.ThemeMode =
    com.azusachino.cappuccino.ui.ThemeMode.SYSTEM,
)

interface ConnectedActions {
  fun addMachine(label: String, url: String)

  fun renameProfile(id: String, label: String)

  fun selectProfile(id: String)

  fun disconnectProfile()

  fun refreshProfile(id: String) = refresh()

  fun disconnectMachine(id: String) = disconnectProfile()

  fun refreshAll() = refresh()

  fun refresh()

  fun selectAgent(agent: AgentRow?)

  fun removeProfile(id: String)

  fun retry()

  fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode)

  fun submitPrompt(text: String)

  fun answerPrompt(promptId: String, optionIndex: Int?, optionId: String?, action: String?)

  fun loadConversation()
}

class ConnectedViewModel
internal constructor(
  private val store: MachineProfileStore,
  private val bridgeFor: (Endpoint) -> BridgeTransport,
  private val retryPause: suspend (Long) -> Unit,
  private val savedStateHandle: SavedStateHandle,
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

  override fun addMachine(label: String, url: String) {
    addEpoch++
    val token = addEpoch
    addJob?.cancel()
    mutableState.value = mutableState.value.copy(busy = true, addError = null)
    addJob = viewModelScope.launch {
      try {
        val endpoint = Endpoint.parse(url, allowLocalHttp = BuildConfig.DEBUG)
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
    cancelOwnedWork()
    clearSavedSelection()
    val profile = mutableState.value.profiles.firstOrNull { it.id == id } ?: return
    mutableState.value =
      mutableState.value.copy(
        activeProfileId = id,
        selectedAgent = null,
        stream = StreamState(),
        connection = mutableState.value.machineConnection(profile).connection,
        agents = mutableState.value.machineConnection(profile).agents,
        conversationTurns = emptyList(),
        conversationSource = null,
        busy = false,
      )
    if (mutableState.value.machineConnection(profile).connection != ConnectionState.Connected)
      refreshProfile(id)
  }

  override fun disconnectProfile() {
    mutableState.value.activeProfileId?.let { disconnectMachine(it) }
  }

  override fun disconnectMachine(id: String) {
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
          conversationTurns = emptyList(),
          conversationSource = null,
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
    mutableState.value.profiles
      .filter { it.id !in disconnectedMachines }
      .forEach { profile ->
        if (machineJobs[profile.id]?.isActive != true) refreshProfile(profile.id)
      }
  }

  override fun refreshProfile(id: String) {
    val profile = mutableState.value.profiles.firstOrNull { it.id == id } ?: return
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
                  if (previous.connection == ConnectionState.Connected) previous.connection
                  else ConnectionState.Connecting,
                busy = true,
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
      if (token != epoch || mutableState.value.activeProfileId != profile.id) return
      val selected =
        mutableState.value.selectedAgent?.let { old ->
          agents.firstOrNull { it.machineId == old.machineId && it.sessionId == old.sessionId }
        }
      mutableState.value =
        mutableState.value.copy(
          agents = agents,
          selectedAgent = selected,
          connection =
            if (mutableState.value.selectedAgent == null) ConnectionState.Connected
            else mutableState.value.connection,
          busy = false,
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
    cancelOwnedWork()
    val profile = activeProfile()
    if (agent != null && profile?.machineId != agent.machineId) {
      mutableState.value =
        mutableState.value.copy(
          selectedAgent = null,
          stream = StreamState(),
          conversationTurns = emptyList(),
          conversationSource = null,
          connection = ConnectionState.Error("Agent belongs to a different machine"),
        )
      return
    }
    mutableState.value =
      mutableState.value.copy(
        selectedAgent = agent,
        stream = StreamState(),
        conversationTurns = emptyList(),
        conversationSource = null,
      )
    if (agent == null) clearSavedSelection()
    else {
      savedStateHandle[SELECTED_PROFILE] = profile?.id
      savedStateHandle[SELECTED_MACHINE] = agent.machineId.toString()
      savedStateHandle[SELECTED_LOCATOR] = agent.sessionId
      pendingRestore = true
    }
    if (foreground && agent != null && profile != null) {
      loadConversation()
      openStream(profile, agent)
    }
  }

  override fun loadConversation() {
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
          val snapshot = client.conversationSnapshot(agent.sessionId)
          val current = mutableState.value
          if (
            token != epoch ||
              !foreground ||
              current.activeProfileId != profile.id ||
              current.selectedAgent?.machineId != agent.machineId ||
              current.selectedAgent.sessionId != agent.sessionId
          )
            return@launch
          // Terminal snapshots already arrive through the stream. Reconstructing a
          // second, unstable Markdown history from them flashes/rekeys the screen.
          val recent =
            com.azusachino.cappuccino.core.boundedConversation(
              if (snapshot.source == "canonical_log") snapshot.turns else emptyList()
            )
          mutableState.value =
            current.copy(
              conversationTurns = recent.turns,
              omittedConversationTurns = recent.omittedMessages,
              omittedToolOperations = recent.omittedTools,
              conversationSource = snapshot.source,
            )
        } catch (error: Exception) {
          if (error is kotlinx.coroutines.CancellationException) throw error
          // Keep earlier turns; on a newly opened view allow verified stream scrollback
          // rather than leaving a restored reader waiting for unavailable history.
          if (
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
      } while (conversationRequested && token == epoch && foreground)
    }
  }

  override fun submitPrompt(text: String) {
    val profile = activeProfile() ?: return
    val agent = mutableState.value.selectedAgent ?: return
    val client = bridgeFor(profile.endpoint)
    val requestEpoch = epoch
    viewModelScope.launch {
      try {
        client.submitPrompt(agent.sessionId, text)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (requestEpoch != epoch || mutableState.value.activeProfileId != profile.id) return@launch
        mutableState.value =
          mutableState.value.copy(
            connection = ConnectionState.Error(error.message ?: "Failed to submit prompt")
          )
      }
    }
  }

  override fun answerPrompt(
    promptId: String,
    optionIndex: Int?,
    optionId: String?,
    action: String?,
  ) {
    val profile = activeProfile() ?: return
    val agent = mutableState.value.selectedAgent ?: return
    val client = bridgeFor(profile.endpoint)
    val requestEpoch = epoch
    viewModelScope.launch {
      try {
        client.answerPrompt(agent.sessionId, promptId, optionIndex, optionId, action)
      } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        if (requestEpoch != epoch || mutableState.value.activeProfileId != profile.id) return@launch
        mutableState.value =
          mutableState.value.copy(
            connection = ConnectionState.Error(error.message ?: "Failed to answer prompt")
          )
      }
    }
  }

  fun setForeground(value: Boolean) {
    if (foreground == value) return
    foreground = value
    if (!value) {
      if (savedStateHandle.get<String>(SELECTED_PROFILE) != null) pendingRestore = true
      cancelOwnedWork()
      stopMachineJobs()
      mutableState.value =
        mutableState.value.copy(connection = ConnectionState.Disconnected, busy = false)
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
  }

  override fun retry() {
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

  private fun openStream(profile: MachineProfile, agent: AgentRow) {
    cancelStream()
    val token = epoch
    val client = bridgeFor(profile.endpoint)
    streamJob = viewModelScope.launch {
      val retryBudget = StreamRetryBudget()
      while (foreground && token == epoch) {
        try {
          client.verify(profile.machineId)
          if (token != epoch) return@launch
          recordMachineName(profile, client.machineName, client.bridgeDetails)
          client.stream(agent.sessionId).collect { event ->
            val selected = mutableState.value.selectedAgent
            if (
              token != epoch ||
                selected?.machineId != agent.machineId ||
                selected.sessionId != agent.sessionId
            )
              return@collect
            val current = mutableState.value
            val reduced = reduce(current.stream, event, agent.sessionId)
            val streamError = (event as? StreamEvent.Error)?.let { "${it.code}: ${it.message}" }
            mutableState.value =
              current.copy(
                stream = reduced,
                connection = streamError?.let(ConnectionState::Error) ?: ConnectionState.Connected,
              )
            if (streamError != null) throw BridgeStreamError(streamError)
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
    val profile = mutableState.value.profiles.firstOrNull { it.id == profileId }
    if (profile == null || profile.machineId != machineId || locator.isNullOrBlank()) {
      clearRestoredSelection("Saved agent selection is no longer available")
      return
    }
    epoch++
    val restoreToken = epoch
    mutableState.value =
      mutableState.value.copy(
        activeProfileId = profileId,
        connection = ConnectionState.Recovering,
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
        val match = agents.firstOrNull { it.machineId == machineId && it.sessionId == locator }
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

  private fun clearSavedSelection() {
    savedStateHandle[SELECTED_PROFILE] = null
    savedStateHandle[SELECTED_MACHINE] = null
    savedStateHandle[SELECTED_LOCATOR] = null
    pendingRestore = false
  }

  private fun clearRestoredSelection(message: String) {
    mutableState.value =
      mutableState.value.copy(
        selectedAgent = null,
        stream = StreamState(),
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
    const val RESTORE_PENDING = "selection_restore_pending"
  }

  override fun onCleared() {
    cancelOwnedWork()
    stopMachineJobs()
    addJob?.cancel()
  }
}
