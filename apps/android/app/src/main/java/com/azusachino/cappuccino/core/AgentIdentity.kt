package com.azusachino.cappuccino.core

import java.util.UUID

data class AgentIdentity(val machineId: UUID, val sessionId: String)

enum class MessageDelivery(val label: String) {
  NUDGE("Nudge"),
  FOLLOW_UP("Follow-up"),
}
