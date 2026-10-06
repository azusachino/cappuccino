import Foundation

// The surface the Machines UI consumes; real daemon client and the scripted
// demo (UI tests, previews) both conform.

/// Transport surface the Machines UI consumes. No credentials: per the owner
/// decision the bridge has no auth — the tailnet/loopback boundary is the
/// security model, and the machine is identified by its base URL.
public protocol DaemonServing: Sendable {
  /// Reachability + identity probe for the machine at `baseURL`.
  func pair(baseURL: URL) async throws -> PairedMachine
  /// Agent rows for the machine at `baseURL`.
  func listAgents(baseURL: URL) async throws -> [AgentRow]
}

/// Scripted client for UI tests and previews: token "demo-ok" pairs, anything
/// else fails visibly; the agent list mirrors fixtures/agents-list.json plus
/// the unnamed-pane and null-branch cases the fixture set is extended by.
public struct DemoDaemonClient: DaemonServing {
  public init() {}

  public func pair(baseURL: URL) async throws -> PairedMachine {
    try await Task.sleep(nanoseconds: 150_000_000)
    return PairedMachine(machineID: "11111111-1111-4111-8111-111111111111")
  }

  public func listAgents(baseURL: URL) async throws -> [AgentRow] {
    try await Task.sleep(nanoseconds: 150_000_000)
    return [
      AgentRow(
        machineID: "11111111-1111-4111-8111-111111111111",
        sessionID: "s-aurora", label: "pi on harus-mini",
        branch: "feat/collector-fix", working: true),
      AgentRow(
        machineID: "11111111-1111-4111-8111-111111111111",
        sessionID: "s-borealis", label: "pi on harus-mini",
        branch: "main", working: false),
      AgentRow(
        machineID: "11111111-1111-4111-8111-111111111111",
        sessionID: "w1:p43", label: "pi",
        branch: nil, working: false),
    ]
  }
}

/// Always-unreachable transport for the failure-state UI journey.
public struct UnreachableClient: DaemonServing {
  public init() {}

  public func pair(baseURL: URL) async throws -> PairedMachine {
    throw DaemonClientError.unreachable("connection refused (test transport)")
  }

  public func listAgents(baseURL: URL) async throws -> [AgentRow] {
    throw DaemonClientError.unreachable("connection refused (test transport)")
  }
}

// MARK: - Transcript demo (UI tests/previews): scripted fixture journeys.

private enum DemoTranscriptPayload {
  static let standard = #"""
    {"session_id":"s-aurora","active_branch":"feat/collector-fix","entries":[
      {"seq":1,"kind":"user","text":"Fix the collector path and rerun lint."},
      {"seq":2,"kind":"assistant","text":"Checked `Makefile`; the collector path pointed at the debug variant.\n\n```\nANDROID_TEST_OUTPUT := debugAndroidTest/connected\n```","tool":{"name":"edit","args":{"path":"Makefile"},"detail":"+ANDROID_TEST_OUTPUT := .../debugAndroidTest/connected"}},
      {"seq":3,"kind":"assistant","text":"Reran the focused gate; export checks pass.","tool":{"name":"bash","args":{"command":"make ui-test-android"},"detail":"exit 0; 3 PNGs verified"}}
    ]}
    """#

  /// 1k generated entries for the scroll smoke (alternating chat lines and a
  /// tool row every tenth entry, deterministic text).
  static let long: String = {
    var entries: [String] = []
    for index in 1...1000 {
      if index.isMultiple(of: 10) {
        entries.append(
          "{\"seq\":\(index),\"kind\":\"assistant\",\"text\":\"Working on step \(index).\",\"tool\":{\"name\":\"bash\",\"args\":{\"command\":\"step \(index)\"},\"detail\":\"ok\"}}"
        )
      } else if index.isMultiple(of: 2) {
        entries.append(
          "{\"seq\":\(index),\"kind\":\"assistant\",\"text\":\"Working on step \(index).\"}"
        )
      } else {
        entries.append(
          "{\"seq\":\(index),\"kind\":\"user\",\"text\":\"Continue with step \(index).\"}"
        )
      }
    }
    return
      "{\"session_id\":\"s-long\",\"active_branch\":\"main\",\"entries\":[\(entries.joined(separator: ","))]}"
  }()
}

extension DemoDaemonClient: TranscriptStreaming {
  public func transcriptStream(baseURL: URL, session: String, branch: String?)
    -> AsyncThrowingStream<TranscriptStreamEvent, Error>
  {
    let long = ProcessInfo.processInfo.arguments.contains("-cappuccino-demo-transcript-long")
    let payload = long ? DemoTranscriptPayload.long : DemoTranscriptPayload.standard
    return AsyncThrowingStream { continuation in
      let task = Task {
        func emit(_ event: TranscriptStreamEvent, sleepNanos: UInt64 = 0) async {
          if sleepNanos > 0 {
            try? await Task.sleep(nanoseconds: sleepNanos)
          }
          continuation.yield(event)
        }
        do {
          await emit(.open(generation: 0), sleepNanos: 100_000_000)
          let fixture = try TranscriptFixture.load(from: Data(payload.utf8))
          await emit(.entries(fixture.entries), sleepNanos: 700_000_000)
          if long {
            continuation.finish()
            return
          }
          // Live append with a genuinely new id.
          await emit(
            .entries([
              TranscriptEntry(
                seq: 4, id: "assistant-4-live", kind: "assistant",
                text: "LIVE-APPEND-ROW: collector rerun finished.", branch: nil, complete: true,
                tool: nil)
            ]),
            sleepNanos: 4_000_000_000)
          // Duplicate delivery: same id must render exactly once.
          await emit(
            .entries([
              TranscriptEntry(
                seq: 4, id: "assistant-4-live", kind: "assistant",
                text: "LIVE-APPEND-ROW: collector rerun finished.", branch: nil, complete: true,
                tool: nil)
            ]),
            sleepNanos: 700_000_000)
          // Post-gap entry: seq jumps, so a visible placeholder precedes it.
          // The 6s hold gives the UI journey a wide observable window.
          await emit(
            .entries([
              TranscriptEntry(
                seq: 9, id: "assistant-9", kind: "assistant",
                text: "POST-GAP-ROW: resumed after lost entries.", branch: nil, complete: true,
                tool: nil)
            ]),
            sleepNanos: 6_000_000_000)
          // Disconnect journey: hold the post-gap state, then close. Reset
          // semantics (durable reload, restart) are covered by Core model
          // tests — a scripted stream cannot represent them honestly.
          try? await Task.sleep(nanoseconds: 2_000_000_000)
          continuation.finish()
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
