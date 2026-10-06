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
                      tool: nil
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
/// are idempotent, seq jumps become a visible gap placeholder (never silent),
/// and a reset clears the stream.
public struct TranscriptHistoryReassembler: Sendable {
  private var seenIDs = Set<String>()

  public init() {}

  public var isEmpty: Bool { seenIDs.isEmpty }

  public mutating func reset() {
    seenIDs.removeAll()
  }

  /// Merges a delivered batch into the ordered history.
  public mutating func merge(_ incoming: [TranscriptEntry], into history: [TranscriptEntry])
    -> [TranscriptEntry]
  {
    var merged = history
    for entry in incoming {
      guard !seenIDs.contains(entry.id) else { continue }
      seenIDs.insert(entry.id)
      if let seq = entry.seq,
        let lastNumbered = merged.last(where: { $0.seq != nil })?.seq,
        seq > lastNumbered + 1
      {
        // Mid-stream seq jump: entries were lost in delivery; a visible gap
        // placeholder precedes the arriving entry. An empty history is the
        // normal mid-stream join and renders no placeholder.
        merged.append(Self.gapPlaceholder(before: seq))
      }
      merged.append(entry)
    }
    return merged
  }

  static func gapPlaceholder(before seq: Int) -> TranscriptEntry {
    TranscriptEntry(
      seq: nil, id: "gap-before-\(seq)", kind: "gap",
      text: "gap: missing entries before \(seq)", branch: nil, complete: true, tool: nil)
  }
}
