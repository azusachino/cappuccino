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
