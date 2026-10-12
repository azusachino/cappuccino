package com.azusachino.cappuccino.core

import java.time.Duration
import java.time.Instant

/** A turn read in order: prose stays prose, consecutive thinking/tool work becomes one activity. */
sealed interface TranscriptSegment {
  data class Prose(val part: ConversationPart) : TranscriptSegment

  data class Activity(val parts: List<ConversationPart>) : TranscriptSegment
}

private val ConversationPart.isActivity: Boolean
  get() = this is ConversationPart.Thinking || this is ConversationPart.ToolCall

fun transcriptSegments(parts: List<ConversationPart>): List<TranscriptSegment> {
  val segments = mutableListOf<TranscriptSegment>()
  var activity = mutableListOf<ConversationPart>()
  parts.forEach { part ->
    if (part.isActivity) activity += part
    else {
      if (activity.isNotEmpty()) segments += TranscriptSegment.Activity(activity)
      activity = mutableListOf()
      segments += TranscriptSegment.Prose(part)
    }
  }
  if (activity.isNotEmpty()) segments += TranscriptSegment.Activity(activity)
  return segments
}

enum class ToolKind(val verb: String, val singular: String, val plural: String) {
  READ("read", "file", "files"),
  SEARCH("ran", "search", "searches"),
  EDIT("edited", "file", "files"),
  WRITE("wrote", "file", "files"),
  COMMAND("ran", "command", "commands"),
  OTHER("used", "tool", "tools"),
}

// Pi and Claude Code name the same operations differently; unknown names stay generic.
fun toolKind(name: String): ToolKind =
  when (name.lowercase()) {
    "read",
    "view",
    "ls",
    "notebookread" -> ToolKind.READ
    "grep",
    "find",
    "glob",
    "search",
    "websearch",
    "webfetch" -> ToolKind.SEARCH
    "edit",
    "multiedit",
    "notebookedit",
    "apply_patch" -> ToolKind.EDIT
    "write" -> ToolKind.WRITE
    "bash",
    "shell",
    "exec",
    "run_command" -> ToolKind.COMMAND
    else -> ToolKind.OTHER
  }

/** "Thought, edited 1 file, ran 2 commands": counts what happened, claims nothing about results. */
fun activitySummary(parts: List<ConversationPart>): String {
  val pieces = mutableListOf<String>()
  if (parts.any { it is ConversationPart.Thinking }) pieces += "thought"
  val tools = parts.filterIsInstance<ConversationPart.ToolCall>()
  ToolKind.entries.forEach { kind ->
    val count = tools.count { toolKind(it.name) == kind }
    if (count > 0) pieces += "${kind.verb} $count ${if (count == 1) kind.singular else kind.plural}"
  }
  return pieces.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** Wall time from the first recorded start to the last recorded result, if both exist. */
fun activityDuration(parts: List<ConversationPart>): Duration? {
  val tools = parts.filterIsInstance<ConversationPart.ToolCall>()
  val start = tools.mapNotNull { it.startedAt?.let(::instantOrNull) }.minOrNull() ?: return null
  val end = tools.mapNotNull { it.finishedAt?.let(::instantOrNull) }.maxOrNull() ?: return null
  return Duration.between(start, end).takeUnless { it.isNegative }
}

fun toolDuration(part: ConversationPart.ToolCall): Duration? {
  val start = part.startedAt?.let(::instantOrNull) ?: return null
  val end = part.finishedAt?.let(::instantOrNull) ?: return null
  return Duration.between(start, end).takeUnless { it.isNegative }
}

fun compactDuration(duration: Duration): String {
  val millis = duration.toMillis()
  return when {
    millis < 1_000 -> "${millis}ms"
    millis < 10_000 -> "%.1fs".format(java.util.Locale.ROOT, millis / 1_000.0)
    millis < 60_000 -> "${millis / 1_000}s"
    else -> "${millis / 60_000}m ${(millis / 1_000) % 60}s"
  }
}

private fun instantOrNull(value: String): Instant? =
  try {
    Instant.parse(value)
  } catch (_: java.time.format.DateTimeParseException) {
    null
  }
