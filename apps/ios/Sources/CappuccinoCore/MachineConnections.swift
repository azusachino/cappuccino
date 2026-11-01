import Foundation
import Observation

public protocol BridgeMachineServing: Sendable {
  func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> VerifiedMachine
  func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow]
}

extension BridgeClient: BridgeMachineServing {}

public enum MachineConnectionPhase: Equatable, Sendable {
  case disconnected
  case connecting
  case connected
  case failed(String)
}

public struct MachineConnection: Equatable, Sendable {
  public var phase: MachineConnectionPhase = .disconnected
  public var agents: [AgentRow] = []
  public var refreshing = false
  /// Advertisements from the last verified handshake; nil until connected.
  public var details: BridgeDetails?
  public var name: String?

  public init() {}
}

/// Owns independent read-only discovery. Session viewers and delivery are separate owners.
@MainActor @Observable
public final class MachineConnections {
  public private(set) var profiles: [BridgeProfile]
  public private(set) var connections: [UUID: MachineConnection] = [:]
  public private(set) var storageError: String?
  @ObservationIgnored private let store: BridgeProfileStore
  @ObservationIgnored private let client: any BridgeMachineServing
  @ObservationIgnored private var jobs: [UUID: Task<Void, Never>] = [:]
  @ObservationIgnored private var generations: [UUID: UUID] = [:]
  @ObservationIgnored private var manuallyDisconnected: Set<UUID> = []
  @ObservationIgnored private var foreground = false
  @ObservationIgnored private var routes: [UUID: MachineRoute] = [:]
  @ObservationIgnored private var poller: Task<Void, Never>?
  public let log: ConnectionLog

  public init(
    store: BridgeProfileStore = BridgeProfileStore(),
    client: any BridgeMachineServing = BridgeClient(),
    log: ConnectionLog = ConnectionLog()
  ) {
    self.store = store
    self.client = client
    self.log = log
    do {
      profiles = try store.read()
    } catch {
      profiles = []
      storageError = error.localizedDescription
    }
    // Iroh peers must be known before their requests can be routed.
    for profile in profiles {
      if let iroh = profile.iroh { IrohHub.shared.register(iroh) }
    }
  }

  /// Called only with a profile constructed after pairing. Refresh re-verifies its UUID.
  public func saveVerifiedProfile(_ profile: BridgeProfile) throws {
    guard storageError == nil else {
      throw BridgeError.protocolError(
        "Resolve unreadable saved profiles before replacing them")
    }
    var updated = profiles
    if let index = updated.firstIndex(where: { $0.id == profile.id }) {
      guard updated[index].machineID == profile.machineID,
        updated[index].endpoint == profile.endpoint
      else {
        throw BridgeError.protocolError("Rename cannot change machine ownership")
      }
      updated[index] = profile
    } else {
      updated.append(profile)
    }
    try store.save(updated)
    profiles = updated
    if foreground { refresh(profile.id) }
  }

  /// Verifies the bridge at `address` and saves it bound to the verified machine.
  public func addMachine(address: String, label: String) async throws {
    guard
      let url = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
      url.host != nil
    else {
      throw BridgeError.protocolError(
        "Enter the machine's base URL, e.g. http://127.0.0.1:7392")
    }
    let verified = try await client.verifyMachine(baseURL: url, expectedID: nil)
    let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
    let name = trimmed.isEmpty ? (verified.name ?? url.host ?? "Machine") : trimmed
    try saveVerifiedProfile(
      try BridgeProfile(machineID: verified.machine.id, endpoint: url, name: name))
  }

  /// The transports for one machine, in Android's order: iroh first, then the tailnet fallback.
  /// The router is kept per machine so its sticky choice survives between refreshes. A changed
  /// address rebuilds it.
  private func route(for profile: BridgeProfile) -> MachineRouter<URL> {
    var clients: [TransportKind: URL] = [:]
    if let iroh = profile.iroh, let url = URL(string: "\(irohURLScheme)://\(iroh.endpointID)") {
      clients[.iroh] = url
    }
    if let tailnet = profile.tailnetFallback {
      clients[.tailnet] = tailnet
    } else if profile.iroh == nil {
      clients[.tailnet] = profile.endpoint
    }
    if let cached = routes[profile.id], cached.clients == clients {
      return cached.router
    }
    let router = MachineRouter<URL>(clients: clients, now: { Date().timeIntervalSince1970 })
    routes[profile.id] = MachineRoute(clients: clients, router: router)
    return router
  }

  /// Pairs an iroh machine from its pairing payload and saves it under its verified identity.
  public func addIrohMachine(payload: String, label: String) async throws {
    let profile = try await pairIrohMachine(payload: payload, name: label, verifier: client)
    try saveVerifiedProfile(profile)
  }

