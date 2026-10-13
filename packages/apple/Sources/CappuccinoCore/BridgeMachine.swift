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
