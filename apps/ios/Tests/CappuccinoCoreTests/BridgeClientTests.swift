import Foundation
import Synchronization
import Testing

@testable import CappuccinoCore

/// Hermetic BridgeClient tests over a URLProtocol stub: the bridge's JSON
/// shapes must map to the same AgentRow values the bridge wire and the shared
/// fixture produce (fixture/live equivalence, issue #16).

/// Bridge-shaped superset of fixtures/agents-list.json (adds pane_id and
/// branch_source; identical machine/agent/branch values).
private let agentsEventJSON = """
  {"event":"agents","machine_id":"11111111-1111-4111-8111-111111111111","agents":[
    {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"s-aurora","pane_id":"w1:a",
     "label":"pi on harus-mini","agent":"pi","status":"working","working":true,
     "cwd":"/tmp/a","active_branch":"feat/collector-fix","branch_source":"own"},
    {"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"s-borealis","pane_id":"w1:b",
     "label":"pi on harus-mini","agent":"pi","status":"idle","working":false,
     "cwd":"/tmp/b","active_branch":"main","branch_source":"own"}
  ]}
  """

final class StubURLProtocol: URLProtocol {
  nonisolated(unsafe) static var handler: (@Sendable (URLRequest) -> (Int, Data))?

  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

  override func startLoading() {
    guard let handler = Self.handler, let url = request.url else { return }
    let (status, data) = handler(request)
    let response = HTTPURLResponse(
      url: url, statusCode: status, httpVersion: nil,
      headerFields: ["Content-Type": "application/json"])!
    client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
    client?.urlProtocol(self, didLoad: data)
    client?.urlProtocolDidFinishLoading(self)
  }

  override func stopLoading() {}
}

