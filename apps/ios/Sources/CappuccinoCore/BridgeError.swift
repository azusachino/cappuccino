import Foundation

/// The error every bridge call throws. Its message is the owner-facing reason, or
/// the transport fact when no reason was given (see OwnerMessage).
public enum BridgeError: Error, Equatable, Sendable {
  case unauthorized
  case unreachable(String)
  case protocolError(String)
  case streamOverflow

  public var message: String {
    switch self {
    case .unauthorized:
      return "Pairing token rejected by the bridge."
    case .unreachable(let detail):
      return "Bridge unreachable: \(detail)"
    case .protocolError(let detail):
      return "Bridge protocol error: \(detail)"
    case .streamOverflow:
      return "Bridge stream consumer fell behind"
    }
  }
}

/// Preserve typed TLS failures through transport wrappers so they never enter retry budgets.
func isBridgeTLSFailure(_ error: any Error) -> Bool {
  guard let failure = error as? URLError else { return false }
  switch failure.code {
  case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
    .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid, .clientCertificateRejected,
    .clientCertificateRequired, .appTransportSecurityRequiresSecureConnection:
    return true
  default: return false
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
  /// Herdr's own status: `idle`, `working`, `blocked`, `done` or `unknown`.
  public let status: String
  public let cwd: String?
  /// The agent kind (`pi`, `claude`, `agy`); a canonical conversation id must carry it.
  public let provider: String?
  public let paneID: String?
  public let nativeSessionRef: String?

  public init(
    machineID: String, sessionID: String, label: String, branch: String?, working: Bool,
    provider: String? = nil, status: String? = nil, cwd: String? = nil,
    paneID: String? = nil, nativeSessionRef: String? = nil
  ) {
    self.machineID = machineID
    self.sessionID = sessionID
    self.label = label
    self.branch = branch
    self.working = working
    self.provider = provider
    self.status = status ?? (working ? "working" : "idle")
    self.cwd = cwd
    self.paneID = paneID
    self.nativeSessionRef = nativeSessionRef
  }
}

public struct PairedMachine: Equatable, Sendable {
  public let machineID: String

  public init(machineID: String) {
    self.machineID = machineID
  }
}
