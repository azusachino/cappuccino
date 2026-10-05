import Foundation

// Pure logic that turns successive pane reads into confirmed-complete entries.
//
// Rule (documented wire behavior): only the trailing line of a poll can still
// be growing. A line becomes a durable entry when a later line follows it, or
// when the poll happens while the agent is idle (quiescence). This is what
// keeps a partial trailing line from ever being rendered complete.
//
// Pane reads are diffed by CONTENT, never by absolute row index: the pi TUI
// repaints and compacts its scrollback, so the same text can move between rows
// and the visible line count can shrink between polls. The daemon finds the
// longest overlap between the previous snapshot's suffix and the current
// snapshot's prefix and emits only the truly new lines after that overlap.

public struct PaneSnapshot: Sendable, Equatable {
  public var lines: [String]
  public var working: Bool

  public init(lines: [String], working: Bool) {
    self.lines = lines
    self.working = working
  }
}

public enum LineDiff {
  /// Returns the lines appended after the previous snapshot, or nil when there
  /// is no recognizable continuity (full repaint/clear): the caller must then
  /// re-baseline without emitting anything, or reset on a branch change.
  public static func appendedLines(previous: [String], current: [String]) -> [String]? {
    if previous.isEmpty {
      return current
    }
    if current.count >= previous.count,
      Array(current.prefix(previous.count)) == previous
    {
      return Array(current.dropFirst(previous.count))
    }
    // Pane compacted or repainted: find the largest k where the previous
    // snapshot's suffix of length k equals the current snapshot's prefix.
    let maxOverlap = min(previous.count, current.count)
    for k in stride(from: maxOverlap, through: 1, by: -1) {
      if Array(previous.suffix(k)) == Array(current.prefix(k)) {
        return Array(current.dropFirst(k))
      }
    }
    return nil
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
            seq: -1, id: EntryIdentity.id(branch: nil, text: "...gap..."), kind: "gap",
            text: "gap: missing entries \(last.seq + 1)-\(entry.seq - 1)", branch: nil,
            complete: true))
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
  private var storedLines: [String] = []
  /// A trailing line held back because the agent was working when it appeared;
  /// confirmed on the next idle poll (quiescence).
  private var heldTrailing: String?
  private var nextSeq = 1
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
      storedLines = []
      heldTrailing = nil
    }
    lastBranch = branch

    guard let appended = LineDiff.appendedLines(
      previous: storedLines, current: snapshot.lines)
    else {
      // Unrecognizable repaint: re-baseline quietly rather than fabricate
      // entries; the next poll re-establishes continuity.
      storedLines = snapshot.lines
      heldTrailing = nil
      return []
    }

    var pending = appended
    if confirmTrailing {
      // Idle: a previously held trailing line is now confirmed too, as long as
      // it is still the line right before the appended content.
      if let held = heldTrailing, storedLines.last == held {
        pending.insert(held, at: 0)
      }
      heldTrailing = nil
    } else if let last = appended.last {
      // Working: hold the trailing line back until a later line or idleness
      // confirms it.
      heldTrailing = last
      pending = Array(appended.dropLast())
    }
    storedLines = snapshot.lines

    let newEntries: [TranscriptEntry] = pending.map { text in
      let entry = TranscriptEntry(
        seq: nextSeq, id: EntryIdentity.id(branch: branch, text: text), kind: "output",
        text: text, branch: branch, complete: true)
      nextSeq += 1
      return entry
    }
    entries = Reconciler.append(entries, newEntries)
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
          seq: -1, id: EntryIdentity.id(branch: nil, text: "...gap..."), kind: "gap",
          text: "gap: entries <= \(afterSeq) no longer retained", branch: nil, complete: true))
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
