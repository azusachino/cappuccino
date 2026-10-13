import Foundation

/// A canned bridge for UI journeys, selected by the `-cappuccino-fixture` launch argument.
/// It answers from fixed data with no network, so journeys are hermetic.
public struct FixtureBridge: Sendable {
  public static let machineID = UUID(uuidString: "11111111-1111-4111-8111-111111111111")!
  public static let sessionID = "s-fixture"

  public init() {}

  /// A machines store holding one fixture machine, served by this bridge.
  @MainActor public static func machines() -> MachineConnections {
    let suite = "cappuccino-fixture"
    let defaults = UserDefaults(suiteName: suite)!
    defaults.removePersistentDomain(forName: suite)
    let store = BridgeProfileStore(defaults: defaults)
    // A constant endpoint: failing to build it would be a programming error, not input.
    let profile = try! BridgeProfile(
      machineID: machineID, endpoint: URL(string: "https://fixture.example")!, name: "Fixture Mac")
    try! store.save([profile])
    return MachineConnections(store: store, client: FixtureBridge())
  }

  public static let clientFactory: @Sendable () -> any AgentSessionServing = { FixtureBridge() }

  static let agent = AgentRow(
    machineID: machineID.uuidString, sessionID: sessionID, label: "pi on fixture", branch: "main",
    working: false, provider: "pi")

  static let turns: [ConversationTurn] = [
    ConversationTurn(
      id: "t-user", role: "user", timestamp: nil, text: "Run the build.", parts: []),
    ConversationTurn(
      id: "t-agent", role: "agent", timestamp: nil, text: "The build passed.",
      parts: [
        .toolCall(
          ToolCall(
            name: "bash", input: #"{"command":"make check"}"#, output: "ok", isError: false,
            startedAt: nil, finishedAt: nil, images: nil))
      ]),
  ]
}

extension FixtureBridge: BridgeMachineServing {
  public func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> VerifiedMachine {
    VerifiedMachine(
      machine: BridgeMachine(id: Self.machineID, name: "Fixture Mac", version: "0.1.0"),
      details: BridgeDetails(
        machineID: Self.machineID, machineName: "Fixture Mac", version: "0.1.0",
        protocolVersion: 1,
        capabilities: ["agent_control": true, "prompt_input": true, "prompt_answers": true]))
  }

  public func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] {
    [Self.agent]
  }
}

extension FixtureBridge: AgentSessionServing {
  public func conversationSnapshot(base: URL, session: String, agent: String?) async throws
    -> ConversationSnapshot
  {
    ConversationSnapshot(
      turns: Self.turns, source: "canonical_log", conversationID: nil,
      machineID: Self.machineID, stats: nil)
  }

  public func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error> {
    AsyncThrowingStream { continuation in
      continuation.yield(.open(sessionID: Self.sessionID, generation: 0))
      continuation.yield(.agentStatus(sessionID: Self.sessionID, state: "idle", detail: nil))
      continuation.finish()
    }
  }

  public func controlAgent(
    base: URL, session: String, action: String, capabilities: [String: Bool]?
  )
    async throws
  {}

  public func submitPrompt(base: URL, session: String, text: String) async throws {}

  public func answerPrompt(
    base: URL, session: String, promptID: String, optionIndex: Int?, optionID: String?,
    action: String?
  ) async throws {}

  public func answerQuestions(
    base: URL, session: String, promptID: String, answers: [QuestionAnswer]
  )
    async throws
  {}
}
