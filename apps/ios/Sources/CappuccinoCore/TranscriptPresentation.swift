import Foundation

/// Presentation rules for the conversation page, ported from ui/MessageBody.kt, ui/TranscriptRows.kt
/// and the bottom-marker logic of ui/CappuccinoShell.kt.

/// The first six lines, at most 600 characters, of a reply shown inline. Longer replies get "View full message".
public func messagePreview(_ text: String) -> String {
  let lines = text.components(separatedBy: "\n").prefix(6).joined(separator: "\n")
  return String(lines.prefix(600))
}

/// A reply's text kept whole up to 16,000 characters, the transcript budget.
public func transcriptInlineText(_ text: String) -> String {
  String(text.prefix(16_000))
}

/// The status the owner sees live: blocked while a prompt waits, otherwise the stream's
/// status, falling back to the agent's status from the list.
public func liveStatus(
  pendingPrompt: Bool, streamStatus: String?, listedStatus: String?
) -> String {
  if pendingPrompt { return "blocked" }
  return (streamStatus ?? listedStatus ?? "").lowercased()
}

/// Turns that show in the transcript: those with text or parts.
public func chatTurns(_ turns: [ConversationTurn]) -> [ConversationTurn] {
  turns.filter { turn in
    !(turn.text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true)
      || !turn.parts.isEmpty
  }
}

/// Everything after the last person's message is the newest turn: its activity opens by default.
public func newestTurnIDs(_ turns: [ConversationTurn]) -> Set<String> {
  let lastUser = turns.lastIndex { $0.role.lowercased() == "user" }
  let start = lastUser.map { $0 + 1 } ?? 0
  return Set(turns[start...].map(\.id))
}

/// A turn's time as local HH:mm, with the full local date and time for VoiceOver.
public struct LocalTime: Equatable, Sendable {
  public let short: String
  public let full: String
}

public func localTime(_ iso: String?, zone: TimeZone = .current) -> LocalTime? {
  guard let iso, let date = instant(iso) else { return nil }
  let short = DateFormatter()
  short.timeZone = zone
  short.locale = Locale(identifier: "en_US_POSIX")
  short.dateFormat = "HH:mm"
  let full = DateFormatter()
  full.timeZone = zone
  full.locale = Locale.current
  full.dateStyle = .medium
  full.timeStyle = .medium
  return LocalTime(short: short.string(from: date), full: full.string(from: date))
}

/// The distance from the bottom, in points, that still counts as following the output.
public let followSlackPoints: Double = 60

/// Whether the reader is at the newest output. Only the reader's own scrolling changes it.
public struct FollowState: Equatable, Sendable {
  public private(set) var following = true

  public init() {}

  /// Called when the reader scrolls: near the bottom is following, anywhere else is not.
  public mutating func readerMoved(distanceFromBottom: Double) {
    following = distanceFromBottom <= followSlackPoints
  }

  /// A drag stops following at once; the jump button brings the reader back.
  public mutating func dragged() {
    following = false
  }

  public mutating func jumpToLatest() {
    following = true
  }
}

private func instant(_ value: String) -> Date? {
  if let date = try? Date(
    value, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
  {
    return date
  }
  return try? Date(value, strategy: Date.ISO8601FormatStyle())
}
