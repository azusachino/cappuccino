import Foundation
import Observation

/// The calls the app model makes to one bridge. Each request gets a fresh client from the
/// model's factory, as Android's ConnectedViewModel does.
public protocol AgentSessionServing: Sendable {
  func conversationSnapshot(base: URL, session: String, agent: String?) async throws
    -> ConversationSnapshot
  func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error>
  func controlAgent(base: URL, session: String, action: String, capabilities: [String: Bool]?)
    async throws
  func submitPrompt(base: URL, session: String, text: String) async throws
  func answerPrompt(
    base: URL, session: String, promptID: String, optionIndex: Int?, optionID: String?,
    action: String?
  ) async throws
  func answerQuestions(base: URL, session: String, promptID: String, answers: [QuestionAnswer])
    async throws
}

extension BridgeClient: AgentSessionServing {
  public func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error> {
    agentEvents(base: base, session: session)
  }
}

/// The selected agent: its conversation, live stream and owner actions. Every request is
/// bound to the selection epoch, so results for an agent the owner has left are dropped.
@MainActor @Observable
public final class SessionModel {
  public private(set) var selectedProfileID: UUID?
  public private(set) var selectedAgent: AgentRow?
  public private(set) var turns: [ConversationTurn] = []
  public private(set) var omittedTurns = 0
  public private(set) var omittedTools = 0
  public private(set) var conversationSource: String?
  public private(set) var conversationID: String?
  public private(set) var stats: SessionStats?
  public private(set) var stream = StreamState()
  /// Why the stream stopped. The machine's connection itself is not changed.
  public private(set) var streamError: String?
  /// Why the last Stop, New session, prompt or answer failed.
  public private(set) var actionError: String?

  @ObservationIgnored private let machines: MachineConnections
  @ObservationIgnored private let makeClient: @Sendable () -> any AgentSessionServing
  @ObservationIgnored private var epoch = 0
  @ObservationIgnored private var streamTask: Task<Void, Never>?
  @ObservationIgnored private var conversationTask: Task<Void, Never>?
  @ObservationIgnored private var conversationRequested = false

  public init(
    machines: MachineConnections,
    makeClient: @escaping @Sendable () -> any AgentSessionServing = { BridgeClient() }
  ) {
    self.machines = machines
    self.makeClient = makeClient
  }

  /// The owner's profile for the selection, if it still exists.
  public var selectedProfile: BridgeProfile? {
    machines.profiles.first { $0.id == selectedProfileID }
  }

  /// The machine's advertisements from its last verified handshake.
  public var capabilities: [String: Bool]? {
    guard let id = selectedProfileID else { return nil }
    return machines.connections[id]?.details?.capabilities
  }

  /// Stop and New session are offered only when the bridge advertises agent control.
  public var canControl: Bool { capabilities?["agent_control"] == true }

  /// Leaves the agent: its stream and conversation stop, its results are dropped, and the
  /// transcript is cleared. Switching agents bumps the same epoch.
  public func clearSelection(profileID: UUID? = nil) {
    epoch += 1
    streamTask?.cancel()
    conversationTask?.cancel()
    streamTask = nil
    conversationTask = nil
    conversationRequested = false
    selectedProfileID = profileID ?? selectedProfileID
    selectedAgent = nil
    turns = []
    omittedTurns = 0
    omittedTools = 0
    conversationSource = nil
    conversationID = nil
    stats = nil
    stream = StreamState()
    streamError = nil
    actionError = nil
  }

  /// Select an agent on a machine. A foreign machine's agent is refused.
  public func select(profileID: UUID, agent: AgentRow?) {
    clearSelection(profileID: profileID)
    guard let agent, let profile = selectedProfile else { return }
    guard UUID(uuidString: agent.machineID) == profile.machineID else {
      streamError = "Agent belongs to a different machine"
      return
    }
    selectedAgent = agent
    startStream(token: epoch)
    requestConversation()
  }

  /// Re-reads the conversation. Requests that arrive while one is running are coalesced into one more pass.
  public func requestConversation() {
    conversationRequested = true
    guard conversationTask == nil else { return }
    let token = epoch
    conversationTask = Task { [weak self] in
      while let model = self, model.conversationRequested, model.epoch == token {
        model.conversationRequested = false
        await model.loadConversation(token: token)
      }
      if let model = self, model.epoch == token { model.conversationTask = nil }
    }
  }

  /// Waits for the current stream to end. Tests use this to observe the reducer deterministically.
  func awaitStreamForTesting() async {
    await streamTask?.value
  }