@Suite(.serialized) struct BridgeClientTests {
  @Test func recoveryBindingUsesFreshMachineAndNativeCatalogIdentity() async throws {
    let machine = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
    let base = URL(string: "http://127.0.0.1:7392")!
    let agent = AgentRow(
      machineID: machine.uuidString, sessionID: "same-locator", label: "pi",
      branch: nil, working: false, paneID: "pane-1", nativeSessionRef: "pi:original")
    let cases: [(String?, String, Bool)] = [
      ("pi:original", "pane-1", true), ("pi:replaced", "pane-1", false),
      (nil, "pane-1", false), ("pi:original", "reused-pane", false),
    ]
    for (native, pane, accepted) in cases {
      let reads = Mutex<[String]>([])
      let reference = native.map { "\"\($0)\"" } ?? "null"
      let payload =
        #"{"event":"agents","machine_id":"11111111-1111-4111-8111-111111111111","agents":[{"machine_id":"11111111-1111-4111-8111-111111111111","session_id":"same-locator","pane_id":"\#(pane)","native_session_ref":\#(reference),"agent":"pi"}]}"#
      StubURLProtocol.handler = { request in
        let path = request.url!.path
        reads.withLock { $0.append(path) }
        if path == "/api/session" {
          return (
            200,
            Data(
              #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":1,"plugin":"cappuccino-bridge"}"#
                .utf8)
          )
        }
        #expect(path == "/api/agents")
        return (200, Data(payload.utf8))
      }
      #expect(
        try await stubbedClient().verifyBinding(base: base, machineID: machine, agent: agent)
          == accepted)
      #expect(reads.withLock { $0 } == ["/api/session", "/api/agents"])
    }
    StubURLProtocol.handler = { _ in
      Issue.record("Missing native identity must not trigger a guessed binding")
      return (500, Data())
    }
    let legacy = AgentRow(
      machineID: machine.uuidString, sessionID: "same-locator", label: "pi", branch: nil,
      working: false)
    #expect(
      try await stubbedClient().verifyBinding(base: base, machineID: machine, agent: legacy)
        == false)
  }

  let binding = SessionBinding(
    machineID: "6f1c2d3e-4a5b-4c6d-8e7f-001122334455", paneID: "w1:p3",
    nativeSessionRef: "pi:native-1")

  private func stubbedClient() -> BridgeClient {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubURLProtocol.self]
    return BridgeClient(port: 7392, session: URLSession(configuration: configuration))
  }

  @Test func pairReturnsMachineFromSessionEndpoint() async throws {
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/session")
      #expect(request.value(forHTTPHeaderField: "Authorization") == nil)
      return (
        200,
        #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":1,"plugin":"cappuccino-bridge"}"#
          .data(using: .utf8)!
      )
    }
    let machine = try await stubbedClient().pair(
      baseURL: URL(string: "http://127.0.0.1:7392")!)
    #expect(machine.machineID == "11111111-1111-4111-8111-111111111111")
  }

  @Test func rejectsInvalidPairingAndUnexpectedMachineIdentity() async {
    let base = URL(string: "https://machine.example")!
    for payload in [
      #"{"event":"paired","machine_id":"invalid","protocol":1,"plugin":"cappuccino-bridge"}"#,
      #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":2,"plugin":"cappuccino-bridge"}"#,
      #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":true,"plugin":"cappuccino-bridge"}"#,
      #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":1,"plugin":"wrong"}"#,
    ] {
      StubURLProtocol.handler = { _ in (200, Data(payload.utf8)) }
      await #expect(throws: BridgeError.self) {
        try await stubbedClient().verifyMachine(baseURL: base)
      }
    }
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(
          #"{"event":"paired","machine_id":"11111111-1111-4111-8111-111111111111","protocol":1,"plugin":"cappuccino-bridge"}"#
            .utf8)
      )
    }
    await #expect(throws: BridgeError.self) {
      try await stubbedClient().verifyMachine(
        baseURL: base, expectedID: UUID(uuidString: "22222222-2222-4222-8222-222222222222")!)
    }
  }

  @Test func rejectsForeignAgentAndMissingLocator() async {
    let base = URL(string: "https://machine.example")!
    for row in [
      #"{"machine_id":"22222222-2222-4222-8222-222222222222","session_id":"same-locator"}"#,
      #"{"machine_id":"11111111-1111-4111-8111-111111111111","session_id":" "}"#,
    ] {
      let payload =
        "{\"event\":\"agents\",\"machine_id\":\"11111111-1111-4111-8111-111111111111\",\"agents\":[\(row)]}"
      StubURLProtocol.handler = { _ in (200, Data(payload.utf8)) }
      await #expect(throws: BridgeError.self) {
        try await stubbedClient().listAgents(baseURL: base)
      }
    }
    StubURLProtocol.handler = { _ in (200, Data(agentsEventJSON.utf8)) }
    await #expect(throws: BridgeError.self) {
      try await stubbedClient().listAgents(
        baseURL: base, expectedMachineID: UUID(uuidString: "22222222-2222-4222-8222-222222222222")!)
    }
  }

  @Test func unauthorizedMapsVisibly() async {
    StubURLProtocol.handler = { _ in
      (
        401,
        #"{"event":"error","code":"unauthorized","message":"pairing token rejected"}"#.data(
          using: .utf8)!
      )
    }
    do {
      _ = try await stubbedClient().pair(baseURL: URL(string: "http://127.0.0.1:7392")!)
      Issue.record("pairing must fail visibly")
    } catch let error as BridgeError {
      #expect(error == .unauthorized)
    } catch {
      Issue.record("unexpected error \(error)")
    }
  }

  @Test func bridgeAgentsMatchFixtureRows() async throws {
    let fixtureURL = URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .deletingLastPathComponent()
      .appendingPathComponent("fixtures/agents-list.json")
    let fixture =
      try JSONSerialization.jsonObject(with: Data(contentsOf: fixtureURL)) as? [String: Any]
    let fixtureRows = (fixture?["agents"] as? [[String: Any]] ?? []).map { row in
      AgentRow(
        machineID: row["machine_id"] as? String ?? "",
        sessionID: row["session_id"] as? String ?? "",
        label: row["label"] as? String ?? "", branch: row["active_branch"] as? String,
        working: row["working"] as? Bool ?? false)
    }

    // The bridge serves the same documents the fixture does; this response is
    // the bridge-shaped superset (pane_id, branch_source) of the fixture.
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(agentsEventJSON.utf8)
      )
    }
    let rows = try await stubbedClient().listAgents(baseURL: URL(string: "http://127.0.0.1:7392")!)
    // `provider` is bridge-only metadata; the fixture's rendered fields must match.
    func rendered(_ row: AgentRow) -> String {
      "\(row.machineID)|\(row.sessionID)|\(row.label)|\(row.branch ?? "")|\(row.working)"
    }
    #expect(
      rows.map(rendered) == fixtureRows.map(rendered),
      "bridge transports render the fixture identically")
    // Herdr's own status and the working folder reach the rows (blocked is not idle).
    #expect(rows.map(\.status) == ["working", "idle"])
    #expect(rows.map(\.cwd) == ["/tmp/a", "/tmp/b"])
  }

  @Test func oversizedResponsesAreRefused() async {
    // Valid JSON, but past the 2 MB cap: refused, as Android refuses it.
    let padding = String(repeating: "x", count: maxHTTPBodyBytes)
    StubURLProtocol.handler = { _ in (200, Data(#"{"pad":"\#(padding)"}"#.utf8)) }
    await #expect(throws: BridgeError.protocolError("bridge response is larger than 2 MB")) {
      try await stubbedClient().listAgents(baseURL: URL(string: "http://127.0.0.1:7392")!)
    }
  }

  @Test func httpErrorSurfacesAsProtocolError() async {
    StubURLProtocol.handler = { _ in
      (
        502,
        #"{"event":"error","code":"herdr_unreachable","message":"herdr: cannot connect"}"#
          .data(using: .utf8)!
      )
    }
    do {
      _ = try await stubbedClient().listAgents(baseURL: URL(string: "http://127.0.0.1:7392")!)
      Issue.record("expected an error")
    } catch let error as BridgeError {
      #expect(error.message.contains("cannot connect"))
    } catch {
      Issue.record("unexpected error \(error)")
    }
  }

  @Test func fetchConversationReturnsTurns() async throws {
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/s-aurora/conversation")
      return (
        200,
        #"{"session_id":"s-aurora","source":"canonical_log","turns":[{"id":"t1","role":"user","text":"hello"}]}"#
          .data(using: .utf8)!
      )
    }
    let result = try await stubbedClient().fetchConversation(
      base: URL(string: "http://127.0.0.1:7392")!, session: "s-aurora")
    #expect(result["session_id"] as? String == "s-aurora")
    #expect((result["turns"] as? [[String: Any]])?.count == 1)
  }

  @Test func submitPromptSendsJsonBody() async throws {
    let base = URL(string: "http://127.0.0.1:7392")!
    let recorder = BodyRecorder()
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/s-aurora/prompt")
      #expect(request.httpMethod == "POST")
      recorder.bodies.append(requestBody(request))
      return (200, #"{"status":"delivered"}"#.data(using: .utf8)!)
    }
    try await stubbedClient().submitPrompt(
      base: base, session: "s-aurora", binding: binding, text: "test input")
    try await stubbedClient().submitPrompt(
      base: base, session: "s-aurora", binding: binding, text: "/new", confirmed: true)
    let unconfirmed = try JSONSerialization.jsonObject(with: recorder.bodies[0]) as? [String: Any]
    let confirmed = try JSONSerialization.jsonObject(with: recorder.bodies[1]) as? [String: Any]
    #expect(unconfirmed?["text"] as? String == "test input")
    #expect(unconfirmed?["confirmed"] == nil)
    #expect(confirmed?["text"] as? String == "/new")
    #expect(confirmed?["confirmed"] as? Bool == true)
    for body in [unconfirmed, confirmed] {
      let expected = body?["expected_session"] as? [String: String]
      #expect(
        expected == [
          "machine_id": "6f1c2d3e-4a5b-4c6d-8e7f-001122334455", "pane_id": "w1:p3",
          "native_session_ref": "pi:native-1",
        ])
    }
  }

  @Test func sessionBindingNeedsAPaneAndANativeRef() {
    let machine = "6f1c2d3e-4a5b-4c6d-8e7f-001122334455"
    func row(pane: String?, native: String?) -> AgentRow {
      AgentRow(
        machineID: machine, sessionID: "s-1", label: "pi", branch: nil, working: false,
        paneID: pane, nativeSessionRef: native)
    }
    #expect(SessionBinding(row(pane: "w1:p3", native: "pi:native-1")) == binding)
    #expect(SessionBinding(row(pane: nil, native: "pi:native-1")) == nil)
    #expect(SessionBinding(row(pane: "", native: "pi:native-1")) == nil)
    #expect(SessionBinding(row(pane: "w1:p3", native: nil)) == nil)
    #expect(SessionBinding(row(pane: "w1:p3", native: "")) == nil)
  }

  @Test func answerPromptSendsSelection() async throws {
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/s-aurora/prompt")
      #expect(request.httpMethod == "POST")
      return (200, #"{"status":"delivered"}"#.data(using: .utf8)!)
    }
    try await stubbedClient().answerPrompt(
      base: URL(string: "http://127.0.0.1:7392")!,
      session: "s-aurora",
      binding: binding,
      promptID: "p123",
      optionIndex: 0,
      optionID: "opt-1",
      action: "select_option"
    )
  }
}
