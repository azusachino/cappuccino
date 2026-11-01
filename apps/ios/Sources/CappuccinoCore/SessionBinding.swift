import Foundation

/// The agent session an owner write is meant for. The bridge refuses a prompt, answer or
/// control without it (`expected_session`, 428 `session_binding_required`) and rechecks it
/// against Herdr before writing, so a replaced pane never receives the owner's input.
public struct SessionBinding: Equatable, Sendable {
  public let machineID: String
  public let paneID: String
  public let nativeSessionRef: String

  public init(machineID: String, paneID: String, nativeSessionRef: String) {
    self.machineID = machineID
    self.paneID = paneID
    self.nativeSessionRef = nativeSessionRef
  }

  /// Nil when discovery did not report the agent's pane or native session reference.
  public init?(_ agent: AgentRow) {
    guard let paneID = agent.paneID, !paneID.isEmpty,
      let nativeSessionRef = agent.nativeSessionRef, !nativeSessionRef.isEmpty
    else { return nil }
    self.init(machineID: agent.machineID, paneID: paneID, nativeSessionRef: nativeSessionRef)
  }

  /// The `expected_session` object of a write body.
  public var json: [String: Any] {
    ["machine_id": machineID, "pane_id": paneID, "native_session_ref": nativeSessionRef]
  }
}
