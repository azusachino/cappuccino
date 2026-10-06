import Foundation
import Testing

@testable import CappuccinoCore

/// Hermetic BridgeClient tests over a URLProtocol stub: the bridge's JSON
/// shapes must map to the same AgentRow values the daemon wire and the shared
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
        #"{"event":"paired","machine_id":"bridge-m-1","protocol":1,"plugin":"azusachino.cappuccino-bridge"}"#
          .data(using: .utf8)!
      )
    }
    let machine = try await stubbedClient().pair(
      baseURL: URL(string: "http://127.0.0.1:7392")!)
    #expect(machine.machineID == "bridge-m-1")
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
    } catch let error as DaemonClientError {
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
    let (_, fixtureRows) = try DaemonWire.rowsFromFixture(try Data(contentsOf: fixtureURL))

    // The bridge serves the same documents the daemon does; this response is
    // the bridge-shaped superset (pane_id, branch_source) of the fixture.
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(agentsEventJSON.utf8)
      )
    }
    let rows = try await stubbedClient().listAgents(baseURL: URL(string: "http://127.0.0.1:7392")!)
    #expect(rows == fixtureRows, "bridge and daemon transports render the fixture identically")
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
    } catch let error as DaemonClientError {
      #expect(error.message.contains("cannot connect"))
    } catch {
      Issue.record("unexpected error \(error)")
    }
  }
}
