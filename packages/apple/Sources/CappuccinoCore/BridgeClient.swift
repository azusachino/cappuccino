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

  /// Durable reload probe for session resets: transport/HTTP failures throw
  /// (the caller keeps history and shows the banner); a clean
  /// `available: false` answer is a normal no-op (this machine has no
  /// canonical pi transcript to reload from).
  public func fetchDurableReload(base url: URL, session: String) async throws {
    let endpoint = url.appendingPathComponent("/api/transcript?session=\(session)")
    _ = try await get(endpoint)
  }

  /// Fetches structured conversation turns for an agent session
  public func fetchConversation(base url: URL, session: String) async throws -> [String: Any] {
    let endpoint = url.appendingPathComponent("/api/agents/\(session)/conversation")
    return try await get(endpoint)
  }

  /// Sends a user prompt to an agent session via Bridge V2
  public func submitPrompt(base url: URL, session: String, text: String) async throws {
    let endpoint = url.appendingPathComponent("/api/agents/\(session)/prompt")
    let body: [String: Any] = ["type": "prompt", "text": text]
    _ = try await post(endpoint, body: body)
  }

  /// Answers an interactive prompt card (tool approval or ask_question)
  public func answerPrompt(
    base url: URL,
    session: String,
    promptID: String,
    optionIndex: Int? = nil,
    optionID: String? = nil,
    action: String? = nil
  ) async throws {
    let endpoint = url.appendingPathComponent("/api/agents/\(session)/prompt")
    var body: [String: Any] = [
      "type": "answer_prompt",
      "prompt_id": promptID,
    ]
    if let optionIndex { body["option_index"] = optionIndex }
    if let optionID { body["option_id"] = optionID }
    if let action { body["action"] = action }
    _ = try await post(endpoint, body: body)
  }

  private func post(_ url: URL, body: [String: Any]) async throws -> [String: Any] {
    var request = URLRequest(url: url)
    request.httpMethod = "POST"
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    request.timeoutInterval = timeout
    do {
      request.httpBody = try JSONSerialization.data(withJSONObject: body)
    } catch {
      throw DaemonClientError.protocolError("failed to serialize request body")
    }
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
    case 200, 201:
      return object
    case 401:
      throw DaemonClientError.unauthorized
    default:
      let code = object["code"] as? String ?? "http_\(http.statusCode)"
      let message = object["message"] as? String ?? code
      throw DaemonClientError.protocolError(message)
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
