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
