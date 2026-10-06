// Transcript view model lives in Core: logic is unit-testable without SwiftUI.

import Foundation

@MainActor
public final class TranscriptModel: ObservableObject {
  public enum Phase: Equatable {
    case loading
    case streaming
    case failed(message: String)
  }

  @Published public private(set) var entries: [TranscriptEntry] = []
  @Published public private(set) var phase: Phase = .loading
  @Published public private(set) var branch: String?
  @Published public private(set) var sessionID: String

  public let session: String
  public let machineURL: URL
  public let streaming: TranscriptStreaming
  private var reassembler = TranscriptHistoryReassembler()
  private var streamTask: Task<Void, Never>?
  private var generation = 0
  private var stopped = false

  public init(session: String, machineURL: URL, streaming: TranscriptStreaming, branch: String?) {
    self.session = session
    self.machineURL = machineURL
    self.streaming = streaming
    self.branch = branch
    self.sessionID = session
  }

  public func start() {
    stopped = false
    guard streamTask == nil else { return }
    phase = .loading
    streamTask = Task { [weak self] in
      await self?.runStream()
    }
  }

  /// Deterministic teardown: cancelling the stream task closes the WebSocket
  /// via BridgeStream's synchronous closeImmediately path. No leaked socket.
  public func stop() {
    stopped = true
    streamTask?.cancel()
    streamTask = nil
  }

  /// App lifecycle: foreground-only streaming per the architecture.
  public func sceneBecameInactive() {
    stop()
    phase = .failed(message: "Stream paused while the app is in the background.")
  }

  public func sceneBecameActive() {
    start()
  }

  private func runStream() async {
    do {
      let stream = streaming.transcriptStream(
        baseURL: machineURL, session: session, branch: branch)
      for try await event in stream {
        apply(event)
      }
      // stop() raced a clean end: intentional teardown stays silent.
      guard !stopped else { return }
      phase = .failed(message: "The bridge closed the stream.")
    } catch is CancellationError {
      // Deterministic teardown; no visible failure.
    } catch let error as DaemonClientError {
      phase = .failed(message: error.message)
    } catch {
      phase = .failed(message: error.localizedDescription)
    }
  }

  private func apply(_ event: TranscriptStreamEvent) {
    switch event {
    case .open:
      phase = .streaming
    case .entries(let incoming):
      // O(new) appends: the merge touches only delivered entries, never the
      // whole history.
      entries = reassembler.merge(incoming, into: entries)
    case .reset(let newGeneration):
      guard newGeneration != generation else { return }
      generation = newGeneration
      entries = []
      reassembler.reset()
    }
  }
}
