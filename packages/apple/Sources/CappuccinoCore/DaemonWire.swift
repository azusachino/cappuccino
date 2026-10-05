import Foundation

// Typed errors for the daemon wire v0 (services/daemon/README.md). Pairing
// failures are explicit errors, never silent retries (behavior spec v0).

public enum DaemonClientError: Error, Equatable, Sendable {
  case unauthorized
  case unreachable(String)
  case protocolError(String)

  public var message: String {
    switch self {
    case .unauthorized:
      return "Pairing token rejected by the daemon."
    case .unreachable(let detail):
      return "Daemon unreachable: \(detail)"
    case .protocolError(let detail):
      return "Daemon protocol error: \(detail)"
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

  public init(machineID: String, sessionID: String, label: String, branch: String?, working: Bool) {
    self.machineID = machineID
    self.sessionID = sessionID
    self.label = label
    self.branch = branch
    self.working = working
  }
}

public struct PairedMachine: Equatable, Sendable {
  public let machineID: String

  public init(machineID: String) {
    self.machineID = machineID
  }
}

public enum DaemonWire {
  /// Parses one NDJSON daemon event (single JSON object).
  public static func parseEvent(_ data: Data) throws -> JSONEvent {
    guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw DaemonClientError.protocolError("event is not a JSON object")
    }
    switch object["event"] as? String {
    case "paired":
      guard let machineID = object["machine_id"] as? String, !machineID.isEmpty else {
        throw DaemonClientError.protocolError("paired event missing machine_id")
      }
      return .paired(PairedMachine(machineID: machineID))
    case "agents":
      guard let machineID = object["machine_id"] as? String else {
        throw DaemonClientError.protocolError("agents event missing machine_id")
      }
      let rawAgents = object["agents"] as? [[String: Any]] ?? []
      let rows = rawAgents.map { agent -> AgentRow in
        AgentRow(
          machineID: agent["machine_id"] as? String ?? machineID,
          sessionID: agent["session_id"] as? String ?? agent["pane_id"] as? String ?? "",
          label: agent["label"] as? String ?? agent["agent"] as? String ?? "",
          branch: agent["active_branch"] as? String,
          working: agent["working"] as? Bool ?? false
        )
      }
      return .agents(machineID: machineID, rows: rows)
    case "error":
      let code = object["code"] as? String ?? ""
      let message = object["message"] as? String ?? ""
      if code == "unauthorized" {
        throw DaemonClientError.unauthorized
      }
      throw DaemonClientError.protocolError(message.isEmpty ? code : message)
    default:
      throw DaemonClientError.protocolError("unknown event \(object["event"] as? String ?? "?")")
    }
  }

  /// Encodes a wire v0 request line (JSON + trailing newline).
  public static func encodeRequest(_ object: [String: Any]) throws -> Data {
    var payload = try JSONSerialization.data(withJSONObject: object)
    payload.append(0x0A)
    return payload
  }

  /// Maps the shared fixture shape (fixtures/agents-list.json) to rows. The
  /// fixture omits pane_id, so session_id stands alone there; null or missing
  /// active_branch renders as no branch.
  public static func rowsFromFixture(_ data: Data) throws -> (machineID: String, rows: [AgentRow]) {
    guard let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
      let machineID = object["machine_id"] as? String,
      let agents = object["agents"] as? [[String: Any]]
    else {
      throw DaemonClientError.protocolError("fixture is not an agents-list document")
    }
    let rows = agents.map { agent in
      AgentRow(
        machineID: agent["machine_id"] as? String ?? machineID,
        sessionID: agent["session_id"] as? String ?? "",
        label: agent["label"] as? String ?? "",
        branch: agent["active_branch"] as? String,
        working: agent["working"] as? Bool ?? false
      )
    }
    return (machineID, rows)
  }
}

public enum JSONEvent: Equatable, Sendable {
  case paired(PairedMachine)
  case agents(machineID: String, rows: [AgentRow])
}
