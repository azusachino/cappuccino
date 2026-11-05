package com.azusachino.cappuccino.ui

import com.azusachino.cappuccino.core.AgentRow
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineConnection
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.machineConnection

/**
 * What the owner sees about a machine's connection. Every surface (row, details sheet, session
 * header, composer) reads this one mapping, so they cannot disagree about the same state.
 */
sealed interface ConnectionStatus {
  /** The label shown on rows and in the details sheet. */
  val label: String

  /** The key [agentStatusStyle] takes for a badge. */
  val styleKey: String

  data object Connected : ConnectionStatus {
    override val label = "Connected"
    override val styleKey = "unknown"
  }

  data object Connecting : ConnectionStatus {
    override val label = "Connecting"
    override val styleKey = "connecting"
  }

  data class Reconnecting(val attempt: Int) : ConnectionStatus {
    override val label = if (attempt > 0) "Reconnecting · attempt $attempt" else "Reconnecting"
    override val styleKey = "reconnecting"
  }

  data object NotConnected : ConnectionStatus {
    override val label = "Not connected"
    override val styleKey = "disconnected"
  }

  data object Offline : ConnectionStatus {
    override val label = "Offline"
    override val styleKey = "offline"
  }

  data object ProtocolConflict : ConnectionStatus {
    override val label = "Protocol conflict"
    override val styleKey = "offline"
  }
}

/** What the session screen shows: its connection while it is not live, otherwise the agent. */
sealed interface SessionStatus {
  /** The session is attaching and has nothing to show yet. */
  data object Loading : SessionStatus

  data class Link(val status: ConnectionStatus) : SessionStatus

  /** An agent status key: working, idle, done, blocked, or unknown. */
  data class Agent(val key: String) : SessionStatus
}

internal fun ConnectionState.toStatus(attempt: Int = 0): ConnectionStatus =
  when (this) {
    ConnectionState.Connected -> ConnectionStatus.Connected
    ConnectionState.Connecting -> ConnectionStatus.Connecting
    ConnectionState.Recovering -> ConnectionStatus.Reconnecting(attempt)
    ConnectionState.Disconnected -> ConnectionStatus.NotConnected
    is ConnectionState.Error ->
      if (message.startsWith("Incompatible bridge protocol")) ConnectionStatus.ProtocolConflict
      else ConnectionStatus.Offline
  }

/** The status of [profile]'s machine: its own connection, never the session's. */
internal fun ConnectedUiState.machineStatus(profile: MachineProfile): ConnectionStatus =
  machineConnection(profile).let { it.connection.toStatus(it.reconnectAttempt) }

/**
 * The session screen's status. The worse of the machine's status and the stream's connection wins,
 * and an agent status is shown only while the machine is connected and the stream is live.
 */
internal fun ConnectedUiState.sessionStatus(selected: AgentRow?): SessionStatus {
  val activeProfile = profiles.firstOrNull { it.id == activeProfileId }
  val machine = activeProfile?.let { machineStatus(it) }
  val effective =
    if (machine != null && machine != ConnectionStatus.Connected) machine
    else connection.toStatus(reconnectAttempt)
  val noContent = conversationTurns.isEmpty() && stream.entries.isEmpty()
  val saved = offlineSavedAt != null
  if (!saved && noContent && effective == ConnectionStatus.Connected && !streamLive) {
    return SessionStatus.Loading
  }
  if (
    !saved &&
      noContent &&
      (effective == ConnectionStatus.Connecting || effective is ConnectionStatus.Reconnecting)
  ) {
    return SessionStatus.Loading
  }
  if (effective != ConnectionStatus.Connected) return SessionStatus.Link(effective)
  if (!streamLive && selected == null) return SessionStatus.Link(ConnectionStatus.Connecting)
  if (stream.pendingPrompt != null) return SessionStatus.Agent("blocked")
  return SessionStatus.Agent((stream.agentStatus ?: selected?.status)?.lowercase() ?: "unknown")
}

/** The header badge key for a session status. */
internal fun SessionStatus.styleKey(selected: AgentRow? = null): String =
  when (this) {
    SessionStatus.Loading -> selected?.status?.lowercase() ?: "connecting"
    is SessionStatus.Link ->
      if (this.status == ConnectionStatus.Connecting && selected != null)
        selected.status.lowercase()
      else this.status.styleKey
    is SessionStatus.Agent -> this.key
  }

/** The composer's placeholder while it is disabled. */
internal fun ConnectionStatus.composerLabel(): String =
  when (this) {
    ConnectionStatus.Connecting -> "Connecting…"
    is ConnectionStatus.Reconnecting -> "Reconnecting…"
    ConnectionStatus.Offline -> "Offline"
    ConnectionStatus.ProtocolConflict -> "Protocol conflict"
    ConnectionStatus.Connected,
    ConnectionStatus.NotConnected -> "Not connected"
  }

/** The row's and sheet's status word for the machine behind [profile]. */
internal fun machineInformationStatus(profile: MachineProfile, state: ConnectedUiState): String =
  state.machineStatus(profile).label

/** Keep the machine row's wording in step with the shared mapping. */
internal fun MachineConnection.statusLabel(): String = connection.toStatus(reconnectAttempt).label
