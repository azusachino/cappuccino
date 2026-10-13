import Foundation
import Testing

@testable import CappuccinoCore

private actor ControlledDiscovery: BridgeMachineServing {
  let machines: [String: UUID]
  var pending: [String: CheckedContinuation<[AgentRow], any Error>] = [:]
  var waiting: [String: CheckedContinuation<Void, Never>] = [:]

  init(_ profiles: [BridgeProfile]) {
    machines = Dictionary(uniqueKeysWithValues: profiles.map { ($0.endpoint.host!, $0.machineID) })
  }

  func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> BridgeMachine {
    guard let id = machines[baseURL.host!], expectedID == id else {
      throw DaemonClientError.protocolError("Wrong machine")
    }
    return BridgeMachine(id: id, name: nil, version: nil)
  }

  func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] {
    let host = baseURL.host!
    return try await withCheckedThrowingContinuation { continuation in
      pending[host] = continuation
      waiting.removeValue(forKey: host)?.resume()
    }
  }

  func waitForRequest(_ host: String) async {
    if pending[host] != nil { return }
    await withCheckedContinuation { waiting[host] = $0 }
  }

  func complete(_ host: String, failure: Bool = false, foreign: Bool = false) {
    guard let continuation = pending.removeValue(forKey: host) else { return }
    if failure {
      continuation.resume(throwing: DaemonClientError.unreachable("offline"))
    } else {
      continuation.resume(returning: [
        AgentRow(
          machineID: (foreign ? UUID() : machines[host]!).uuidString,
          sessionID: "same-locator", label: host, branch: nil, working: false)
      ])
    }
  }
}

@MainActor struct MachineConnectionsTests {
  @Test func independentDiscoveryRejectsLateDisconnectedAndForeignResults() async throws {
    let suite = "cappuccino-connections-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let mac = try BridgeProfile(
      machineID: UUID(), endpoint: URL(string: "https://mac.example")!, name: "Mac")
    let mini = try BridgeProfile(
      machineID: UUID(), endpoint: URL(string: "https://mini.example")!, name: "Mini")
    let store = BridgeProfileStore(defaults: defaults)
    try store.save([mac, mini])
    let client = ControlledDiscovery([mac, mini])
    let model = MachineConnections(store: store, client: client)
    model.setForeground(true)
    defer { model.setForeground(false) }
    let macJob = model.refresh(mac.id)!
    let miniJob = model.refresh(mini.id)!
    await client.waitForRequest("mac.example")
    await client.waitForRequest("mini.example")
    await client.complete("mac.example")
    await macJob.value
    #expect(model.connections[mac.id]?.phase == .connected)
    model.disconnect(mini.id)
    await client.complete("mini.example")
    await miniJob.value
    #expect(model.connections[mini.id]?.phase == .disconnected)
    #expect(model.connections[mac.id]?.phase == .connected)
    let failing = model.refresh(mini.id)!
    await client.waitForRequest("mini.example")
    await client.complete("mini.example", failure: true)
    await failing.value
    #expect(model.connections[mini.id]?.phase == .failed("Daemon unreachable: offline"))
    #expect(model.connections[mac.id]?.phase == .connected)
    let foreign = model.refresh(mini.id)!
    await client.waitForRequest("mini.example")
    await client.complete("mini.example", foreign: true)
    await foreign.value
    #expect(model.connections[mini.id]?.agents.isEmpty == true)
    let restored = model.refresh(mini.id)!
    await client.waitForRequest("mini.example")
    await client.complete("mini.example")
    await restored.value
    #expect(model.connections[mini.id]?.phase == .connected)
    #expect(
      model.connections[mac.id]?.agents.first?.id != model.connections[mini.id]?.agents.first?.id)
    try model.remove(mini.id)
    #expect(model.connections[mini.id] == nil)
    #expect(try store.read() == [mac])
    #expect(model.connections[mac.id]?.phase == .connected)
  }
}
