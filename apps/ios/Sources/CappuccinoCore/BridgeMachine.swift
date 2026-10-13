import Foundation

/// Identity verified from the bridge protocol, never inferred from a host or display name.
public struct BridgeMachine: Equatable, Sendable {
  public let id: UUID
  public let name: String?
  public let version: String?

  public init(id: UUID, name: String?, version: String?) {
    self.id = id
    self.name = name
    self.version = version
  }
}

/// A verified handshake: the machine identity plus the advertisements the app gates actions on.
public struct VerifiedMachine: Equatable, Sendable {
  public let machine: BridgeMachine
  public let details: BridgeDetails

  /// The machine's own name from /api/session, if it reported one.
  public var name: String? { details.machineName }
}
