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

  public init(
    store: BridgeProfileStore = BridgeProfileStore(),
    client: any BridgeMachineServing = BridgeClient()
  ) {
    self.store = store
    self.client = client
    do {
      profiles = try store.read()
    } catch {
      profiles = []
      storageError = error.localizedDescription
    }
  }

  /// Called only with a profile constructed after pairing. Refresh re-verifies its UUID.
  public func saveVerifiedProfile(_ profile: BridgeProfile) throws {
    guard storageError == nil else {
      throw DaemonClientError.protocolError(
        "Resolve unreadable saved profiles before replacing them")
    }
    var updated = profiles
    if let index = updated.firstIndex(where: { $0.id == profile.id }) {
      guard updated[index].machineID == profile.machineID,
        updated[index].endpoint == profile.endpoint
      else {
        throw DaemonClientError.protocolError("Rename cannot change machine ownership")
      }
      updated[index] = profile
    } else {
      updated.append(profile)
    }
    try store.save(updated)
    profiles = updated
    if foreground { refresh(profile.id) }
  }

  public func remove(_ id: UUID) throws {
    let updated = profiles.filter { $0.id != id }
    guard storageError == nil else {
      throw DaemonClientError.protocolError(
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
    } else {
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
    jobs[id] = Task { [weak self, client] in
      do {
        let verified = try await client.verifyMachine(
          baseURL: profile.endpoint, expectedID: profile.machineID)
        try Task.checkCancellation()
        let agents = try await client.listAgents(
          baseURL: profile.endpoint, expectedMachineID: profile.machineID)
        try Task.checkCancellation()
        guard
          agents.allSatisfy({
            UUID(uuidString: $0.machineID) == profile.machineID && !$0.sessionID.isEmpty
          })
        else {
          throw DaemonClientError.protocolError("Agent ownership does not match this machine")
        }
        guard let self, self.generations[id] == generation, self.foreground else { return }
        var result = MachineConnection()
        result.phase = .connected
        result.agents = agents
        result.details = verified.details
        result.name = verified.name
        self.connections[id] = result
        self.jobs.removeValue(forKey: id)
      } catch {
        guard let self, self.generations[id] == generation, self.foreground else { return }
        if error is CancellationError { return }
        var result = MachineConnection()
        result.phase = .failed((error as? DaemonClientError)?.message ?? error.localizedDescription)
        self.connections[id] = result
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
