import Foundation

public struct AgentIdentity: Hashable, Codable, Sendable {
  public let machineID: UUID
  public let sessionID: String

  public init(machineID: UUID, sessionID: String) {
    self.machineID = machineID
    self.sessionID = sessionID
  }
}
