package com.azusachino.cappuccino.ui

enum class TranscriptRole {
  USER,
  AGENT,
  TOOL,
  STATUS,
}

data class ParsedMessage(
  val role: TranscriptRole,
  val text: String,
  val toolGlyph: String? = null,
  val toolName: String? = null,
  val toolArg: String? = null,
)

object TranscriptParser {
  // Full-width rules agent TUIs draw between turns (Claude Code separators)
  private val RULE_LINE = Regex("^[─━═]{6,}$")

  // The prompt echo: what the user typed, shown back by the agent TUI
  private val USER_LINE = Regex("^[❯>]\\s?")

  // Chrome the TUI repaints every turn — model/cwd line, context meters, mode footers, turn
  // metadata
  private val STATUS_LINE =
    Regex(
      "^(?:\\[[^\\]]*\\]\\s*│|⏵|⣾|█|Context\\s|Usage\\s|[·•]\\s*\\d+\\s*shell|✳|※|working\\s*·|thinking\\b)"
    )

  // Tool lines: ● Read(...), ● Edit(...), ○ Bash(...), ● Command(...), etc.
  private val TOOL_LINE = Regex("^([●○∙•])\\s+([A-Za-z0-9_-]+)(?:\\((.*)\\))?.*$")

  fun parse(text: String): List<ParsedMessage> {
    val messages = mutableListOf<ParsedMessage>()
    val lines = text.lines()

    var pendingRole: TranscriptRole? = null
    val pendingLines = mutableListOf<String>()

    fun flush() {
      val role = pendingRole ?: return
      if (role == TranscriptRole.AGENT) {
        val chunk = mutableListOf<String>()
        fun emitChunk() {
          val body = chunk.joinToString("\n").trim()
          if (body.isNotEmpty()) {
            messages += ParsedMessage(TranscriptRole.AGENT, body)
          }
          chunk.clear()
        }
        for (line in pendingLines) {
          if (line.trim().isEmpty()) {
            emitChunk()
          } else {
            chunk += line
          }
        }
        emitChunk()
      } else {
        val body = pendingLines.joinToString("\n").trim()
        if (body.isNotEmpty()) {
          messages += ParsedMessage(role, body)
        }
      }
      pendingRole = null
      pendingLines.clear()
    }

    for (rawLine in lines) {
      val line = rawLine.trimEnd()
      val trimmed = line.trim()

      if (RULE_LINE.matches(trimmed)) {
        flush()
        continue
      }

      if (USER_LINE.containsMatchIn(trimmed)) {
        flush()
        val prompt = trimmed.replace(USER_LINE, "").trim()
        if (prompt.isNotEmpty()) {
          messages += ParsedMessage(TranscriptRole.USER, prompt)
        }
        continue
      }

      val toolMatch = TOOL_LINE.matchEntire(trimmed)
      if (toolMatch != null) {
        flush()
        val glyph = toolMatch.groupValues[1]
        val toolName = toolMatch.groupValues[2]
        val toolArg = toolMatch.groupValues.getOrNull(3)
        messages +=
          ParsedMessage(
            role = TranscriptRole.TOOL,
            text = trimmed,
            toolGlyph = glyph,
            toolName = toolName,
            toolArg = toolArg,
          )
        continue
      }

      val role =
        if (STATUS_LINE.containsMatchIn(trimmed)) {
          TranscriptRole.STATUS
        } else {
          TranscriptRole.AGENT
        }

      if (pendingRole == role) {
        pendingLines += line
      } else {
        flush()
        pendingRole = role
        pendingLines += line
      }
    }
    flush()

    return messages
  }

  fun parseLine(line: String): ParsedMessage {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || RULE_LINE.matches(trimmed)) {
      return ParsedMessage(TranscriptRole.STATUS, "")
    }
    if (USER_LINE.containsMatchIn(trimmed)) {
      val prompt = trimmed.replace(USER_LINE, "").trim()
      return ParsedMessage(TranscriptRole.USER, if (prompt.isEmpty()) trimmed else prompt)
    }
    val toolMatch = TOOL_LINE.matchEntire(trimmed)
    if (toolMatch != null) {
      return ParsedMessage(
        role = TranscriptRole.TOOL,
        text = trimmed,
        toolGlyph = toolMatch.groupValues[1],
        toolName = toolMatch.groupValues[2],
        toolArg = toolMatch.groupValues.getOrNull(3),
      )
    }
    if (STATUS_LINE.containsMatchIn(trimmed)) {
      return ParsedMessage(TranscriptRole.STATUS, trimmed)
    }
    return ParsedMessage(TranscriptRole.AGENT, line)
  }
}
