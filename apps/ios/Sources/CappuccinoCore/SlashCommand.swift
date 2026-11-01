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
