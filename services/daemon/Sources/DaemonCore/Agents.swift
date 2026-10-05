import Foundation

// Agent identity per behavior spec v0: an agent_ref is {machine_id, session_id};
// the daemon surfaces exactly what `herdr agent list` sees on this machine.

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

  public init(
    machineId: String, sessionId: String, paneId: String, label: String, agentKind: String,
    status: String, working: Bool, cwd: String, activeBranch: String?
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
    ])
  }
}

public final class MachineIdentity: Sendable {
  public let machineId: String
  private let lock = NSLock()
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
    guard let agents = result["agents"]?.arrayValue else {
      throw HerdrClient.HerdrError.protocolError("agent.list returned no agents array")
    }
    return agents.compactMap { agent in
      guard let name = agent["name"]?.stringValue, !name.isEmpty,
        let paneId = agent["pane_id"]?.stringValue
      else { return nil }
      let cwd = agent["cwd"]?.stringValue ?? ""
      return AgentInfo(
        machineId: machineId,
        // session_id: the pane's reported agent name; fall back to pane id when
        // unnamed so session_id is never empty (machine-scoped identity).
        sessionId: name,
        paneId: paneId,
        label: agent["terminal_title_stripped"]?.stringValue ?? agent["terminal_title"]?.stringValue ?? name,
        agentKind: agent["agent"]?.stringValue ?? "unknown",
        status: agent["agent_status"]?.stringValue ?? "unknown",
        working: agent["agent_status"]?.stringValue == "working",
        cwd: cwd,
        activeBranch: Self.activeBranch(cwd: cwd)
      )
    }
  }

  /// Read-only git query; nil when the cwd is not a work tree.
  static func activeBranch(cwd: String) -> String? {
    guard !cwd.isEmpty else { return nil }
    let process = Process()
    process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
    process.arguments = ["-C", cwd, "--no-optional-locks", "branch", "--show-current"]
    let pipe = Pipe()
    process.standardOutput = pipe
    process.standardError = FileHandle.nullDevice
    do {
      try process.run()
      let data = pipe.fileHandleForReading.readDataToEndOfFile()
      process.waitUntilExit()
      guard process.terminationStatus == 0 else { return nil }
      let branch = String(data: data, encoding: .utf8)?
        .trimmingCharacters(in: .whitespacesAndNewlines)
      return (branch?.isEmpty == false) ? branch : nil
    } catch {
      return nil
    }
  }
}
