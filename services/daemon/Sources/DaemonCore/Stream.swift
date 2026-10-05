import Foundation

// Pure logic that turns successive pane reads into confirmed-complete entries.
//
// The pi TUI repaints constantly: status header/footer lines (token counts,
// rates, timestamps) mutate in place every poll, and scrollback can compact.
// Positional diffs cannot survive that, so alignment is by LONGEST COMMON
// SUBSEQUENCE between the previous and current snapshots. Lines of the current
// snapshot that the LCS leaves unmatched AFTER the last matched line are
// candidates: genuinely appended content, or churn that happened to land at
// the tail.
//
// Candidates become entries only when they are STABLE — seen in two
// consecutive polls — or immediately when the agent is idle (quiescence).
// Volatile footer churn changes every poll and therefore never stabilizes
// while the agent works; real transcript lines persist and are emitted within
// one poll. A line whose content id is already in the ring is never emitted
// again, which keeps repeated blank/separator lines from spamming history.

public struct PaneSnapshot: Sendable, Equatable {
  public var lines: [String]
  public var working: Bool

  public init(lines: [String], working: Bool) {
    self.lines = lines
    self.working = working
  }
}

public enum LineDiff {
  /// Maximum window used for the LCS alignment; `pane.read` is capped upstream
  /// but this bounds the quadratic DP regardless.
  static let window = 512

  /// Indices of `current` lines matched by the LCS with `previous`, plus the
  /// index in `current` of the last match. Returns nil only when both sides
  /// are empty of any common ground.
  public static func align(previous: [String], current: [String])
    -> (matched: Set<Int>, lastMatch: Int)
  {
    let prev = previous.suffix(window)
    let cur = current.suffix(window)
    let offset = current.count - cur.count
    let n = prev.count
    let m = cur.count
    var matched = Set<Int>()
    guard n > 0, m > 0 else { return (matched, -1) }
    // dp[i][j] = LCS length of prev[i...] and cur[j...]
    var dp = [[Int]](repeating: [Int](repeating: 0, count: m + 1), count: n + 1)
    for i in stride(from: n - 1, through: 0, by: -1) {
      for j in stride(from: m - 1, through: 0, by: -1) {
        if prev[i] == cur[j] {
          dp[i][j] = dp[i + 1][j + 1] + 1
        } else {
          dp[i][j] = max(dp[i + 1][j], dp[i][j + 1])
        }
      }
    }
    var i = 0
    var j = 0
    var lastMatch = -1
    while i < n, j < m {
      if prev[i] == cur[j] {
        matched.insert(j + offset)
        lastMatch = j + offset
        i += 1
        j += 1
      } else if dp[i + 1][j] >= dp[i][j + 1] {
        i += 1
      } else {
        j += 1
      }
    }
    return (matched, lastMatch)
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
  private var seenIds = Set<String>()
  private var storedLines: [String] = []
  /// Content ids seen as candidates in the previous poll; a candidate that is
  /// still present one poll later (matched or still unmatched) is stable
  /// content. Volatile footer churn changes every poll and never stabilizes
  /// while the agent works.
  private var previousCandidateIds: Set<String> = []
  private var nextSeq = 1
  private var lastBranch: String?
  /// Bumped when the active branch changes: clients must reset (no abandoned
  /// branch is ever concatenated into history).
  private(set) public var generation = 0

  public init() {}

  public func ingest(
    snapshot: PaneSnapshot, branch: String?, confirmTrailing: Bool, trace: String = ""
  ) -> [TranscriptEntry] {
    lock.lock()
    defer { lock.unlock() }
    if let branch, branch != lastBranch, lastBranch != nil {
      generation += 1
      entries = []
      seenIds = []
      storedLines = []
      previousCandidateIds = []
      Trace.log("\(trace): branch changed to \(branch) -> reset, generation \(generation)")
    }
    lastBranch = branch

    let (matched, lastMatch) = LineDiff.align(previous: storedLines, current: snapshot.lines)

    // Candidates: current lines the LCS left unmatched. Genuinely appended
    // content is unmatched (the previous snapshot had no such line); header
    // and footer churn is unmatched too, but its content changes every poll.
    // Candidates that are still present one poll later (as unmatched lines or
    // newly matched into the stable region) are real content; candidates that
    // vanish were churn. Quiescence (agent idle) confirms fresh candidates
    // immediately instead of waiting a poll.
    var candidateIds = Set<String>()
    var currentIds = Set<String>()
    for text in snapshot.lines {
      let id = EntryIdentity.id(branch: branch, text: text)
      currentIds.insert(id)
    }
    for (index, text) in snapshot.lines.enumerated() where !matched.contains(index) {
      candidateIds.insert(EntryIdentity.id(branch: branch, text: text))
    }

    var stableIds = currentIds.intersection(previousCandidateIds)
    if confirmTrailing { stableIds.formUnion(candidateIds) }
    // A pure compaction (everything matched, nothing new) must not wipe the
    // pending candidate set: lines that arrived just before the compaction
    // still need their confirming poll.
    if !candidateIds.isEmpty || snapshot.lines.count >= storedLines.count {
      previousCandidateIds = candidateIds
    }

    var newEntries: [TranscriptEntry] = []
    for text in snapshot.lines {
      let id = EntryIdentity.id(branch: branch, text: text)
      guard !seenIds.contains(id), stableIds.contains(id) else { continue }
      seenIds.insert(id)
      let entry = TranscriptEntry(
        seq: nextSeq, id: id, kind: "output", text: text, branch: branch, complete: true)
      nextSeq += 1
      newEntries.append(entry)
    }
    entries = Reconciler.append(entries, newEntries)
    Trace.log(
      "\(trace): cur=\(snapshot.lines.count) lastMatch=\(lastMatch) " +
        "candidates=\(candidateIds.count) stable=\(stableIds.count) " +
        "emitted=\(newEntries.count) working=\(snapshot.working)")
    storedLines = snapshot.lines
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
