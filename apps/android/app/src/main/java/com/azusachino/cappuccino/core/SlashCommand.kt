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
