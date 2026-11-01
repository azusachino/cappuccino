import Foundation
import Observation

/// The calls the app model makes to one bridge. Each request gets a fresh client from the
/// model's factory, as Android's ConnectedViewModel does.
public protocol AgentSessionServing: Sendable {
  func conversationSnapshot(base: URL, session: String, agent: String?) async throws
    -> ConversationSnapshot
  func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error>
  func verifyBinding(base: URL, machineID: UUID, agent: AgentRow) async throws -> Bool
  func controlAgent(base: URL, session: String, action: String, capabilities: [String: Bool]?)
    async throws
  func submitPrompt(base: URL, session: String, text: String, confirmed: Bool) async throws
  func answerPrompt(
    base: URL, session: String, promptID: String, optionIndex: Int?, optionID: String?,
    action: String?
  ) async throws
  func answerQuestions(base: URL, session: String, promptID: String, answers: [QuestionAnswer])
    async throws
}

extension AgentSessionServing {
  /// Clients without authoritative discovery cannot establish a recovery binding.
  public func verifyBinding(base: URL, machineID: UUID, agent: AgentRow) async throws -> Bool {
    false
  }
}

extension BridgeClient: AgentSessionServing {
  public func verifyBinding(base: URL, machineID: UUID, agent: AgentRow) async throws -> Bool {
    guard agent.nativeSessionRef != nil, UUID(uuidString: agent.machineID) == machineID else {
      return false
    }
    _ = try await verifyMachine(baseURL: base, expectedID: machineID)
    return try await listAgents(baseURL: base, expectedMachineID: machineID).contains {
      UUID(uuidString: $0.machineID) == machineID && $0.sessionID == agent.sessionID
        && $0.paneID == agent.paneID && $0.nativeSessionRef == agent.nativeSessionRef
    }
  }

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
  @ObservationIgnored private var foreground = true
  @ObservationIgnored private var streamTask: Task<Void, Never>?
  @ObservationIgnored private var conversationTask: Task<Void, Never>?
  @ObservationIgnored private var conversationRequested = false
  @ObservationIgnored var retryPause: @Sendable (Int) async throws -> Void = {
    try await Task.sleep(nanoseconds: UInt64($0) * 1_000_000)
  }

  @ObservationIgnored private let history: SavedConversationStore?

  /// `history` receives each canonical transcript for the History tab; nil keeps nothing.
  public init(
    machines: MachineConnections,
    makeClient: @escaping @Sendable () -> any AgentSessionServing = { BridgeClient() },
    history: SavedConversationStore? = nil
  ) {
    self.machines = machines
    self.makeClient = makeClient
    self.history = history
  }

  /// Where canonical transcripts are kept, for the conversation's history sheet.
  public var historyStore: SavedConversationStore? { history }

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
  public var isLive: Bool { stream.generation != nil && stream.agentStatus != nil }
  public var canControl: Bool { isLive && capabilities?["agent_control"] == true }

  static let reconnecting = "Reconnecting to the agent"
  /// The stream dropped and is being re-established; `streamError` holds no failure yet.
  public var isReconnecting: Bool { streamError == Self.reconnecting }
  /// The selected agent's first conversation read has not finished, successfully or not.
  public var isLoadingHistory: Bool { selectedAgent != nil && conversationSource == nil }

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

  /// Pausing discovery is not enough: discard live affordances and fence old work too.
  /// Foreground recovery must revalidate the original native binding before reattaching.
  public func setForeground(_ value: Bool) {
    guard value != foreground else { return }
    foreground = value
    if value {
      if selectedAgent != nil {
        streamError = Self.reconnecting
        startStream(token: epoch, recovering: true)
      }
    } else {
      epoch += 1
      streamTask?.cancel()
      streamTask = nil
      conversationTask?.cancel()
      conversationTask = nil
      conversationRequested = false
      invalidateLiveStream()
    }
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
    guard foreground else { return }
    conversationRequested = true
    guard conversationTask == nil else { return }
    let token = epoch
    conversationTask = Task { [weak self] in
      while !Task.isCancelled, let model = self, model.conversationRequested, model.epoch == token {
        model.conversationRequested = false
        await model.loadConversation(token: token)
      }
      if !Task.isCancelled, let model = self, model.epoch == token { model.conversationTask = nil }
    }
  }

