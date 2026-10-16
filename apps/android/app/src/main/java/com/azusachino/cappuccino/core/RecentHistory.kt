package com.azusachino.cappuccino.core

val ConversationPart.visibleInChat: Boolean
  get() = !(this is ConversationPart.ResponseStatus && status == "failed")

fun conversationFollowContent(turns: List<ConversationTurn>): List<ConversationTurn> =
  turns
    .mapNotNull { turn ->
      val visible = turn.parts.filter { it.visibleInChat }
      if (turn.text.isNullOrBlank() && visible.isEmpty()) null else turn.copy(parts = visible)
    }
    .takeLast(1) // Older-window eviction is not a new answer to follow.

const val MAX_RECENT_MESSAGES = 200
const val MAX_RECENT_TOOLS = 100

data class RecentConversation(
  val turns: List<ConversationTurn>,
  val omittedMessages: Int,
  val omittedTools: Int,
)

fun boundedConversation(turns: List<ConversationTurn>): RecentConversation {
  val messages = recentWindow(turns, MAX_RECENT_MESSAGES)
  val totalTools = turns.sumOf { turn -> turn.parts.count { it is ConversationPart.ToolCall } }
  val windowTools =
    messages.items.sumOf { turn -> turn.parts.count { it is ConversationPart.ToolCall } }
  var skipTools = (windowTools - MAX_RECENT_TOOLS).coerceAtLeast(0)
  val clipped =
    messages.items.map { turn ->
      turn.copy(
        parts =
          turn.parts.mapIndexedNotNull { index, part ->
            if (part is ConversationPart.ToolCall) {
              if (skipTools > 0) {
                skipTools--
                null
              } else part.copy(originalPartIndex = part.originalPartIndex ?: index)
            } else part
          }
      )
    }
  return RecentConversation(
    clipped,
    messages.omitted,
    totalTools - minOf(windowTools, MAX_RECENT_TOOLS),
  )
}

data class RecentWindow<T>(val items: List<T>, val omitted: Int)

fun <T> recentWindow(items: List<T>, limit: Int): RecentWindow<T> {
  require(limit > 0)
  return RecentWindow(items.takeLast(limit), (items.size - limit).coerceAtLeast(0))
}
