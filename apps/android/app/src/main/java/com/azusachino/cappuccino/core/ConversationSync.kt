package com.azusachino.cappuccino.core

/**
 * Where the phone's replicated log stands in the bridge's file (contract §7). `after` is the
 * forward position for catch-up, `before` the backward position for backfill. `startReached` means
 * a `before` page reached the start of the file, so there is nothing older to load.
 */
data class SyncCursor(
  val after: String?,
  val before: String?,
  val startReached: Boolean,
  val conversationId: String?,
)

/** Which request produced a page: no query, `after` (forward) or `before` (backward). */
enum class SyncDirection {
  Newest,
  Forward,
  Backward,
}

/**
 * One sync response. `hasAfter` and `hasBefore` say whether the key was present: an absent token
 * keeps the cursor's old value, while a present null `before` means the start of the file.
 */
data class SyncPage(
  val mode: String,
  val upserts: List<ConversationTurn>,
  val afterToken: String?,
  val hasAfter: Boolean,
  val beforeToken: String?,
  val hasBefore: Boolean,
  val caughtUp: Boolean,
  val conversationId: String?,
)

/**
 * Applies one page to the log and returns the new turns and cursor. A null cursor means the log is
 * not replicated (scrollback), so the page replaces it and nothing is synced later.
 */
fun applySyncPage(
  turns: List<ConversationTurn>,
  cursor: SyncCursor?,
  direction: SyncDirection,
  page: SyncPage,
): Pair<List<ConversationTurn>, SyncCursor?> {
  if (page.mode == "scrollback") return page.upserts to null
  val sameConversation =
    cursor != null &&
      cursor.conversationId == page.conversationId &&
      page.mode == "append" &&
      direction != SyncDirection.Newest
  val merged =
    if (!sameConversation) page.upserts
    else mergeTurns(turns, page.upserts, prepend = direction == SyncDirection.Backward)
  val next =
    SyncCursor(
      after = if (page.hasAfter) page.afterToken else cursor?.after,
      before = if (page.hasBefore) page.beforeToken else cursor?.before,
      startReached =
        if (page.hasBefore) page.beforeToken == null else cursor?.startReached ?: false,
      conversationId = page.conversationId ?: cursor?.conversationId,
    )
  return merged to next
}

/**
 * Replaces turns with the same id in place, and places new ids at the end (forward) or the start
 * (backward) in page order. A held turn is resent complete, so its replacement keeps its position.
 */
internal fun mergeTurns(
  turns: List<ConversationTurn>,
  upserts: List<ConversationTurn>,
  prepend: Boolean,
): List<ConversationTurn> {
  val incoming = upserts.associateBy { it.id }
  val known = turns.mapTo(HashSet()) { it.id }
  val replaced = turns.map { incoming[it.id] ?: it }
  val added = upserts.filter { it.id !in known }
  return if (prepend) added + replaced else replaced + added
}
