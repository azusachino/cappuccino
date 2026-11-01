import Foundation

public struct AgentIdentity: Hashable, Codable, Sendable {
  public let machineID: UUID
  public let sessionID: String

  public init(machineID: UUID, sessionID: String) {
    self.machineID = machineID
    self.sessionID = sessionID
  }
}

/// Which agent program runs in a pane, from `AgentRow.provider`. Android's avatar colours use the
/// same grouping (`agentBrandColors`): Antigravity reports itself under several names.
public enum AgentKind: Equatable, Sendable {
  case pi
  case claude
  case agy
  case other

  public init(provider: String?) {
    switch provider?.lowercased() {
    case "pi": self = .pi
    case "claude": self = .claude
    case "agy", "antigravity", "antigravity-cli": self = .agy
    default: self = .other
    }
  }
}
