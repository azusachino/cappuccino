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

data class ConnectedUiState(
  val profiles: List<MachineProfile> = emptyList(),
  val addedProfileIds: Set<String> = emptySet(),
  val activeProfileId: String? = null,
  val agents: List<AgentRow> = emptyList(),
  val selectedAgent: AgentRow? = null,
  val stream: StreamState = StreamState(),
  val connection: ConnectionState = ConnectionState.Disconnected,
  val busy: Boolean = false,
  val themeMode: com.azusachino.cappuccino.ui.ThemeMode =
    com.azusachino.cappuccino.ui.ThemeMode.SYSTEM,
)

interface ConnectedActions {
  fun addMachine(label: String, url: String)

  fun selectProfile(id: String)

  fun disconnectProfile()

  fun refresh()

  fun selectAgent(agent: AgentRow?)

  fun removeProfile(id: String)

  fun retry()

  fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode)
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
  private var operation: Job? = null
  private var streamJob: Job? = null
  private var foreground = false
  private var epoch = 0L
  private var pendingRestore = savedStateHandle.get<String>(SELECTED_PROFILE) != null

  override fun addMachine(label: String, url: String) {
    cancelOwnedWork()
    val token = epoch
    mutableState.value =
      mutableState.value.copy(busy = true, connection = ConnectionState.Connecting)
    operation = viewModelScope.launch {
      try {
        val endpoint = Endpoint.parse(url, allowLocalHttp = BuildConfig.DEBUG)
        val machineId = bridgeFor(endpoint).verify()
        if (token != epoch) return@launch
        val profile =
          MachineProfile(
            UUID.randomUUID().toString(),
            label.ifBlank { endpoint.base.host },
            endpoint,
            machineId,
          )
        store.save(profile)
        mutableState.value =
          mutableState.value.copy(
            profiles = store.read(),
            addedProfileIds = mutableState.value.addedProfileIds + profile.id,
            activeProfileId = profile.id,
            connection = ConnectionState.Connected,
            busy = false,
          )
        refresh(profile)
      } catch (error: Exception) {
        if (token == epoch)
          mutableState.value =
            mutableState.value.copy(
              connection = ConnectionState.Error(error.message ?: "Connection failed"),
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
        connection = ConnectionState.Connecting,
        busy = true,
      )
    operation = viewModelScope.launch { refresh(profile) }
  }

  override fun disconnectProfile() {
    cancelOwnedWork()
    clearSavedSelection()
    mutableState.value =
      mutableState.value.copy(
        activeProfileId = null,
        selectedAgent = null,
        agents = emptyList(),
        stream = StreamState(),
        connection = ConnectionState.Disconnected,
        busy = false,
      )
  }

  override fun setThemeMode(mode: com.azusachino.cappuccino.ui.ThemeMode) {
    store.saveThemeMode(mode)
    mutableState.value = mutableState.value.copy(themeMode = mode)
  }

  override fun refresh() {
    val profile = activeProfile() ?: return
    cancelOwnedWork()
    mutableState.value =
      mutableState.value.copy(busy = true, connection = ConnectionState.Connecting)
    operation = viewModelScope.launch { refresh(profile) }
  }

  private suspend fun refresh(profile: MachineProfile) {
    val token = epoch
    try {
      val client = bridgeFor(profile.endpoint)
      client.verify(profile.machineId)
      val agents = client.agents(profile.machineId)
      if (token != epoch) return
      val selected =
        mutableState.value.selectedAgent?.let { old ->
          agents.firstOrNull { it.machineId == old.machineId && it.sessionId == old.sessionId }
        }
      mutableState.value =
        mutableState.value.copy(
          agents = agents,
          selectedAgent = selected,
          connection = ConnectionState.Connected,
          busy = false,
        )
      if (foreground && selected != null) openStream(profile, selected)
    } catch (error: Exception) {
      if (token == epoch)
        mutableState.value =
          mutableState.value.copy(
            connection = ConnectionState.Error(error.message ?: "Bridge unavailable"),
            busy = false,
          )
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
          connection = ConnectionState.Error("Agent belongs to a different machine"),
        )
      return
    }
    mutableState.value = mutableState.value.copy(selectedAgent = agent, stream = StreamState())
    if (agent == null) clearSavedSelection()
    else {
      savedStateHandle[SELECTED_PROFILE] = profile?.id
      savedStateHandle[SELECTED_MACHINE] = agent.machineId.toString()
      savedStateHandle[SELECTED_LOCATOR] = agent.sessionId
      pendingRestore = true
    }
    if (foreground && agent != null && profile != null) openStream(profile, agent)
  }

  fun setForeground(value: Boolean) {
    if (foreground == value) return
    foreground = value
    if (!value) {
      if (savedStateHandle.get<String>(SELECTED_PROFILE) != null) pendingRestore = true
      cancelOwnedWork()
      mutableState.value =
        mutableState.value.copy(connection = ConnectionState.Disconnected, busy = false)
    } else {
      if (pendingRestore) {
        restoreSelection()
      } else {
        val profile = activeProfile() ?: return
        mutableState.value =
          mutableState.value.copy(connection = ConnectionState.Recovering, busy = true)
        operation = viewModelScope.launch { refresh(profile) }
      }
    }
  }

  override fun removeProfile(id: String) {
    cancelOwnedWork()
    if (savedStateHandle.get<String>(SELECTED_PROFILE) == id) clearSavedSelection()
    store.remove(id)
    val profiles = store.read()
    mutableState.value =
      ConnectedUiState(profiles = profiles, activeProfileId = profiles.firstOrNull()?.id)
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
          }
          break
        } catch (error: Exception) {
          if (error is kotlinx.coroutines.CancellationException) throw error
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
  }

  companion object {
    const val SELECTED_PROFILE = "selected_profile_id"
    const val SELECTED_MACHINE = "selected_machine_id"
    const val SELECTED_LOCATOR = "selected_agent_locator"
    const val RESTORE_PENDING = "selection_restore_pending"
  }

  override fun onCleared() {
    cancelOwnedWork()
  }
}
