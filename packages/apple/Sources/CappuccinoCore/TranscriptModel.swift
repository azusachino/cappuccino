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
  /// Durable reload probe run on session reset (bridge /api/transcript).
  /// When it throws, the reset keeps the previous history and surfaces the
  /// banner — never a silent clear.
  public let durableReload: (@Sendable (URL, String) async throws -> Void)?

  private var streamTask: Task<Void, Never>?
  private var generation = 0
  private var stopped = false

  public init(
    session: String, machineURL: URL, streaming: TranscriptStreaming, branch: String?,
    durableReload: (@Sendable (URL, String) async throws -> Void)? = nil
  ) {
    self.session = session
    self.machineURL = machineURL
    self.streaming = streaming
    self.branch = branch
    self.durableReload = durableReload
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
    print("DBG-MODEL apply:", event)
    switch event {
    case .open:
      phase = .streaming
    case .entries(let incoming):
      // O(new) amortized appends; the merge never copies the whole history.
      reassembler.merge(incoming)
      entries = reassembler.entries
    case .reset(let newGeneration):
      guard newGeneration != generation else { return }
      generation = newGeneration
      entries = []
      reassembler.reset()
      // Durable reload then a fresh stream: failed reload keeps history and
      // the banner (runStream is restarted by the caller below).
      Task { [weak self] in
        await self?.durableReloadThenRestart()
      }
    }
  }

  private func durableReloadThenRestart() async {
    let previous = entries
    do {
      if let durableReload {
        try await durableReload(machineURL, session)
      }
      stop()
      start()
    } catch {
      // Failed durable reload: keep the history, show the banner.
      entries = previous
      phase = .failed(message: "Durable transcript reload failed; history kept.")
    }
  }
}
