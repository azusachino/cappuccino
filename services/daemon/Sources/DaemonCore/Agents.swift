import Foundation

// Agent identity per behavior spec v0: an agent_ref is {machine_id, session_id};
// the daemon surfaces exactly what `herdr agent list` sees on this machine,
// including agents Herdr has no name for.

public struct AgentInfo: Sendable, Equatable {
  public var machineId: String
  public var sessionId: String
  public var paneId: String
  public var label: String
  public var agentKind: String
  public var status: String
  public var working: Bool
  public var cwd: String
  public var activeBranch: String?
  /// "own" when the git toplevel equals the agent cwd (the branch below is the
  /// session's own work); "none" when there is no branch to report because the
  /// cwd is not a repository root — any git hit there is inherited enclosing
  /// context and must not be presented as the session's branch.
  public var branchSource: String

  public init(
    machineId: String, sessionId: String, paneId: String, label: String, agentKind: String,
    status: String, working: Bool, cwd: String, activeBranch: String?, branchSource: String
  ) {
    self.machineId = machineId
    self.sessionId = sessionId
    self.paneId = paneId
    self.label = label
    self.agentKind = agentKind
    self.status = status
    self.working = working
    self.cwd = cwd
    self.activeBranch = activeBranch
    self.branchSource = branchSource
  }

  public var json: JSONValue {
    .object([
      "machine_id": .string(machineId),
      "session_id": .string(sessionId),
      "pane_id": .string(paneId),
      "label": .string(label),
      "agent": .string(agentKind),
      "status": .string(status),
      "working": .bool(working),
      "cwd": .string(cwd),
      "active_branch": activeBranch.map { .string($0) } ?? .null,
      "branch_source": .string(branchSource),
    ])
  }
}

public final class MachineIdentity: Sendable {
  public let machineId: String
  static let dir = NSString(string: "~/Library/Application Support/cappuccino-spike").expandingTildeInPath
  static let file = "\(dir)/machine-id"

  public init(store: String = "~/Library/Application Support/cappuccino-spike/machine-id") {
    let fm = FileManager.default
    if let existing = try? String(contentsOfFile: store, encoding: .utf8)
      .trimmingCharacters(in: .whitespacesAndNewlines), !existing.isEmpty
    {
      machineId = existing
      return
    }
    let fresh = UUID().uuidString.lowercased()
    try? fm.createDirectory(atPath: Self.dir, withIntermediateDirectories: true)
    fm.createFile(atPath: store, contents: Data(fresh.utf8), attributes: [.posixPermissions: 0o600])
    machineId = fresh
  }
}

public final class AgentCatalog: Sendable {
  private let client: HerdrClient
  public let machineId: String

  public init(client: HerdrClient, machineId: String) {
    self.client = client
    self.machineId = machineId
  }

  public func listAgents() throws -> [AgentInfo] {
    let result = try client.request(method: "agent.list", params: [:])
    return try Self.mapAgents(from: result, machineId: machineId)
  }

  /// Pure mapping of an `agent.list` result so Herdr parity is unit-testable.
  /// Unnamed agents are included (exact parity with `herdr agent list`) with a
  /// deterministic fallback identity: pane id as session_id, agent kind as
  /// label — never an invented name.
  static func mapAgents(from result: JSONValue, machineId: String) throws -> [AgentInfo] {
    guard let agents = result["agents"]?.arrayValue else {
      throw HerdrClient.HerdrError.protocolError("agent.list returned no agents array")
    }
    return agents.compactMap { agent in
      guard let paneId = agent["pane_id"]?.stringValue, !paneId.isEmpty else { return nil }
      let name = agent["name"]?.stringValue ?? ""
      let cwd = agent["cwd"]?.stringValue ?? ""
      let kind = agent["agent"]?.stringValue ?? "unknown"
      let branch = Self.ownBranch(cwd: cwd)
      return AgentInfo(
        machineId: machineId,
        // session_id: the pane's reported agent name; for unnamed panes the
        // pane id is the deterministic machine-scoped fallback.
        sessionId: name.isEmpty ? paneId : name,
        paneId: paneId,
        label: name.isEmpty
          ? kind
          : (agent["terminal_title_stripped"]?.stringValue
            ?? agent["terminal_title"]?.stringValue ?? name),
        agentKind: kind,
        status: agent["agent_status"]?.stringValue ?? "unknown",
        working: agent["agent_status"]?.stringValue == "working",
        cwd: cwd,
        activeBranch: branch.branch,
        branchSource: branch.source
      )
    }
  }

  public struct BranchReport: Sendable, Equatable {
    public var branch: String?
    public var source: String  // "own" | "none"
  }

  /// Reports the active branch only when the repository toplevel equals the
  /// agent cwd: the branch must be the session's own repository boundary, not
  /// inherited from an enclosing checkout. Pure in `cwd`; nil-safe everywhere.
  static func ownBranch(cwd: String) -> BranchReport {
    guard !cwd.isEmpty else { return BranchReport(branch: nil, source: "none") }
    guard let toplevel = Self.gitOutput(["-C", cwd, "rev-parse", "--show-toplevel"]) else {
      return BranchReport(branch: nil, source: "none")
    }
    guard Self.sameDirectory(cwd, toplevel) else {
      // Enclosing repository discovered the cwd from outside: inherited
      // context, never presented as the session's own branch.
      return BranchReport(branch: nil, source: "none")
    }
    let branch = Self.gitOutput(["-C", cwd, "--no-optional-locks", "branch", "--show-current"])
    return BranchReport(branch: branch, source: branch == nil ? "none" : "own")
  }

  /// Identity by device + inode: symlink-proof where string normalization is
  /// not (e.g. /var vs /private/var).
  static func sameDirectory(_ a: String, _ b: String) -> Bool {
    let fm = FileManager.default
    guard let attrsA = try? fm.attributesOfItem(atPath: a),
      let attrsB = try? fm.attributesOfItem(atPath: b),
      let inodeA = (attrsA[.systemFileNumber] as? NSNumber)?.uint64Value,
      let inodeB = (attrsB[.systemFileNumber] as? NSNumber)?.uint64Value
    else { return a == b }
    // Directory inodes are unique per filesystem; a mismatch in the optional
    // device identifier also implies different filesystems.
    let deviceA = (attrsA[.deviceIdentifier] as? NSNumber)?.uint64Value
    let deviceB = (attrsB[.deviceIdentifier] as? NSNumber)?.uint64Value
    if let deviceA, let deviceB, deviceA != deviceB { return false }
    return inodeA == inodeB
  }

  static func gitOutput(_ arguments: [String]) -> String? {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
    process.arguments = arguments
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    do {
      try process.run()
    } catch {
      return nil
    }
    let data = pipe.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    guard process.terminationStatus == 0 else { return nil }
    let output = String(data: data, encoding: .utf8)?
      .trimmingCharacters(in: .whitespacesAndNewlines)
    return (output?.isEmpty == false) ? output : nil
  }
}
