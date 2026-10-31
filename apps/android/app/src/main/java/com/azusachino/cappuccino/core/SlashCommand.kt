package com.azusachino.cappuccino.core

/**
 * The agent command a composer message would run, such as `/model` for `/model opus`; null for
 * ordinary text. A path such as `/Users/me/notes.md` is text: the word must end at a space or the
 * end. Matches the bridge, which refuses an unconfirmed command.
 */
fun slashCommand(text: String): String? =
  Regex("""^/[A-Za-z][A-Za-z0-9_:-]*(?=\s|$)""").find(text.trimStart())?.value

/** What running [command] costs, for the confirmation shown before it is sent. */
fun slashCommandWarning(command: String): String =
  when (command.lowercase()) {
    "/model" ->
      "Changing the model drops the agent's prompt cache: the next turn re-reads the whole " +
        "conversation uncached, which is slower and costs more."
    "/new",
    "/clear",
    "/reset" ->
      "This starts a fresh conversation; the current one is preserved in conversation history."
    "/compact" -> "This replaces the conversation history with a summary."
    "/exit",
    "/quit" -> "This exits the agent. Its terminal session ends."
    else -> "$command is an agent command, not a message. It runs in the terminal as typed."
  }

/**
 * If [command] is disallowed on mobile (e.g. because it requires physical keys like Esc to exit),
 * returns the user-facing explanation why it cannot be run; null if permitted.
 */
fun disallowedSlashCommandReason(command: String): String? =
  when (command.lowercase().trim()) {
    "/status" ->
      "The /status command opens an interactive full-screen curses overlay that requires pressing the physical Esc key to quit. Because mobile keyboards lack an Escape key, this command is disabled to prevent locking the session."
    "/exit",
    "/quit" ->
      "Exiting the agent is disabled: Cappuccino has no mechanism to restore terminated agent sessions."
    else -> null
  }

/**
 * Why [text] cannot be sent while the agent is working: `/new` starts a fresh session, so it waits
 * until the agent is idle (pre-redesign rule, kept after New session became a command). Null when
 * the text may be sent.
 */
fun busyAgentCommandReason(text: String, agentWorking: Boolean): String? {
  val command = slashCommand(text)?.lowercase() ?: return null
  return if (agentWorking && command == "/new") "Stop the agent first, then start a new session."
  else null
}

data class AgentSlashCommand(val command: String, val description: String)

val COMMON_SLASH_COMMANDS: List<AgentSlashCommand> =
  listOf(
    AgentSlashCommand("/new", "Start a fresh agent session"),
    AgentSlashCommand("/compact", "Summarize and shrink conversation history"),
    AgentSlashCommand("/model", "Change the active model"),
    AgentSlashCommand("/clear", "Reset conversation and clear context"),
  )

fun matchingSlashCommands(query: String): List<AgentSlashCommand> {
  val clean = query.trimStart().lowercase()
  if (!clean.startsWith("/")) return emptyList()
  val token = clean.substringBefore(" ")
  return COMMON_SLASH_COMMANDS.filter { it.command.startsWith(token) }
}
