import CoreFoundation
import Foundation

/// Client for the Cappuccino bridge plugin (`services/bridge`). URLSession
/// for HTTP, no application auth, typed errors mapped from the bridge's JSON
/// error envelope. Loopback by default; tailscale-serve exposure is a
/// deployment decision, not a client change.
public struct BridgeClient: Sendable {
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
    let verified = try await verifyMachine(baseURL: baseURL)
    return PairedMachine(machineID: verified.machine.id.uuidString.lowercased())
  }

  /// Verifies /api/session and keeps its advertisements, so a connection can gate its actions.
  public func verifyMachine(baseURL: URL, expectedID: UUID? = nil) async throws -> VerifiedMachine {
    let json = BridgeJSON(try await get(baseURL.appendingPathComponent("/api/session")))
    let details = try parseBridgeDetails(json)
    guard expectedID == nil || details.machineID == expectedID else {
      throw BridgeError.protocolError("Bridge machine identity changed")
    }
    return VerifiedMachine(
      machine: BridgeMachine(
        id: details.machineID, name: details.machineName, version: details.version),
      details: details)
  }

  public func listAgents(baseURL: URL) async throws -> [AgentRow] {
    try await listAgents(baseURL: baseURL, expectedMachineID: nil)
  }

  public func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] {
    let event = try await get(baseURL.appendingPathComponent("/api/agents"))
    guard event["event"] as? String == "agents",
      let rawMachineID = event["machine_id"] as? String,
      let machineID = UUID(uuidString: rawMachineID),
      expectedMachineID == nil || machineID == expectedMachineID,
      let rawAgents = event["agents"] as? [[String: Any]]
    else {
      throw BridgeError.protocolError("Invalid agent discovery identity")
    }
    return try rawAgents.map { agent in
      guard let owner = agent["machine_id"] as? String,
        UUID(uuidString: owner) == machineID,
        let locator = agent["session_id"] as? String ?? agent["pane_id"] as? String,
        !locator.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      else {
        throw BridgeError.protocolError(
          "Agent is missing its locator or belongs to another machine")
      }
      return AgentRow(
        machineID: owner,
        sessionID: locator,
        label: agent["label"] as? String ?? agent["agent"] as? String ?? "",
        branch: agent["active_branch"] as? String,
        working: agent["working"] as? Bool ?? false,
        provider: agent["agent"] as? String,
        status: agent["status"] as? String,
        cwd: agent["cwd"] as? String
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
    let endpoint = agentURL(url, session: session, "conversation")
    return try await get(endpoint)
  }

  /// Sends a user prompt to an agent session via Bridge V2
  public func submitPrompt(base url: URL, session: String, text: String) async throws {
    let endpoint = agentURL(url, session: session, "prompt")
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
    let endpoint = agentURL(url, session: session, "prompt")
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
      throw BridgeError.protocolError("failed to serialize request body")
    }
    let (data, response): (Data, URLResponse)
    do {
      (data, response) = try await session.data(for: request)
    } catch {
      throw BridgeError.unreachable(error.localizedDescription)
    }
    guard let http = response as? HTTPURLResponse else {
      throw BridgeError.unreachable("response is not HTTP")
    }
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw BridgeError.protocolError("response is not a JSON object")
    }
    switch http.statusCode {
    case 200, 201:
      return object
    case 401:
      throw BridgeError.unauthorized
    default:
      let code = object["code"] as? String ?? "http_\(http.statusCode)"
      let message = object["message"] as? String ?? code
      throw BridgeError.protocolError(message)
    }
  }

  private func get(_ url: URL) async throws -> [String: Any] {
    var request = URLRequest(url: url)
    request.timeoutInterval = timeout
    let (data, response): (Data, URLResponse)
    do {
      (data, response) = try await session.data(for: request)
    } catch {
      throw BridgeError.unreachable(error.localizedDescription)
    }
    guard let http = response as? HTTPURLResponse else {
      throw BridgeError.unreachable("response is not HTTP")
    }
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw BridgeError.protocolError("response is not a JSON object")
    }
    switch http.statusCode {
    case 200:
      return object
    case 401:
      throw BridgeError.unauthorized
    default:
      let code = object["code"] as? String ?? "http_\(http.statusCode)"
      let message = object["message"] as? String ?? code
      throw BridgeError.protocolError(message)
    }
  }
}

