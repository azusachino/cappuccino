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
    "/reset" -> "This starts a fresh conversation; the current one leaves the agent's context."
    "/compact" -> "This replaces the conversation history with a summary."
    "/exit",
    "/quit" -> "This exits the agent. Its terminal session ends."
    else -> "$command is an agent command, not a message. It runs in the terminal as typed."
  }

data class AgentSlashCommand(val command: String, val description: String)

val COMMON_SLASH_COMMANDS: List<AgentSlashCommand> =
  listOf(
    AgentSlashCommand("/compact", "Summarize and shrink conversation history"),
    AgentSlashCommand("/model", "Change the active model"),
    AgentSlashCommand("/clear", "Reset conversation and clear context"),
    AgentSlashCommand("/new", "Start a fresh agent session"),
    AgentSlashCommand("/status", "Display agent and session stats"),
    AgentSlashCommand("/help", "List available tools and commands"),
    AgentSlashCommand("/exit", "Exit the agent terminal session"),
  )

fun matchingSlashCommands(query: String): List<AgentSlashCommand> {
  val clean = query.trimStart().lowercase()
  if (!clean.startsWith("/")) return emptyList()
  val token = clean.substringBefore(" ")
  return COMMON_SLASH_COMMANDS.filter { it.command.startsWith(token) }
}
