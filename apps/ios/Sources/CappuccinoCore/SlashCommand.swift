import Foundation

/// The agent command a composer message would run, such as `/model` for `/model opus`; nil for
/// ordinary text. A path such as `/Users/me/notes.md` is text: the word must end at a space or the
/// end. Matches the bridge, which refuses an unconfirmed command.
public func slashCommand(_ text: String) -> String? {
  let trimmed = text.drop(while: \.isWhitespace)
  return trimmed.prefixMatch(of: #/\/[A-Za-z][A-Za-z0-9_:-]*(?=\s|$)/#).map { String($0.output) }
}

/// What running `command` costs, for the confirmation shown before it is sent.
public func slashCommandWarning(_ command: String) -> String {
  switch command.lowercased() {
  case "/model":
    return "Changing the model drops the agent's prompt cache: the next turn re-reads the whole "
      + "conversation uncached, which is slower and costs more."
  case "/new", "/clear", "/reset":
    return "This starts a fresh conversation; the current one leaves the agent's context."
  case "/compact":
    return "This replaces the conversation history with a summary."
  case "/exit", "/quit":
    return "This exits the agent. Its terminal session ends."
  default:
    return "\(command) is an agent command, not a message. It runs in the terminal as typed."
  }
}

/// Why a command cannot be sent from the phone at all; nil when it is permitted. Ports
/// `disallowedSlashCommandReason` from core/SlashCommand.kt.
public func disallowedSlashCommandReason(_ command: String) -> String? {
  switch command.lowercased() {
  case "/status":
    return "The /status command opens a full-screen overlay that needs the physical Esc key to "
      + "close, which phones lack. It is disabled to avoid locking the session."
  case "/exit", "/quit":
    return "Exiting the agent is disabled: Cappuccino has no way to restore a terminated session."
  default:
    return nil
  }
}

/// Why a command must wait: `/new` starts a fresh session, so it waits until the agent is idle.
/// Ports `busyAgentCommandReason` from core/SlashCommand.kt.
public func busyAgentCommandReason(_ command: String, agentWorking: Bool) -> String? {
  agentWorking && command.lowercased() == "/new"
    ? "Stop the agent first, then start a new session." : nil
}

/// An agent command the composer offers, with what it does.
public struct AgentSlashCommand: Equatable, Sendable {
  public let command: String
  public let description: String
}

/// The commands the composer menu lists. Ports `COMMON_SLASH_COMMANDS` from core/SlashCommand.kt;
/// refused commands (`/status`, `/exit`, `/quit`) are never listed.
public let commonSlashCommands: [AgentSlashCommand] = [
  AgentSlashCommand(command: "/new", description: "Start a fresh agent session"),
  AgentSlashCommand(command: "/compact", description: "Summarize and shrink conversation history"),
  AgentSlashCommand(command: "/model", description: "Change the active model"),
  AgentSlashCommand(command: "/clear", description: "Reset conversation and clear context"),
]

/// The listed commands a draft starting with `/` could be completing. Ports `matchingSlashCommands`.
public func matchingSlashCommands(_ query: String) -> [AgentSlashCommand] {
  let clean = query.drop(while: \.isWhitespace).lowercased()
  guard clean.hasPrefix("/") else { return [] }
  let token = clean.prefix { $0 != " " }
  return commonSlashCommands.filter { $0.command.hasPrefix(token) }
}

/// Recent prompts, newest first and without repeats, as Android's composer keeps them.
public func rememberingPrompt(_ text: String, in recent: [String], limit: Int = 20) -> [String] {
  let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
  guard !trimmed.isEmpty else { return recent }
  return Array(([trimmed] + recent.filter { $0 != trimmed }).prefix(limit))
}
