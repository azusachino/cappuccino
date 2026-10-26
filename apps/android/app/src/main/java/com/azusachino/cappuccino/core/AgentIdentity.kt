package com.azusachino.cappuccino.core

import java.util.UUID

data class AgentIdentity(val machineId: UUID, val sessionId: String)

/** Reported native binding, not a canonical conversation ID or atomic fencing token. */
class MutationBinding
private constructor(
  val machineId: UUID,
  val paneId: String,
  val nativeSessionRef: String,
) {
  companion object {
    fun from(agent: AgentRow): MutationBinding? {
      val pane = agent.paneId?.takeIf(::validField) ?: return null
      val reference = agent.nativeSessionRef?.takeIf(::validField) ?: return null
      return MutationBinding(agent.machineId, pane, reference)
    }

    private fun validField(value: String) =
      value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 256
  }
}

enum class MessageDelivery(val label: String) {
  NUDGE("Nudge"),
  FOLLOW_UP("Follow-up"),
}
