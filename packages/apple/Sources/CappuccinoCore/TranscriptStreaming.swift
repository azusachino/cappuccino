import Foundation

/// Transcript streaming surface for the transcript UI. The real bridge
/// client adapts the WebSocket wire; the demo client scripts the fixture
/// journey for hermetic UI tests.
public protocol TranscriptStreaming: Sendable {
  /// Yields open, entry batches, resets and gap placeholders for one agent.
  func transcriptStream(
    baseURL: URL, session: String, branch: String?
  ) -> AsyncThrowingStream<TranscriptStreamEvent, Error>
}

public enum TranscriptStreamEvent: Equatable, Sendable {
  case open(generation: Int)
  case entries([TranscriptEntry])
  case reset(generation: Int)
}

extension BridgeClient: TranscriptStreaming {
  /// Adapts the WebSocket wire (BridgeStreamOutput + BridgeTranscriptEntry)
  /// into transcript domain events. The bridge already reconciles server-side;
  /// the view model re-applies the contract client-side (dedupe, gaps).
  public func transcriptStream(
    baseURL: URL, session: String, branch: String?
  ) -> AsyncThrowingStream<TranscriptStreamEvent, Error> {
    let wireStream = stream(base: baseURL, session: session)
    return AsyncThrowingStream { continuation in
      let task = Task {
        do {
          for try await output in wireStream {
            switch output {
            case .open(let generation):
              continuation.yield(.open(generation: generation))
            case .reset(let generation):
              continuation.yield(.reset(generation: generation))
            case .entries(let wireEntries):
              continuation.yield(
                .entries(
                  wireEntries.map { wire in
                    TranscriptEntry(
                      seq: wire.seq,
                      id: wire.id,
                      kind: wire.kind,
                      text: wire.text,
                      branch: branch,
                      complete: wire.complete,
                      tool: wire.tool.map { tool in
                        TranscriptToolCall(
                          name: tool.name, args: tool.args ?? [:], detail: tool.detail)
                      }
                    )
                  }))
            }
          }
        } catch {
          continuation.finish(throwing: error)
        }
      }
      continuation.onTermination = { _ in
        task.cancel()
      }
    }
  }
}

/// Client-side reconciliation mirroring the reference contract (behavior spec
/// v0, history section) and the reconciliation-cases fixture: duplicate ids
/// are idempotent, entries insert in stable seq order (out-of-order arrivals
/// backfill by seq), seq jumps become a visible gap placeholder (never
/// silent), and a reset clears the stream.
///
/// In-order appends are O(1) at the reassembler level; publishing `entries`
/// through the model may trigger Swift Array copy-on-write. Backfills scan and
/// shift O(n) entries and are expected to be rare.
public struct TranscriptHistoryReassembler: Sendable {
  private(set) public var entries: [TranscriptEntry] = []
  private(set) public var lastSeq: Int?
  private var seenIDs = Set<String>()
  private var gapMarkers = Set<Int>()

  public init() {}

  public mutating func reset() {
    entries = []
    lastSeq = nil
    seenIDs.removeAll()
    gapMarkers.removeAll()
  }

  /// Merges a delivered batch; returns only the entries added now (O(new)).
  @discardableResult
  public mutating func merge(_ incoming: [TranscriptEntry]) -> [TranscriptEntry] {
    var added: [TranscriptEntry] = []
    for entry in incoming {
      guard !seenIDs.contains(entry.id) else { continue }
      seenIDs.insert(entry.id)
      if let seq = entry.seq {
        if let last = lastSeq, seq > last + 1, !gapMarkers.contains(seq) {
          // Mid-stream seq jump: entries were lost in delivery; a visible
          // gap placeholder precedes the arriving entry. An empty history is
          // the normal mid-stream join and renders no placeholder.
          let gap = Self.gapPlaceholder(before: seq)
          gapMarkers.insert(seq)
          entries.append(gap)
          added.append(gap)
        }
        if let last = lastSeq, seq <= last {
          // Out-of-order backfill: insert at the seq-sorted position.
          let position = Self.insertionPosition(of: seq, in: entries)
          entries.insert(entry, at: position)
          added.append(entry)
          continue
        }
        entries.append(entry)
        added.append(entry)
        lastSeq = seq
      } else {
        entries.append(entry)
        added.append(entry)
      }
    }
    return added
  }

  /// Last position whose numbered seq is below `seq` (skips gap markers).
  static func insertionPosition(of seq: Int, in entries: [TranscriptEntry]) -> Int {
    var position = 0
    for (index, entry) in entries.enumerated() {
      if let entrySeq = entry.seq, entrySeq < seq { position = index + 1 }
    }
    return position
  }

  static func gapPlaceholder(before seq: Int) -> TranscriptEntry {
    TranscriptEntry(
      seq: nil, id: "gap-before-\(seq)", kind: "gap",
      text: "gap: missing entries before \(seq)", branch: nil, complete: true, tool: nil)
  }
}
