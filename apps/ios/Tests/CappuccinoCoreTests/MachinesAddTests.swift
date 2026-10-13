import Foundation
import Testing

@testable import CappuccinoCore

/// Add and rename on the Machines screen: a machine is saved only after its handshake verifies.
private struct VerifyingDiscovery: BridgeMachineServing {
  let machine: UUID
  let name: String?

  func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> VerifiedMachine {
    VerifiedMachine(
      machine: BridgeMachine(id: machine, name: name, version: nil),
      details: BridgeDetails(
        machineID: machine, machineName: name, version: nil, protocolVersion: 1,
        capabilities: nil))
  }

  func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] { [] }
}

@MainActor struct MachinesAddTests {
  private func makeConnections(machine: UUID, name: String?) -> (MachineConnections, String) {
    let suite = "cappuccino-add-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    let store = BridgeProfileStore(defaults: defaults)
    return (
      MachineConnections(
        store: store, client: VerifyingDiscovery(machine: machine, name: name)), suite
    )
  }

  @Test func addSavesOnlyAVerifiedMachineAndNamesItFromItsReport() async throws {
    let machine = UUID()
    let (connections, suite) = makeConnections(machine: machine, name: "Mini")
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    try await connections.addMachine(address: " https://mini.example ", label: "")
    #expect(connections.profiles.map(\.machineID) == [machine])
    #expect(connections.profiles.map(\.name) == ["Mini"])
    await #expect(throws: BridgeError.self) {
      try await connections.addMachine(address: "https://mini.example", label: "")
    }
    #expect(connections.profiles.count == 1, "the same verified machine is not saved twice")
  }

  @Test func addRejectsMalformedAddressesWithoutSaving() async {
    let (connections, suite) = makeConnections(machine: UUID(), name: nil)
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    await #expect(throws: BridgeError.self) {
      try await connections.addMachine(address: "not a url", label: "")
    }
    #expect(connections.profiles.isEmpty)
  }

  @Test func renameChangesOnlyTheLabel() async throws {
    let machine = UUID()
    let (connections, suite) = makeConnections(machine: machine, name: nil)
    defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
    try await connections.addMachine(address: "https://mini.example", label: "Old")
    let id = try #require(connections.profiles.first?.id)
    try connections.rename(id, to: "Desk Mini")
    #expect(connections.profiles.first?.name == "Desk Mini")
    #expect(connections.profiles.first?.machineID == machine)
  }
}
