package com.azusachino.cappuccino.core

import java.util.UUID

internal data class AgentCompletion(val agent: AgentRow, val sequence: Long) {
  val key: String
    get() = "${agent.machineId}:${agent.paneId}:${agent.nativeSessionRef}:$sequence"
}

/** Catalog completion markers only: no inference from text, idle, or success. */
internal class AgentCompletions {
  private data class Observed(val reference: String, val state: Long)

  private val known = mutableMapOf<Pair<UUID, String>, Observed>()

  fun observe(machine: UUID, agents: List<AgentRow>): List<AgentCompletion> {
    val present = mutableSetOf<Pair<UUID, String>>()
    val completions = mutableListOf<AgentCompletion>()
    agents.forEach { agent ->
      require(agent.machineId == machine)
      val pane = agent.paneId ?: return@forEach
      val key = machine to pane
      present += key
      val reference = agent.nativeSessionRef
      val state = agent.stateChangeSeq
      if (reference == null || state == null || state < 0) {
        known.remove(key)
        return@forEach
      }
      val previous = known[key]
      val completed = agent.completionSeq
      if (
        previous != null &&
          previous.reference == reference &&
          state >= previous.state &&
          completed != null &&
          completed > previous.state &&
          completed <= state
      ) {
        completions += AgentCompletion(agent, completed)
      }
      known[key] = Observed(reference, state)
    }
    known.keys.removeAll { it.first == machine && it !in present }
    return completions
  }

  /** A transport gap is a fresh baseline, not evidence of observed completion. */
  fun reset(machine: UUID) {
    known.keys.removeAll { it.first == machine }
  }
}
