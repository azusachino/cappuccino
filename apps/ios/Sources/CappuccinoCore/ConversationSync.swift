import Foundation

/// Where the phone's replicated log stands in the bridge's file (contract §7). `after` is the
/// forward position for catch-up, `before` the backward position for backfill. `startReached`
/// means a `before` page reached the start of the file, so there is nothing older to load.
public struct SyncCursor: Equatable, Sendable {
  public let after: String?
  public let before: String?
  public let startReached: Bool
  public let conversationID: String?
}

/// Which request produced a page: no query, `after` (forward) or `before` (backward).
public enum SyncDirection: Equatable, Sendable {
  case newest
  case forward
  case backward
}

/// One sync response. `hasAfter` and `hasBefore` say whether the key was present: an absent token
/// keeps the cursor's old value, while a present null `before` means the start of the file.
public struct SyncPage: Equatable, Sendable {
  public let mode: String
  public let upserts: [ConversationTurn]
  public let afterToken: String?
  public let hasAfter: Bool
  public let beforeToken: String?
  public let hasBefore: Bool
  public let caughtUp: Bool
  public let conversationID: String?
}

/// One sync response: the snapshot the screen shows, and the page the replicated log applies.
public struct ConversationPage: Equatable, Sendable {
  public let snapshot: ConversationSnapshot
  public let sync: SyncPage
}

/// Applies one page to the log and returns the new turns and cursor. A nil cursor means the log
/// is not replicated (scrollback), so the page replaces it and nothing is synced later.
func applySyncPage(
  _ turns: [ConversationTurn], cursor: SyncCursor?, direction: SyncDirection, page: SyncPage
) -> (turns: [ConversationTurn], cursor: SyncCursor?) {
  if page.mode == "scrollback" { return (page.upserts, nil) }
  let sameConversation =
    cursor != nil && (page.conversationID == nil || cursor?.conversationID == page.conversationID)
    && page.mode == "append"
    && direction != .newest
  let merged =
    sameConversation
    ? mergeTurns(turns, page.upserts, prepend: direction == .backward) : page.upserts
  let next = SyncCursor(
    after: page.hasAfter ? page.afterToken : cursor?.after,
    before: page.hasBefore ? page.beforeToken : cursor?.before,
    startReached: page.hasBefore ? page.beforeToken == nil : (cursor?.startReached ?? false),
    conversationID: page.conversationID ?? cursor?.conversationID)
  return (merged, next)
}

/// Replaces turns with the same id in place, and places new ids at the end (forward) or the start
/// (backward) in page order. A held turn is resent complete, so its replacement keeps its position.
func mergeTurns(
  _ turns: [ConversationTurn], _ upserts: [ConversationTurn], prepend: Bool
) -> [ConversationTurn] {
  let incoming = Dictionary(upserts.map { ($0.id, $0) }, uniquingKeysWith: { _, last in last })
  let known = Set(turns.map(\.id))
  let replaced = turns.map { incoming[$0.id] ?? $0 }
  let added = upserts.filter { !known.contains($0.id) }
  return prepend ? added + replaced : replaced + added
}
