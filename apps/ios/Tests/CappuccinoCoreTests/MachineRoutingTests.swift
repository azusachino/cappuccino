import Foundation
import Testing

@testable import CappuccinoCore

/// Fails every iroh request and answers over the tailnet, recording the base URLs it was called with.
private actor RoutedDiscovery: BridgeMachineServing {
  private(set) var bases: [URL] = []
  let machine: UUID

  init(machine: UUID) {
    self.machine = machine
  }

  func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> VerifiedMachine {
    bases.append(baseURL)
    if baseURL.scheme == irohURLScheme { throw URLError(.timedOut) }
    return VerifiedMachine(
      machine: BridgeMachine(id: machine, name: nil, version: nil),
      details: BridgeDetails(
        machineID: machine, machineName: nil, version: nil, protocolVersion: 1,
        capabilities: [:]))
  }

  func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] {
    bases.append(baseURL)
    if baseURL.scheme == irohURLScheme { throw URLError(.timedOut) }
    return []
  }
}

@MainActor struct MachineRoutingTests {
  @Test func anIrohFailureFallsBackToTheTailnetAddressDuringRefresh() async throws {
    let defaults = UserDefaults(suiteName: "routing-\(UUID().uuidString)")!
    let machine = UUID()
    let iroh = IrohEndpointAddr(endpointID: "peer-route", relayURL: nil, directAddrs: [])
    let profile = try BridgeProfile(
      machineID: machine, endpoint: URL(string: "cappiroh://peer-route")!, name: "Mini",
      iroh: iroh, tailnetFallback: URL(string: "https://mini.tailnet.example")!)
    let store = BridgeProfileStore(defaults: defaults)
    try store.save([profile])
    let discovery = RoutedDiscovery(machine: machine)
    let machines = MachineConnections(store: store, client: discovery)
    machines.setForeground(true)

    await machines.refresh(profile.id)?.value

    #expect(machines.connections[profile.id]?.phase == .connected)
    let bases = await discovery.bases
    #expect(bases.first?.scheme == irohURLScheme, "iroh is probed first")
    #expect(bases.last?.scheme == "https", "the tailnet answers after iroh fails")
  }

  @Test func aTailnetFallbackThatNamesIrohIsRejected() {
    #expect(throws: BridgeError.self) {
      try BridgeProfile(
        machineID: UUID(), endpoint: URL(string: "https://mini.tailnet.example")!, name: "Mini",
        tailnetFallback: URL(string: "cappiroh://peer")!)
    }
  }
}
