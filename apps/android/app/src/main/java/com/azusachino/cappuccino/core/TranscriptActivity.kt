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

// Tool steps after pi-pocket's web/turns.js and web/transcript.js (MIT, docs/licenses/pi-pocket).
// Pi and Claude Code name the same operations and their arguments differently.

enum class ToolKind(val glyph: String) {
  READ("→"),
  EDIT("✎"),
  WRITE("+"),
  COMMAND("$"),
  SEARCH("⌕"),
  LIST("⌕"),
  OTHER("·"),
}

fun toolKind(name: String): ToolKind =
  when (name.lowercase()) {
    "read",
    "view",
    "notebookread" -> ToolKind.READ
    "edit",
    "multiedit",
    "notebookedit",
    "apply_patch" -> ToolKind.EDIT
    "write" -> ToolKind.WRITE
    "bash",
    "shell",
    "exec",
    "run_command" -> ToolKind.COMMAND
    "grep",
    "find",
    "glob",
    "search" -> ToolKind.SEARCH
    "ls" -> ToolKind.LIST
    else -> ToolKind.OTHER
  }

private fun arguments(part: ConversationPart.ToolCall): JsonObject? =
  try {
    parseJsonObject(part.input)
  } catch (_: Exception) {
    null
  }

private fun JsonObject.text(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
  try {
    optionalString(key)?.takeIf { it.isNotBlank() }
  } catch (_: Exception) {
    null
  }
}

private fun JsonObject.number(key: String): Long? =
  try {
    optionalLong(key)
  } catch (_: Exception) {
    null
  }

private fun pathOf(args: JsonObject?): String? = args?.text("path", "file_path", "notebook_path")

/** What a group did, in the order first done: "Thought, searched, read 2 files, ran 1 command". */
fun activitySummary(parts: List<ConversationPart>): String {
  data class Did(val verb: String, val noun: String?, val things: MutableSet<String>)
  val did = LinkedHashMap<String, MutableList<Did>>()
  parts.forEachIndexed { index, part ->
    when (part) {
      is ConversationPart.Thinking -> did.getOrPut("thought") { mutableListOf() }
      is ConversationPart.ToolCall -> {
        val args = arguments(part)
        val (verb, noun, target) =
          when (toolKind(part.name)) {
            ToolKind.READ -> Triple("read", "file", pathOf(args))
            ToolKind.EDIT -> Triple("edited", "file", pathOf(args))
            ToolKind.WRITE -> Triple("wrote", "file", pathOf(args))
            ToolKind.COMMAND -> Triple("ran", "command", null)
            ToolKind.SEARCH -> Triple("searched", null, null)
            ToolKind.LIST -> Triple("listed", "folder", pathOf(args))
            ToolKind.OTHER -> Triple("used ${part.name.lowercase()}", null, null)
          }
        val nouns = did.getOrPut(verb) { mutableListOf() }
        if (noun != null) {
          val entry =
            nouns.firstOrNull { it.noun == noun }
              ?: Did(verb, noun, mutableSetOf()).also { nouns += it }
          // A path names one thing however often it is touched; a command is counted per call.
          entry.things += target ?: "call:$index"
        }
      }
      is ConversationPart.Text,
      is ConversationPart.ResponseStatus -> Unit
    }
  }
  var previousNoun: String? = null
  val pieces = did.map { (verb, nouns) ->
    val counted = nouns.mapIndexed { index, entry ->
      val count = entry.things.size
      // "Edited 1 file, wrote 1": a noun just said is not said again.
      if (index == 0 && entry.noun == previousNoun) "$count"
      else "$count ${entry.noun}${if (count == 1) "" else "s"}"
    }
    previousNoun = nouns.lastOrNull()?.noun
    if (counted.isEmpty()) verb else "$verb ${counted.joinToString(" and ")}"
  }
  return pieces.joinToString(", ").replaceFirstChar { it.uppercase() }
}

/** A step's bold label and muted subject: the tool, and what it touched or ran. */
fun stepWords(part: ConversationPart.ToolCall): Pair<String, String> {
  val args = arguments(part)
  val label = part.name.lowercase()
  val subject =
    when (toolKind(part.name)) {
      ToolKind.READ -> {
        val range =
          args?.number("offset")?.let { offset ->
            ":$offset" + (args.number("limit")?.let { "+$it" } ?: "")
          } ?: ""
        (pathOf(args) ?: "") + range
      }
      ToolKind.EDIT,
      ToolKind.WRITE,
      ToolKind.LIST -> pathOf(args) ?: ""
      ToolKind.COMMAND -> args?.text("command", "cmd") ?: ""
      ToolKind.SEARCH ->
        listOfNotNull(args?.text("pattern", "query"), pathOf(args)).joinToString("  ")
      ToolKind.OTHER -> ""
    }
  return label to subject.lineSequence().firstOrNull().orEmpty()
}

enum class Tone {
  PLAIN,
  OK,
  ERR,
}

data class StepOutcome(val text: String, val tone: Tone = Tone.PLAIN)

private fun lineCount(text: String): Int =
  if (text.isEmpty()) 0 else text.removeSuffix("\n").split('\n').size

private fun plural(count: Int, word: String, many: String = "${word}s") =
  "$count ${if (count == 1) word else many}"

