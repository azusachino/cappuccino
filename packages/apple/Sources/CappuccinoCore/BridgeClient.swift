import Foundation

/// Client for the Cappuccino bridge plugin (`services/bridge`), implementing
/// the same `DaemonServing` seam as the reference daemon client. URLSession
/// for HTTP, bearer-token auth, typed errors mapped from the bridge's JSON
/// error envelope. Loopback by default; tailscale-serve exposure is a
/// deployment decision, not a client change.
public struct BridgeClient: DaemonServing {
  public let baseURL: URL
  public let timeout: TimeInterval
  /// Injectable so hermetic tests can stub the transport via URLProtocol.
  public let session: URLSession

  public init(
    host: String = "127.0.0.1", port: UInt16 = 7392, timeout: TimeInterval = 5,
    session: URLSession = .shared
  ) {
    self.baseURL = URL(string: "http://\(host):\(port)")!
    self.timeout = timeout
    self.session = session
  }

  public func pair(baseURL: URL) async throws -> PairedMachine {
    let event = try await get(baseURL.appendingPathComponent("/api/session"))
    guard let machineID = event["machine_id"] as? String, !machineID.isEmpty else {
      throw DaemonClientError.protocolError("paired event missing machine_id")
    }
    return PairedMachine(machineID: machineID)
  }

  public func listAgents(baseURL: URL) async throws -> [AgentRow] {
    let event = try await get(baseURL.appendingPathComponent("/api/agents"))
    guard let rawAgents = event["agents"] as? [[String: Any]] else {
      throw DaemonClientError.protocolError("agents event missing agents array")
    }
    return rawAgents.map { agent in
      AgentRow(
        machineID: agent["machine_id"] as? String ?? "",
        sessionID: agent["session_id"] as? String ?? agent["pane_id"] as? String ?? "",
        label: agent["label"] as? String ?? agent["agent"] as? String ?? "",
        branch: agent["active_branch"] as? String,
        working: agent["working"] as? Bool ?? false
      )
    }
  }

  private func get(_ url: URL) async throws -> [String: Any] {
    var request = URLRequest(url: url)
    request.timeoutInterval = timeout
    let (data, response): (Data, URLResponse)
    do {
      (data, response) = try await session.data(for: request)
    } catch {
      throw DaemonClientError.unreachable(error.localizedDescription)
    }
    guard let http = response as? HTTPURLResponse else {
      throw DaemonClientError.unreachable("response is not HTTP")
    }
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw DaemonClientError.protocolError("response is not a JSON object")
    }
    switch http.statusCode {
    case 200:
      return object
    case 401:
      throw DaemonClientError.unauthorized
    default:
      let code = object["code"] as? String ?? "http_\(http.statusCode)"
      let message = object["message"] as? String ?? code
      throw DaemonClientError.protocolError(message)
    }
  }
}