  private func loadConversation(token: Int) async {
    guard let agent = selectedAgent, let profile = selectedProfile else { return }
    do {
      let snapshot = try await makeClient().conversationSnapshot(
        base: profile.endpoint, session: agent.sessionID, agent: agent.provider)
      if let machine = snapshot.machineID, machine != profile.machineID {
        throw DaemonClientError.protocolError("Conversation machine identity changed")
      }
      guard token == epoch else { return }
      // Terminal snapshots arrive through the stream; only canonical history is shown here.
      let recent = boundedConversation(
        snapshot.source == "canonical_log" ? snapshot.turns : [])
      turns = recent.turns
      omittedTurns = recent.omittedMessages
      omittedTools = recent.omittedTools
      conversationSource = snapshot.source
      conversationID = snapshot.conversationID
      stats = snapshot.stats
    } catch {
      // Keep the turns already shown. A failed first load falls back to scrollback.
      guard token == epoch, !Task.isCancelled else { return }
      if conversationSource == nil { conversationSource = "terminal_scrollback" }
    }
  }

  private func startStream(token: Int) {
    streamTask?.cancel()
    streamTask = Task { [weak self] in
      await self?.runStream(token: token)
    }
  }

  private func runStream(token: Int) async {
    var budget = StreamRetryBudget()
    while token == epoch, !Task.isCancelled {
      guard let agent = selectedAgent, let profile = selectedProfile else { return }
      do {
        let events = makeClient().events(base: profile.endpoint, session: agent.sessionID)
        for try await event in events {
          guard token == epoch else { return }
          stream = try reduceStream(stream, event, selectedSession: agent.sessionID)
          switch event {
          case .error(let code, let message):
            streamError = "\(code): \(message)"
            return
          case .open, .entries, .reset, .agentStatus:
            requestConversation()
          case .promptRequest, .promptResolved:
            break
          }
        }
        return
      } catch {
        guard token == epoch, !Task.isCancelled else { return }
        // A protocol violation is final; transport failures retry within the budget.
        if case DaemonClientError.protocolError(let detail)? = error as? DaemonClientError {
          streamError = detail
          return
        }
        guard let delay = budget.nextDelayMillis() else {
          streamError = (error as? DaemonClientError)?.message ?? error.localizedDescription
          return
        }
        try? await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000)
      }
    }
  }

  /// Sends a prompt to the selected agent.
  public func sendPrompt(_ text: String) async {
    await perform(
      "Failed to submit prompt", supported: capabilities?["prompt_input"] != false,
      unsupported: "Prompt input unsupported by this bridge"
    ) { client, base, session in
      try await client.submitPrompt(base: base, session: session, text: text)
    }
  }

  /// Stop (`interrupt`) or New session (`new_session`), only when the bridge advertises control.
  public func control(_ action: String) async {
    guard canControl else { return }
    let advertised = capabilities
    await perform("Failed to control the agent") { client, base, session in
      try await client.controlAgent(
        base: base, session: session, action: action, capabilities: advertised)
    }
  }

  /// Answers a screen prompt by option index, id or action.
  public func answerPrompt(
    _ promptID: String, optionIndex: Int? = nil, optionID: String? = nil, action: String? = nil
  ) async {
    await perform(
      "Failed to answer prompt", supported: capabilities?["prompt_answers"] != false,
      unsupported: "Prompt answers unsupported by this bridge"
    ) { client, base, session in
      try await client.answerPrompt(
        base: base, session: session, promptID: promptID, optionIndex: optionIndex,
        optionID: optionID, action: action)
    }
  }

  /// Answers every question of an AskUserQuestion card in one submission.
  public func answerQuestions(_ promptID: String, _ answers: [QuestionAnswer]) async {
    await perform("Failed to answer the question") { client, base, session in
      try await client.answerQuestions(
        base: base, session: session, promptID: promptID, answers: answers)
    }
  }

  /// The one path for owner actions. A failure sets `actionError` and leaves the connection
  /// alone; a result for an agent the owner has since left is dropped.
  private func perform(
    _ failure: String,
    supported: Bool = true,
    unsupported: String = "",
    _ send: @escaping @Sendable (any AgentSessionServing, URL, String) async throws -> Void
  ) async {
    guard let agent = selectedAgent, let profile = selectedProfile else { return }
    let token = epoch
    actionError = nil
    guard supported else {
      actionError = unsupported
      return
    }
    do {
      try await send(makeClient(), profile.endpoint, agent.sessionID)
    } catch {
      guard token == epoch, !(error is CancellationError) else { return }
      let reason = OwnerMessage.text(for: error)
      actionError = reason.isEmpty ? failure : reason
    }
  }
}