  /// Waits for the current stream to end. Tests use this to observe the reducer deterministically.
  func awaitStreamForTesting() async {
    await streamTask?.value
  }

  func awaitConversationForTesting() async {
    await conversationTask?.value
  }

  private func applyConversation(_ snapshot: ConversationSnapshot) {
    let recent = boundedConversation(snapshot.source == "canonical_log" ? snapshot.turns : [])
    turns = recent.turns
    omittedTurns = recent.omittedMessages
    omittedTools = recent.omittedTools
    conversationSource = snapshot.source
    conversationID = snapshot.conversationID
    stats = snapshot.stats
    saveHistory(recent, snapshot)
  }

  /// Keeps the canonical transcript for History. Best-effort: a failed write never touches the
  /// live view, and the file write runs off the main actor.
  private func saveHistory(_ recent: RecentConversation, _ snapshot: ConversationSnapshot) {
    guard
      let history, snapshot.source == "canonical_log", let conversationID = snapshot.conversationID,
      let agent = selectedAgent, !recent.turns.isEmpty
    else { return }
    let saved = SavedConversation(
      agent: agent, conversationID: conversationID, savedAt: Date(), turns: recent.turns,
      omittedMessages: recent.omittedMessages, omittedTools: recent.omittedTools)
    Task.detached(priority: .utility) { try? history.save(saved) }
  }

  private func loadConversation(token: Int) async {
    guard let agent = selectedAgent, let profile = selectedProfile else { return }
    do {
      let client = makeClient()
      let snapshot = try await machines.request(profile) { base in
        try await client.conversationSnapshot(
          base: base, session: agent.sessionID, agent: agent.provider)
      }
      if let machine = snapshot.machineID, machine != profile.machineID {
        throw BridgeError.protocolError("Conversation machine identity changed")
      }
      guard token == epoch, !Task.isCancelled else { return }
      // Terminal snapshots arrive through the stream; only canonical history is shown here.
      applyConversation(snapshot)
    } catch {
      // Keep the turns already shown. A failed first load falls back to scrollback.
      guard token == epoch, !Task.isCancelled else { return }
      if conversationSource == nil { conversationSource = "terminal_scrollback" }
    }
  }

  /// A read-only recovery attempt; it never replays a prompt, answer or control operation.
  public func retryStream() {
    guard foreground, selectedAgent != nil else { return }
    invalidateLiveStream()
    streamError = Self.reconnecting
    startStream(token: epoch, recovering: true)
  }

  private func startStream(token: Int, recovering: Bool = false) {
    guard foreground else { return }
    streamTask?.cancel()
    streamTask = Task { [weak self] in
      await self?.runStream(token: token, recovering: recovering)
    }
  }

