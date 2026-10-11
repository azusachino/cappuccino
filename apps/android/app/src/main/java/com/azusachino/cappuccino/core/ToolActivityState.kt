package com.azusachino.cappuccino.core

/** Common presentation concepts; a result does not establish successful execution. */
enum class ToolActivityState {
  AWAITING_RESULT,
  RESULT_AVAILABLE,
  FAILED,
}

val ConversationPart.ToolCall.activityState: ToolActivityState
  get() =
    when {
      isError == true -> ToolActivityState.FAILED
      output != null -> ToolActivityState.RESULT_AVAILABLE
      else -> ToolActivityState.AWAITING_RESULT
    }
