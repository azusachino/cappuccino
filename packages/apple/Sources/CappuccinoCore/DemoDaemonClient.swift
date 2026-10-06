import Foundation

// The surface the Machines UI consumes; real daemon client and the scripted
// demo (UI tests, previews) both conform.

public protocol DaemonServing: Sendable {
  func pair(token: String) async throws -> PairedMachine
  func listAgents(token: String) async throws -> [AgentRow]
}

extension DaemonClient: DaemonServing {}

/// Scripted client for UI tests and previews: token "demo-ok" pairs, anything
/// else fails visibly; the agent list mirrors fixtures/agents-list.json plus
/// the unnamed-pane and null-branch cases the fixture set is extended by.
public struct DemoDaemonClient: DaemonServing {
  public static let successToken = "demo-ok"

  public init() {}

  public func pair(token: String) async throws -> PairedMachine {
    try await Task.sleep(nanoseconds: 150_000_000)
    guard token == Self.successToken else {
      throw DaemonClientError.unauthorized
    }
    return PairedMachine(machineID: "11111111-1111-4111-8111-111111111111")
  }

  public func listAgents(token: String) async throws -> [AgentRow] {
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
