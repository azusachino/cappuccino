import Foundation

// Pure logic that turns successive pane reads into confirmed-complete entries.
//
// Rule (documented wire behavior): only the trailing line of a poll can still
// be growing. A line becomes a durable entry when a later line follows it, or
// when two consecutive polls return the same trailing line while the pane is
// not working (idle) — the agent has quiesced. This is what keeps a partial
// trailing line from ever being rendered complete.

public struct PaneSnapshot: Sendable, Equatable {
  public var lines: [String]
  public var working: Bool

  public init(lines: [String], working: Bool) {
    self.lines = lines
    self.working = working
  }
}

public enum LineConfirmer {
  /// Returns confirmed entries (in order) given the previous confirmed line
  /// count and the new snapshot. `confirmTrailing` is true when the caller has
  /// decided the trailing line is final (quiescence). Pure and deterministic.
  public static func confirmed(
    previousCount: Int,
    snapshot: PaneSnapshot,
    branch: String?,
    confirmTrailing: Bool
  ) -> (entries: [TranscriptEntry], newCount: Int) {
    var out: [TranscriptEntry] = []
    var seq = previousCount
    let lines = snapshot.lines
    let confirmedCount = confirmTrailing ? lines.count : max(0, lines.count - 1)
    for (index, text) in lines.prefix(confirmedCount).enumerated() where index >= previousCount {
      seq = index + 1
      out.append(
        TranscriptEntry(
          seq: seq,
          id: EntryIdentity.id(branch: branch, text: text),
          kind: "output",
          text: text,
          branch: branch,
          complete: true
        ))
    }
    return (out, confirmedCount)
  }
}

// Reconciliation over an entry stream, implementing the behavior-spec rules the
// fixtures encode: duplicates are idempotent, gaps become visible placeholders,
// and session replacement resets the stream.

public enum Reconciler {
  /// Appends entries, inserting visible gap placeholders. `hasPriorHistory`
  /// marks a client that lost its retained prefix (the daemon then replays from
  /// the oldest entry it still holds); an empty client stream simply starts at
  /// the daemon's first entry with no placeholder.
  public static func append(
    _ existing: [TranscriptEntry],
    _ incoming: [TranscriptEntry],
    hasPriorHistory: Bool = false
  ) -> [TranscriptEntry] {
    var result = existing
    var seen = Set(result.map { $0.seq })
    var openedWithGap = false
    if result.isEmpty, hasPriorHistory, let first = incoming.first, first.seq > 1 {
      result.append(
        TranscriptEntry(
          seq: -1, id: EntryIdentity.id(branch: nil, text: "...gap..."), kind: "gap",
          text: "gap: no retained entries before \(first.seq)", branch: nil, complete: true))
      openedWithGap = true
    }
    for (index, entry) in incoming.enumerated() {
      guard !seen.contains(entry.seq) else { continue }
      let openingPlaceholder = openedWithGap && index == 0
      if let last = result.last, !openingPlaceholder, entry.seq > last.seq + 1 {
        result.append(
          TranscriptEntry(
            seq: -1, id: EntryIdentity.id(branch: nil, text: "…gap…"), kind: "gap",
            text: "gap: missing entries \(last.seq + 1)–\(entry.seq - 1)", branch: nil, complete: true))
      }
      result.append(entry)
      seen.insert(entry.seq)
    }
    return result
  }
}

// The per-session live stream ring the daemon serves from. Lock-guarded so the
// poll task and client connections can share it.

public final class StreamRing: @unchecked Sendable {
  private let lock = NSLock()
  private var entries: [TranscriptEntry] = []
  private var confirmedCount = 0
  private var lastBranch: String?
  /// Bumped when the active branch changes: clients must reset (no abandoned
  /// branch is ever concatenated into history).
  private(set) public var generation = 0

  public init() {}

  public func ingest(snapshot: PaneSnapshot, branch: String?, confirmTrailing: Bool)
    -> [TranscriptEntry]
  {
    lock.lock()
    defer { lock.unlock() }
    if let branch, branch != lastBranch, lastBranch != nil {
      generation += 1
      entries = []
      confirmedCount = 0
    }
    lastBranch = branch
    let (newEntries, count) = LineConfirmer.confirmed(
      previousCount: confirmedCount, snapshot: snapshot, branch: branch,
      confirmTrailing: confirmTrailing)
    confirmedCount = count
    let merged = Reconciler.append(entries, newEntries)
    entries = merged
    return newEntries
  }

  /// Entries strictly after `afterSeq`, or a gap marker followed by the oldest
  /// retained entry when `afterSeq` predates the ring.
  public func replay(after afterSeq: Int) -> (entries: [TranscriptEntry], generation: Int) {
    lock.lock()
    defer { lock.unlock() }
    let older = entries.filter { $0.seq <= afterSeq }
    var out: [TranscriptEntry] = []
    if let first = entries.first, first.seq <= afterSeq, older.isEmpty {
      out.append(
        TranscriptEntry(
          seq: -1, id: EntryIdentity.id(branch: nil, text: "…gap…"), kind: "gap",
          text: "gap: entries ≤ \(afterSeq) no longer retained", branch: nil, complete: true))
    }
    out.append(contentsOf: entries.filter { $0.seq > afterSeq })
    return (out, generation)
  }

  public var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return entries.count
  }
}
