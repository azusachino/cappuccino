import Foundation

/// The error every bridge call throws. Its message is the owner-facing reason, or
/// the transport fact when no reason was given (see OwnerMessage).
public enum BridgeError: Error, Equatable, Sendable {
  case unauthorized
  case unreachable(String)
  case protocolError(String)

  public var message: String {
    switch self {
    case .unauthorized:
      return "Pairing token rejected by the bridge."
    case .unreachable(let detail):
      return "Bridge unreachable: \(detail)"
    case .protocolError(let detail):
      return "Bridge protocol error: \(detail)"
    }
  }
}

// Display models: one row per agent, exactly what the Machines tab renders.

public struct AgentRow: Identifiable, Equatable, Sendable {
  public var id: String { "\(machineID)/\(sessionID)" }
  public let machineID: String
  public let sessionID: String
  public let label: String
  public let branch: String?
  public let working: Bool
  /// The agent kind (`pi`, `claude`, `agy`); a canonical conversation id must carry it.
  public let provider: String?

  public init(
    machineID: String, sessionID: String, label: String, branch: String?, working: Bool,
    provider: String? = nil
  ) {
    self.machineID = machineID
    self.sessionID = sessionID
    self.label = label
    self.branch = branch
    self.working = working
    self.provider = provider
  }
}

public struct PairedMachine: Equatable, Sendable {
  public let machineID: String

  public init(machineID: String) {
    self.machineID = machineID
  }
}