/** Lines added and removed, ignoring lines an edit's old and new text share at either end. */
private fun editCounts(old: String, new: String): Pair<Int, Int> {
  val before = old.split('\n')
  val after = new.split('\n')
  var head = 0
  while (head < before.size && head < after.size && before[head] == after[head]) head++
  var tail = 0
  while (
    tail < before.size - head &&
      tail < after.size - head &&
      before[before.size - 1 - tail] == after[after.size - 1 - tail]
  ) tail++
  return (after.size - head - tail) to (before.size - head - tail)
}

private val TEST_COMMAND =
  Regex(
    "\\b(?:test|tests|spec|jest|vitest|pytest|mocha|rspec|phpunit|ctest|gradlew)\\b",
    RegexOption.IGNORE_CASE,
  )

/** A test runner's own summary ("48 passed · 3.1s"); never guessed for other output. */
fun testOutcome(output: String, command: String): String? {
  if (
    !TEST_COMMAND.containsMatchIn(command) &&
      !Regex("(?m)^(?:# pass|ℹ pass|Tests:) ").containsMatchIn(output)
  )
    return null
  fun count(node: String, word: String, alt: String): String? =
    Regex("(?m)^(?:#|ℹ) $node (\\d+)").find(output)?.groupValues?.get(1)
      ?: Regex("(?m)^\\s*Tests:?\\s+(?:[^\\n]*?\\D)?(\\d+) $word").find(output)?.groupValues?.get(1)
      ?: Regex("\\b(\\d+) (?:$word|$alt)\\b").find(output)?.groupValues?.get(1)
  val passed = count("pass", "passed", "passing") ?: return null
  val failed = count("fail", "failed", "failing")?.toIntOrNull()?.takeIf { it > 0 }
  val seconds =
    Regex("\\b(?:in|Duration|Time:)\\s+([\\d.]+)\\s?s\\b")
      .find(output)
      ?.groupValues
      ?.get(1)
      ?.toDoubleOrNull()
  return listOfNotNull(
      "$passed passed",
      failed?.let { "$it failed" },
      seconds?.let { if (it < 10) "%.1fs".format(java.util.Locale.ROOT, it) else "${it.toInt()}s" },
    )
    .joinToString(" · ")
}

/** A failed call's error, short: an exit code, or the first line that says something. */
fun shortError(output: String?): String {
  val text = output.orEmpty()
  Regex("(?i)(?:exited with code|exit code:?) (\\d+)").find(text)?.let {
    return "exit ${it.groupValues[1]}"
  }
  if (Regex("(?i)timed out").containsMatchIn(text)) return "timed out"
  val line =
    text
      .lineSequence()
      .map { it.trim().replace(Regex("^\\[[\\w -]+\\]\\s*"), "") }
      .firstOrNull { it.isNotEmpty() && !Regex("^</?[\\w-]+>$").matches(it) } ?: return "failed"
  val clean = line.replace(Regex("(?i)^error:\\s*"), "")
  return if (clean.length > 48) clean.take(47) + "…" else clean
}

/** What a call came to, from what it recorded; null when nothing is recorded or worth saying. */
fun stepOutcome(part: ConversationPart.ToolCall): StepOutcome? {
  return when (part.activityState) {
    ToolActivityState.FAILED -> StepOutcome(shortError(part.output), Tone.ERR)
    ToolActivityState.RESULT_NOT_RECORDED -> null
    ToolActivityState.RESULT_AVAILABLE -> {
      val output = part.output.orEmpty()
      val args = arguments(part)
      when (toolKind(part.name)) {
        ToolKind.EDIT -> {
          val edits =
            args
              ?.let { a ->
                val single =
                  a.text("old_string", "oldText")?.let {
                    it to (a.text("new_string", "newText") ?: "")
                  }
                if (single != null) listOf(single)
                else
                  try {
                    a.array("edits").map {
                      (it.text("old_string", "oldText") ?: "") to
                        (it.text("new_string", "newText") ?: "")
                    }
                  } catch (_: Exception) {
                    emptyList()
                  }
              }
              .orEmpty()
          if (edits.isEmpty()) null
          else {
            val (added, removed) =
              edits
                .map { (old, new) -> editCounts(old, new) }
                .reduce { a, b -> (a.first + b.first) to (a.second + b.second) }
            StepOutcome("+$added −$removed", Tone.OK)
          }
        }
        ToolKind.WRITE -> args?.text("content")?.let { StepOutcome("+${lineCount(it)}", Tone.OK) }
        ToolKind.READ ->
          if (output.isBlank()) null else StepOutcome(plural(lineCount(output), "line"))
        ToolKind.SEARCH ->
          when {
            Regex("^(?:No matches|No files found)").containsMatchIn(output) ->
              StepOutcome("no matches")
            output.isBlank() -> null
            else -> StepOutcome(plural(lineCount(output.trim()), "match", "matches"))
          }
        ToolKind.LIST ->
          if (output.isBlank()) StepOutcome("none")
          else StepOutcome(plural(lineCount(output.trim()), "entry", "entries"))
        ToolKind.COMMAND -> {
          testOutcome(output, args?.text("command", "cmd") ?: "")?.let {
            return StepOutcome(it, Tone.OK)
          }
          val lines = lineCount(output.trim())
          StepOutcome(if (lines == 0) "no output" else plural(lines, "line"))
        }
        ToolKind.OTHER -> null
      }
    }
  }
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