  /// Renames a saved machine. Its verified identity and endpoint are unchanged.
  public func rename(_ id: UUID, to label: String) throws {
    guard let profile = profiles.first(where: { $0.id == id }) else { return }
    try saveVerifiedProfile(
      try BridgeProfile(
        id: id, machineID: profile.machineID, endpoint: profile.endpoint, name: label,
        iroh: profile.iroh, tailnetFallback: profile.tailnetFallback))
  }

  public func remove(_ id: UUID) throws {
    let updated = profiles.filter { $0.id != id }
    guard storageError == nil else {
      throw BridgeError.protocolError(
        "Resolve unreadable saved profiles before replacing them")
    }
    try store.save(updated)
    disconnect(id)
    profiles = updated
    connections.removeValue(forKey: id)
    manuallyDisconnected.remove(id)
  }

  public func setForeground(_ active: Bool) {
    foreground = active
    if active {
      refreshAll()
      startPolling()
    } else {
      stopPolling()
      for id in Array(jobs.keys) { invalidate(id) }
      for id in Array(connections.keys) {
        connections[id]?.phase = .disconnected
        connections[id]?.refreshing = false
      }
    }
  }

  public func disconnect(_ id: UUID) {
    invalidate(id)
    manuallyDisconnected.insert(id)
    connections[id] = MachineConnection()
  }

  /// Agent status goes stale without polling. While the app is in the foreground, every machine
  /// is refreshed on a fixed cadence (Android's direct-path rate); a refresh already in flight is
  /// skipped by refreshAll.
  private func startPolling() {
    guard poller == nil else { return }
    poller = Task { [weak self] in
      while !Task.isCancelled {
        try? await Task.sleep(for: .seconds(pollIntervalSeconds(.noReading)))
        guard !Task.isCancelled, let self else { return }
        self.refreshAll()
      }
    }
  }

  private func stopPolling() {
    poller?.cancel()
    poller = nil
  }

  public func refreshAll() {
    guard foreground else { return }
    for profile in profiles
    where !manuallyDisconnected.contains(profile.id) && jobs[profile.id] == nil {
      refresh(profile.id, manual: false)
    }
  }

  @discardableResult
  public func refresh(_ id: UUID, manual: Bool = true) -> Task<Void, Never>? {
    guard foreground, let profile = profiles.first(where: { $0.id == id }) else { return nil }
    if manual { manuallyDisconnected.remove(id) }
    guard !manuallyDisconnected.contains(id) else { return nil }
    invalidate(id)
    let generation = UUID()
    generations[id] = generation
    var connection = connections[id] ?? MachineConnection()
    if connection.phase != .connected { connection.phase = .connecting }
    connection.refreshing = manual
    connections[id] = connection
    log.log("MACHINE", "Refreshing \(profile.name)")
    let router = route(for: profile)
    jobs[id] = Task { [weak self, client] in
      do {
        let verified = try await router.request { base in
          try await client.verifyMachine(baseURL: base, expectedID: profile.machineID)
        }
        try Task.checkCancellation()
        let agents = try await router.request { base in
          try await client.listAgents(baseURL: base, expectedMachineID: profile.machineID)
        }
        try Task.checkCancellation()
        guard
          agents.allSatisfy({
            UUID(uuidString: $0.machineID) == profile.machineID && !$0.sessionID.isEmpty
          })
        else {
          throw BridgeError.protocolError("Agent ownership does not match this machine")
        }
        guard let self, self.generations[id] == generation, self.foreground else { return }
        var result = MachineConnection()
        result.phase = .connected
        result.agents = agents
        result.details = verified.details
        result.name = verified.name
        self.connections[id] = result
        self.log.log("MACHINE", "\(profile.name) connected, \(agents.count) agents")
        self.jobs.removeValue(forKey: id)
      } catch {
        guard let self, self.generations[id] == generation, self.foreground else { return }
        if error is CancellationError { return }
        let reason = (error as? BridgeError)?.message ?? error.localizedDescription
        var result = MachineConnection()
        result.phase = .failed(reason)
        self.connections[id] = result
        self.log.log("MACHINE", "\(profile.name) failed: \(reason)", level: .error)
        self.jobs.removeValue(forKey: id)
      }
    }
    return jobs[id]
  }

  private func invalidate(_ id: UUID) {
    generations.removeValue(forKey: id)
    jobs.removeValue(forKey: id)?.cancel()
  }
}

/// One machine's router, with the transport addresses it was built from.
private struct MachineRoute {
  let clients: [TransportKind: URL]
  let router: MachineRouter<URL>
}