  private func runStream(token: Int, recovering: Bool) async {
    var budget = StreamRetryBudget()
    var attached = recovering
    while foreground, token == epoch, !Task.isCancelled {
      guard let agent = selectedAgent, let profile = selectedProfile else { return }
      invalidateLiveStream()
      do {
        let client = makeClient()
        if attached {
          guard UUID(uuidString: agent.machineID) == profile.machineID,
            agent.nativeSessionRef != nil,
            try await machines.request(
              profile,
              { base in
                try await client.verifyBinding(
                  base: base, machineID: profile.machineID, agent: agent)
              })
          else {
            throw BridgeError.protocolError(
              "Agent session identity is unavailable or replaced; reselect it")
          }
          guard token == epoch, !Task.isCancelled else { return }
          // Recovery is serialized: obsolete coalesced reads cannot unlock or overwrite this
          // attachment, even when their transport ignores cancellation.
          conversationTask?.cancel()
          conversationTask = nil
          conversationRequested = false
          let expectedConversation = conversationID
          let snapshot = try await machines.request(profile) { base in
            try await client.conversationSnapshot(
              base: base, session: agent.sessionID, agent: agent.provider)
          }
          guard token == epoch, !Task.isCancelled else { return }
          guard snapshot.source == "canonical_log" else {
            throw BridgeError.unreachable("Canonical history unavailable; retry to enable actions")
          }
          guard snapshot.machineID == profile.machineID,
            let identity = snapshot.conversationID, let provider = agent.provider,
            CanonicalConversationID.isCanonical(identity, agent: provider),
            expectedConversation == nil || identity == expectedConversation
          else {
            throw BridgeError.protocolError("Recovery history identity changed or is unavailable")
          }
          applyConversation(snapshot)
        }
        attached = true
        var opened = false
        let events = client.events(base: profile.endpoint, session: agent.sessionID)
        for try await event in events {
          guard token == epoch, !Task.isCancelled else { return }
          if case .agentStatus = event, !opened {
            throw BridgeError.protocolError("Status arrived before stream_open")
          }
          stream = try reduceStream(stream, event, selectedSession: agent.sessionID)
          switch event {
          case .error(let code, let message):
            invalidateLiveStream()
            if code == "upstream_unavailable" { throw BridgeError.unreachable(message) }
            streamError = "\(code): \(message)"
            return
          case .open:
            opened = true
            requestConversation()
          case .entries, .reset:
            requestConversation()
          case .agentStatus(let session, _, _):
            if session == agent.sessionID { streamError = nil }
            requestConversation()
          case .promptRequest, .promptResolved:
            break
          }
        }
        throw BridgeError.unreachable("Bridge stream ended")
      } catch {
        guard token == epoch, !Task.isCancelled else { return }
        invalidateLiveStream()
        // A protocol violation is final; transport failures retry within the budget.
        if case BridgeError.protocolError(let detail)? = error as? BridgeError {
          streamError = detail
          return
        }
        if error as? BridgeError == .unauthorized || isBridgeTLSFailure(error) {
          streamError = (error as? BridgeError)?.message ?? error.localizedDescription
          return
        }
        guard let delay = budget.nextDelayMillis() else {
          streamError = (error as? BridgeError)?.message ?? error.localizedDescription
          return
        }
        streamError = Self.reconnecting
        do { try await retryPause(delay) } catch { return }
      }
    }
  }

  private func invalidateLiveStream() {
    stream.pendingPrompt = nil
    stream.agentStatus = nil
    stream.agentStatusDetail = nil
  }

  /// Sends a prompt to the selected agent.
  /// `true` once the bridge accepted it, so the composer keeps the text until then.
  @discardableResult public func sendPrompt(_ text: String, confirmed: Bool = false) async -> Bool {
    // Refused before anything reaches the bridge, as Android does; the reason shows on the page.
    if let command = slashCommand(text) {
      let working = (stream.agentStatus ?? selectedAgent?.status) == "working"
      if let reason = disallowedSlashCommandReason(command)
        ?? busyAgentCommandReason(command, agentWorking: working)
      {
        actionError = reason
        return false
      }
    }
    return await perform(
      "Failed to submit prompt", supported: capabilities?["prompt_input"] != false,
      unsupported: "Prompt input unsupported by this bridge"
    ) { client, base, session in
      try await client.submitPrompt(
        base: base, session: session, text: text, confirmed: confirmed)
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
  /// Runs an owner action; `true` when the bridge accepted it.
  @discardableResult private func perform(
    _ failure: String,
    supported: Bool = true,
    unsupported: String = "",
    _ send: @escaping @Sendable (any AgentSessionServing, URL, String) async throws -> Void
  ) async -> Bool {
    guard let agent = selectedAgent, let profile = selectedProfile else { return false }
    let token = epoch
    actionError = nil
    guard isLive else {
      actionError = "Waiting for a fresh agent status"
      return false
    }
    guard supported else {
      actionError = unsupported
      return false
    }
    do {
      let client = makeClient()
      try await machines.request(profile, mutation: true) { base in
        try await send(client, base, agent.sessionID)
      }
      guard token == epoch else { return false }
      return true
    } catch {
      guard token == epoch, !(error is CancellationError) else { return false }
      let reason = OwnerMessage.text(for: error)
      actionError = reason.isEmpty ? failure : reason
      return false
    }
  }
}
