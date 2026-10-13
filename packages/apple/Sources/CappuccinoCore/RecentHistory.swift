import Foundation

/// Ports core/RecentHistory.kt: bounds the conversation shown, and decides which
/// content counts as a new answer to follow.

let maximumRecentMessages = 200
let maximumRecentTools = 100

extension ConversationPart {
  /// Tool steps live in the activity group; a failed response is recorded, not chat.
  public var visibleInChat: Bool {
    switch self {
    case .toolCall: false
    case .responseStatus(let status, _): status != "failed"
    case .text, .thinking: true
    }
  }
}

extension ConversationTurn {
  func replacingParts(_ parts: [ConversationPart]) -> ConversationTurn {
    ConversationTurn(id: id, role: role, timestamp: timestamp, text: text, parts: parts)
  }
}

/// The content that auto-follow reacts to: the latest reply or outcome. Tool-only
/// updates, reasoning and trimmed older history do not count as a new answer.
public func conversationFollowContent(_ turns: [ConversationTurn]) -> [ConversationTurn] {
  let contentful = turns.compactMap { turn -> ConversationTurn? in
    let visible = turn.parts.filter { part in
      if case .thinking = part { return false }
      return part.visibleInChat
    }
    let blankText = turn.text?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ?? true
    if blankText && visible.isEmpty { return nil }
    return turn.replacingParts(visible)
  }
  return Array(contentful.suffix(1))
}

public struct RecentConversation: Equatable, Sendable {
  public let turns: [ConversationTurn]
  public let omittedMessages: Int
  public let omittedTools: Int
}

/// The newest messages, with the newest tool steps kept within the tool cap.
public func boundedConversation(_ turns: [ConversationTurn]) -> RecentConversation {
  let messages = recentWindow(turns, limit: maximumRecentMessages)
  let totalTools = turns.reduce(0) { $0 + $1.parts.toolCallCount }
  let windowTools = messages.items.reduce(0) { $0 + $1.parts.toolCallCount }
  var skipTools = max(windowTools - maximumRecentTools, 0)
  let clipped = messages.items.map { turn -> ConversationTurn in
    let parts = turn.parts.filter { part in
      guard case .toolCall = part else { return true }
      if skipTools > 0 {
        skipTools -= 1
        return false
      }
      return true
    }
    return turn.replacingParts(parts)
  }
  return RecentConversation(
    turns: clipped,
    omittedMessages: messages.omitted,
    omittedTools: totalTools - min(windowTools, maximumRecentTools))
}

struct RecentWindow<Item> {
  let items: [Item]
  let omitted: Int
}

func recentWindow<Item>(_ items: [Item], limit: Int) -> RecentWindow<Item> {
  precondition(limit > 0)
  return RecentWindow(items: Array(items.suffix(limit)), omitted: max(items.count - limit, 0))
}

extension Array where Element == ConversationPart {
  fileprivate var toolCallCount: Int {
    reduce(0) { count, part in
      if case .toolCall = part { return count + 1 }
      return count
    }
  }
}