/// One conversation response: its turns plus the canonical identity and session stats
/// that Android's ConversationSnapshot carries.
public struct ConversationSnapshot: Equatable, Sendable {
  public let turns: [ConversationTurn]
  public let source: String
  public let conversationID: String?
  public let machineID: UUID?
  public let stats: SessionStats?
}

extension BridgeClient {
  /// Typed conversation fetch. Ownership and identity fail closed (BridgeClient.kt):
  /// the locator must echo back, and a canonical identity needs its canonical_log
  /// source, a machine and, when `agent` is known, that agent's provider prefix.
  public func conversationSnapshot(
    base url: URL, session: String, agent: String? = nil
  ) async throws -> ConversationSnapshot {
    let json = BridgeJSON(try await get(agentURL(url, session: session, "conversation")))
    guard try json.string("session_id") == session else {
      throw BridgeError.protocolError("Conversation belongs to a different locator")
    }
    let source = try json.string("source")
    let identity = try json.optionalString("conversation_id")
    let machineID = try json.optionalString("machine_id").map { raw in
      guard let id = UUID(uuidString: raw) else {
        throw BridgeError.protocolError("Invalid machine identity")
      }
      return id
    }
    if let identity {
      let canonical =
        agent.map { CanonicalConversationID.isCanonical(identity, agent: $0) }
        ?? CanonicalConversationID.isCanonical(identity)
      guard source == "canonical_log", machineID != nil, canonical else {
        throw BridgeError.protocolError("Invalid canonical conversation identity")
      }
    }
    let stats: SessionStats?
    if let raw = json.optionalObject("stats") {
      stats = SessionStats(
        model: try raw.optionalString("model"),
        thinkingLevel: try raw.optionalString("thinking_level"),
        contextTokens: try raw.optionalLong("context_tokens"),
        contextWindow: try raw.optionalLong("context_window"),
        cachePercent: try raw.optionalLong("cache_percent"))
    } else {
      stats = nil
    }
    return ConversationSnapshot(
      turns: try parseConversationTurns(json), source: source, conversationID: identity,
      machineID: machineID, stats: stats)
  }

  /// Owner control (`interrupt` or `new_session`). A bridge that advertises
  /// `agent_control: false` never receives it.
  public func controlAgent(
    base url: URL, session: String, action: String, capabilities: [String: Bool]? = nil
  ) async throws {
    guard capabilities?["agent_control"] != false else {
      throw BridgeError.protocolError("Agent controls unsupported by this bridge")
    }
    _ = try await post(
      agentURL(url, session: session, "control"), body: ["action": action])
  }

  /// Answers every question of an AskUserQuestion card in one structured submission.
  public func answerQuestions(
    base url: URL, session: String, promptID: String, answers: [QuestionAnswer]
  ) async throws {
    let rows: [[String: Any]] = answers.map { answer in
      var row: [String: Any] = ["selected": answer.selected]
      if let other = answer.other { row["other"] = other }
      return row
    }
    _ = try await post(
      agentURL(url, session: session, "prompt"),
      body: ["type": "answer_question", "prompt_id": promptID, "answers": rows])
  }

  /// Locators such as `w1:p3K` are one path component. Percent-encoding keeps a
  /// colon or reserved character from changing the route.
  func agentURL(_ base: URL, session: String, _ leaf: String) -> URL {
    let component =
      session.addingPercentEncoding(withAllowedCharacters: Self.locatorCharacters) ?? session
    return URL(string: "/api/agents/\(component)/\(leaf)", relativeTo: base)!.absoluteURL
  }

  private static let locatorCharacters = CharacterSet.alphanumerics.union(
    CharacterSet(charactersIn: "-._~"))
}
