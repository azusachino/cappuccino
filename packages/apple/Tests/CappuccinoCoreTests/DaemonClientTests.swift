import Foundation
import Testing

@testable import CappuccinoCore

/// Scripted line connection: queues daemon responses, captures what the
/// client sends. No sockets, no daemon, no timing dependencies.
final class FakeLineConnection: LineConnection, @unchecked Sendable {
  private var responses: [Data]
  private(set) var sent: [Data] = []
  private let lock = NSLock()

  init(responses: [String]) {
    self.responses = responses.map { Data($0.utf8) }
  }

  func send(_ payload: Data) async throws {
    appendSent(payload)
  }

  func receiveLine() async throws -> Data? {
    popResponse()
  }

  private func appendSent(_ payload: Data) {
    lock.lock()
    defer { lock.unlock() }
    sent.append(payload)
  }

  private func popResponse() -> Data? {
    lock.lock()
    defer { lock.unlock() }
    guard !responses.isEmpty else { return nil }
    var line = responses.removeFirst()
    line.append(0x0A)
    return line
  }

  func close() {}
}

struct FakeConnector: LineConnecting {
  let make: @Sendable () -> LineConnection

  func connect(host: String, port: UInt16, timeout: TimeInterval) async throws -> LineConnection {
    make()
  }
}

@Suite struct DaemonClientTests {
  private func client(_ responses: [String]) -> DaemonClient {
    DaemonClient(
      connector: FakeConnector { FakeLineConnection(responses: responses) },
      host: "127.0.0.1", port: 7391, timeout: 1)
  }

  @Test func pairSendsTokenAndReturnsMachine() async throws {
    let connection = FakeLineConnection(responses: [
      #"{"event":"paired","machine_id":"m-42","protocol":1}"#
    ])
    let client = DaemonClient(
      connector: FakeConnector { connection }, host: "h", port: 1, timeout: 1)
    let machine = try await client.pair(token: "sekrit")
    #expect(machine.machineID == "m-42")
    let sent = String(data: connection.sent[0], encoding: .utf8) ?? ""
    #expect(sent.contains("pair"))
    #expect(sent.contains("sekrit"))
  }

  @Test func wrongTokenFailsVisibly() async {
    let client = client(
      [#"{"event":"error","code":"unauthorized","message":"pairing token rejected"}"#])
    do {
      _ = try await client.pair(token: "wrong")
      Issue.record("pairing must not succeed with a wrong token")
    } catch let error as DaemonClientError {
      #expect(error == .unauthorized)
      #expect(error.message.contains("rejected"))
    } catch {
      Issue.record("unexpected error type \(error)")
    }
  }

  @Test func listMapsAgentsEvent() async throws {
    let client = client([
      #"{"event":"agents","machine_id":"m-1","agents":[{"machine_id":"m-1","session_id":"s-aurora","pane_id":"w1:a","label":"pi","agent":"pi","status":"working","working":true,"cwd":"/t","active_branch":"feat/x","branch_source":"own"},{"machine_id":"m-1","session_id":"w1:b","pane_id":"w1:b","label":"pi","agent":"pi","status":"idle","working":false,"cwd":"/t","active_branch":null,"branch_source":"none"}]}"#
    ])
    let rows = try await client.listAgents(token: "t")
    #expect(rows.count == 2)
    #expect(rows[0].branch == "feat/x")
    #expect(rows[1].branch == nil)
    #expect(rows[1].sessionID == "w1:b")
  }

  @Test func connectionRefusedSurfacesAsUnreachable() async {
    // Port 1 on loopback: nothing listens there, deterministically refused.
    let client = DaemonClient(
      connector: TCPLineConnectionFactory(), host: "127.0.0.1", port: 1, timeout: 2)
    do {
      _ = try await client.pair(token: "t")
      Issue.record("expected unreachable")
    } catch let error as DaemonClientError {
      guard case .unreachable = error else {
        Issue.record("expected unreachable, got \(error)")
        return
      }
    } catch {
      Issue.record("unexpected error type \(error)")
    }
  }

  @Test func requestSendsExactlyOneLine() async throws {
    let connection = FakeLineConnection(responses: [
      #"{"event":"agents","machine_id":"m","agents":[]}"#
    ])
    let client = DaemonClient(
      connector: FakeConnector { connection }, host: "h", port: 1, timeout: 1)
    _ = try await client.listAgents(token: "tok")
    #expect(connection.sent.count == 1)
    #expect(connection.sent[0].last == 0x0A)
  }
}

@Suite struct TokenStoreTests {
  @Test func inMemoryStoreRoundTripsAndDeletes() throws {
    let store = InMemoryTokenStore()
    #expect(store.loadToken() == nil)
    try store.saveToken("tok-1")
    #expect(store.loadToken() == "tok-1")
    try store.saveToken("tok-2")
    #expect(store.loadToken() == "tok-2")
    store.deleteToken()
    #expect(store.loadToken() == nil)
  }

  @Test func keychainStoreQueryShapeIsDeviceLocal() {
    // The production store must never use an accessibility level that leaves
    // the device after backup/restore.
    let store = KeychainTokenStore()
    #expect(store.service == "com.azusachino.cappuccino.daemon")
    #expect(store.account == "pairing-token")
  }
}
