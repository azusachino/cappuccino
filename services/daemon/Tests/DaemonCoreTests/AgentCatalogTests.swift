import XCTest
@testable import DaemonCore

// Acceptance-blocker regressions: exact `herdr agent list` parity (unnamed
// agents included) and branch honesty (no inherited enclosing-repo branches).

final class AgentCatalogTests: XCTestCase {
  private func agentJson(name: String?, paneId: String, status: String = "idle") -> JSONValue {
    .object([
      "agent": .string("pi"),
      "pane_id": .string(paneId),
      "agent_status": .string(status),
      "cwd": .string("/tmp/agent-cwd"),
      "name": name.map { .string($0) } ?? .null,
      "terminal_title": .string("π - pane"),
    ])
  }

  func testUnnamedAgentsAreIncludedWithDeterministicFallback() throws {
    let result: JSONValue = .object([
      "agents": .array([
        agentJson(name: "cap-spike-agent", paneId: "w1:p5Y"),
        agentJson(name: nil, paneId: "w1:p43", status: "working"),
      ])
    ])
    let agents = try AgentCatalog.mapAgents(from: result, machineId: "m-1")
    XCTAssertEqual(agents.count, 2, "unnamed agents must not be dropped: herdr lists them")
    let named = try XCTUnwrap(agents.first { $0.paneId == "w1:p5Y" })
    XCTAssertEqual(named.sessionId, "cap-spike-agent")
    let unnamed = try XCTUnwrap(agents.first { $0.paneId == "w1:p43" })
    // Deterministic fallback identity: pane id as session_id, kind as label.
    XCTAssertEqual(unnamed.sessionId, "w1:p43")
    XCTAssertEqual(unnamed.label, "pi")
    XCTAssertEqual(unnamed.working, true)
    // Stable across re-mapping of the same input.
    let again = try AgentCatalog.mapAgents(from: result, machineId: "m-1")
    XCTAssertEqual(again, agents)
  }

  func testBranchIsReportedOnlyForTheSessionOwnRepository() {
    let fm = FileManager.default
    let root = fm.temporaryDirectory
      .appendingPathComponent("cap-spike-branch-tests-\(UUID().uuidString)")
    let repo = root.appendingPathComponent("repo")
    let nested = repo.appendingPathComponent("deep/nested/dir")
    let plain = root.appendingPathComponent("plain-dir")
    defer { try? fm.removeItem(at: root) }
    XCTAssertNoThrow(try fm.createDirectory(at: nested, withIntermediateDirectories: true))
    XCTAssertNoThrow(try fm.createDirectory(at: plain, withIntermediateDirectories: true))

    func git(_ directory: String, _ arguments: [String]) {
      let process = Process()
      process.executableURL = URL(fileURLWithPath: "/usr/bin/git")
      process.arguments = ["-C", directory] + arguments
      process.standardOutput = FileHandle.nullDevice
      process.standardError = FileHandle.nullDevice
      XCTAssertNoThrow(try process.run())
      process.waitUntilExit()
      XCTAssertEqual(process.terminationStatus, 0)
    }

    git(repo.path, ["init", "-q"])
    git(repo.path, ["-c", "user.email=t@t", "-c", "user.name=t", "commit", "--allow-empty", "-q", "-m", "init"])
    git(repo.path, ["checkout", "-q", "-b", "feature-branch"])

    // Repo toplevel: the session's own branch is reported.
    let own = AgentCatalog.ownBranch(cwd: repo.path)
    XCTAssertEqual(own.source, "own")
    XCTAssertEqual(own.branch, "feature-branch")

    // Nested inside the repo (the workstation-checkout situation): git would
    // discover the enclosing repo, but that is inherited context, not the
    // session's branch — must be null with source "none".
    let inherited = AgentCatalog.ownBranch(cwd: nested.path)
    XCTAssertEqual(inherited.source, "none")
    XCTAssertNil(inherited.branch)

    // No repository at all.
    let none = AgentCatalog.ownBranch(cwd: plain.path)
    XCTAssertEqual(none.source, "none")
    XCTAssertNil(none.branch)

    // Empty cwd.
    let empty = AgentCatalog.ownBranch(cwd: "")
    XCTAssertEqual(empty.source, "none")
    XCTAssertNil(empty.branch)
  }
}
