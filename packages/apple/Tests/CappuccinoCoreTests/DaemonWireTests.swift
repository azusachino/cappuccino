import Foundation
import Testing

@testable import CappuccinoCore

// Conformance with the shared fixtures/agents-list.json document plus the
// null-branch and unnamed-pane cases the daemon adds (issue #6).

private func fixtureURL(_ name: String) -> URL {
  URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()  // Tests/CappuccinoCoreTests
    .deletingLastPathComponent()  // Tests
    .deletingLastPathComponent()  // package root
    .deletingLastPathComponent()  // packages/apple
    .deletingLastPathComponent()  // vendor/cappuccino
    .appendingPathComponent("fixtures/\(name)")
}

@Suite struct FixtureConformanceTests {
  @Test func agentsListFixtureMapsToRows() throws {
    let data = try Data(contentsOf: fixtureURL("agents-list.json"))
    let (machineID, rows) = try DaemonWire.rowsFromFixture(data)
    #expect(machineID == "11111111-1111-4111-8111-111111111111")
    #expect(rows.count == 2)
    let aurora = try #require(rows.first { $0.sessionID == "s-aurora" })
    #expect(aurora.label == "pi on harus-mini")
    #expect(aurora.branch == "feat/collector-fix")
    #expect(aurora.working == true)
    let borealis = try #require(rows.first { $0.sessionID == "s-borealis" })
    #expect(borealis.branch == "main")
    #expect(borealis.working == false)
  }

  @Test func nullBranchAndUnnamedPaneRenderHonestly() throws {
    let document = """
      {"machine_id":"m-1","agents":[
        {"machine_id":"m-1","session_id":"w1:p43","label":"pi","active_branch":null,"working":false},
        {"machine_id":"m-1","session_id":"s-aurora","label":"pi on harus-mini","active_branch":"feat/x","working":true}
      ]}
      """.data(using: .utf8)!
    let (_, rows) = try DaemonWire.rowsFromFixture(document)
    let unnamed = try #require(rows.first { $0.sessionID == "w1:p43" })
    #expect(unnamed.branch == nil, "null branch means the UI shows 'No branch'")
    #expect(rows.first { $0.sessionID == "s-aurora" }?.branch == "feat/x")
  }

  @Test func daemonAgentsEventAndFixtureProduceEquivalentRows() throws {
    let event = """
      {"event":"agents","machine_id":"11111111-1111-4111-8111-111111111111","agents":[
        {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"s-aurora",
         "pane_id":"w1:p5Y","label":"pi on harus-mini","agent":"pi","status":"working",
         "working":true,"cwd":"/tmp/x","active_branch":"feat/collector-fix","branch_source":"own"},
        {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"w1:p43",
         "pane_id":"w1:p43","label":"pi","agent":"pi","status":"idle",
         "working":false,"cwd":"/tmp/y","active_branch":null,"branch_source":"none"}
      ]}
      """.data(using: .utf8)!
    guard case .agents(_, let liveRows) = try DaemonWire.parseEvent(event) else {
      Issue.record("expected agents event")
      return
    }
    let fixture = try DaemonWire.rowsFromFixture(
      """
      {"machine_id":"11111111-1111-4111-8111-111111111111","agents":[
        {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"s-aurora",
         "label":"pi on harus-mini","active_branch":"feat/collector-fix","working":true},
        {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"w1:p43",
         "label":"pi","active_branch":null,"working":false}
      ]}
      """.data(using: .utf8)!)
    #expect(liveRows == fixture.rows, "fixture and live daemon shapes render identically")
  }
}

@Suite struct DaemonWireParsingTests {
  @Test func pairedEventParses() throws {
    let event = try DaemonWire.parseEvent(
      #"{"event":"paired","machine_id":"m-1","protocol":1}"#.data(using: .utf8)!)
    #expect(event == .paired(PairedMachine(machineID: "m-1")))
  }

  @Test func unauthorizedErrorMapsVisibly() {
    #expect(throws: DaemonClientError.unauthorized) {
      try DaemonWire.parseEvent(
        #"{"event":"error","code":"unauthorized","message":"pairing token rejected"}"#
          .data(using: .utf8)!)
    }
  }

  @Test func unknownEventIsProtocolError() {
    #expect(throws: DaemonClientError.protocolError("unknown event nope")) {
      try DaemonWire.parseEvent(#"{"event":"nope"}"#.data(using: .utf8)!)
    }
  }

  @Test func requestEncodingAddsNewline() throws {
    let payload = try DaemonWire.encodeRequest(["op": "pair", "token": "t"])
    #expect(payload.last == 0x0A)
    #expect(String(data: payload.dropLast(), encoding: .utf8)?.contains("\"pair\"") == true)
  }
}
