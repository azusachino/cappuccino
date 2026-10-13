package com.azusachino.cappuccino.core

/** Common presentation concepts; a result does not establish successful execution. */
enum class ToolActivityState {
  RESULT_NOT_RECORDED,
  RESULT_AVAILABLE,
  FAILED,
}

val ConversationPart.ToolCall.activityState: ToolActivityState
  get() =
    when {
      isError == true -> ToolActivityState.FAILED
      output != null -> ToolActivityState.RESULT_AVAILABLE
      // Missing historical output is not evidence of an active or queued operation.
      else -> ToolActivityState.RESULT_NOT_RECORDED
    }
