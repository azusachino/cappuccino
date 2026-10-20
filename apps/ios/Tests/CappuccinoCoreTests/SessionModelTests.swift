import Foundation
import Testing

@testable import CappuccinoCore

/// Ports the ConnectedViewModelTest cases the iOS model covers: a stale result is dropped, switching
/// machines is refused, controls follow the verified capabilities, and each request gets a fresh client.
private final class FakeBridge: AgentSessionServing, @unchecked Sendable {
  var events: [AgentStreamEvent] = []
  var snapshot = ConversationSnapshot(
    turns: [], source: "canonical_log", conversationID: nil, machineID: nil, stats: nil)
  var failure: Error?
  var latency: UInt64 = 0
  var sent: [String] = []
  var holdOpen = false
  var continuation: AsyncThrowingStream<AgentStreamEvent, Error>.Continuation?
  var streamFailure: Error?
  var snapshotFactory: (@Sendable () async -> ConversationSnapshot)?
  var attempts = 0
  var bindingChecks = 0
  var binding = true

  func verifyBinding(base: URL, machineID: UUID, agent: AgentRow) async throws -> Bool {
    bindingChecks += 1
    return binding
  }

  func conversationSnapshot(base: URL, session: String, agent: String?) async throws
    -> ConversationSnapshot
  {
    if let snapshotFactory { return await snapshotFactory() }
    return snapshot
  }

  func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error> {
    attempts += 1
    return AsyncThrowingStream { continuation in
      self.continuation = continuation
      for event in events { continuation.yield(event) }
      if let streamFailure {
        continuation.finish(throwing: streamFailure)
      } else if !holdOpen {
        continuation.finish()
      }
    }
  }

  func controlAgent(base: URL, session: String, action: String, capabilities: [String: Bool]?)
    async throws
  {
    try await act("control:\(action)")
  }

  func submitPrompt(base: URL, session: String, text: String, confirmed: Bool) async throws {
    try await act("prompt:\(text)")
  }

  func answerPrompt(
    base: URL, session: String, promptID: String, optionIndex: Int?, optionID: String?,
    action: String?
  ) async throws {
    try await act("answer:\(promptID)")
  }

  func answerQuestions(base: URL, session: String, promptID: String, answers: [QuestionAnswer])
    async throws
  {
    try await act("questions:\(promptID)")
  }

  private func act(_ name: String) async throws {
    if latency > 0 { try await Task.sleep(nanoseconds: latency) }
    sent.append(name)
    if let failure { throw failure }
  }
}

private actor SnapshotGate {
  private(set) var reads = 0
  private var first: CheckedContinuation<Void, Never>?

  func read() async -> ConversationSnapshot {
    reads += 1
    let index = reads
    if index == 1 { await withCheckedContinuation { first = $0 } }
    return ConversationSnapshot(
      turns: [
        ConversationTurn(
          id: "settled-\(index)", role: "assistant", timestamp: nil, text: "Settled \(index)",
          parts: [])
      ],
      source: "canonical_log", conversationID: nil, machineID: nil, stats: nil)
  }

  func release() {
    first?.resume()
    first = nil
  }
}

private final class ClientCounter: @unchecked Sendable {
  var count = 0
}

private struct InstantDiscovery: BridgeMachineServing {
  let capabilities: [String: Bool]
  let machine: UUID

  func verifyMachine(baseURL: URL, expectedID: UUID?) async throws -> VerifiedMachine {
    VerifiedMachine(
      machine: BridgeMachine(id: machine, name: nil, version: nil),
      details: BridgeDetails(
        machineID: machine, machineName: nil, version: nil, protocolVersion: 1,
        capabilities: capabilities))
  }

  func listAgents(baseURL: URL, expectedMachineID: UUID?) async throws -> [AgentRow] { [] }
}

@MainActor struct SessionModelTests {
  private struct Fixture {
    let model: SessionModel
    let bridge: FakeBridge
    let counter: ClientCounter
    let profileID: UUID
    let machineID: UUID
    let agent: AgentRow
  }

  private func fixture(
    capabilities: [String: Bool] = ["agent_control": true], events: [AgentStreamEvent]? = nil,
    nativeRef: String? = "pi:synthetic", holdOpen: Bool? = nil
  ) async throws -> Fixture {
    let suite = "cappuccino-session-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    let machineID = UUID()
    let profile = try BridgeProfile(
      machineID: machineID, endpoint: URL(string: "https://mac.example")!, name: "Mac")
    let store = BridgeProfileStore(defaults: defaults)
    try store.save([profile])
    let machines = MachineConnections(
      store: store, client: InstantDiscovery(capabilities: capabilities, machine: machineID))
    machines.setForeground(true)
    await machines.refresh(profile.id)?.value
    let bridge = FakeBridge()
    bridge.events =
      events ?? [
        .open(sessionID: "s-1", generation: 1),
        .agentStatus(sessionID: "s-1", state: "idle", detail: nil),
      ]
    bridge.holdOpen = holdOpen ?? (events == nil)
    let counter = ClientCounter()
    let model = SessionModel(machines: machines) {
      counter.count += 1
      return bridge
    }
    let agent = AgentRow(
      machineID: machineID.uuidString, sessionID: "s-1", label: "pi", branch: nil, working: false,
      provider: "pi", paneID: "pane-1", nativeSessionRef: nativeRef)
    return Fixture(
      model: model, bridge: bridge, counter: counter, profileID: profile.id, machineID: machineID,
      agent: agent)
  }

  private func selectLive(_ fx: Fixture) async {
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    for _ in 0..<100 {
      if fx.model.isLive { return }
      await Task.yield()
    }
    #expect(fx.model.isLive, "Controlled stream must deliver its fresh baseline")
  }

  @Test func controlsRequireAdvertisedCapabilityAndUseAFreshClientEach() async throws {
    let fx = try await fixture()
    defer { fx.model.clearSelection() }
    await selectLive(fx)
    let before = fx.counter.count
    await fx.model.control("interrupt")
    await fx.model.control("new_session")
    #expect(fx.bridge.sent == ["control:interrupt", "control:new_session"])
    #expect(fx.counter.count >= before + 2)
    #expect(fx.model.actionError == nil)
  }

  @Test func controlIsNotOfferedWhenTheBridgeDeclinesIt() async throws {
    let fx = try await fixture(capabilities: ["agent_control": false])
    defer { fx.model.clearSelection() }
    await selectLive(fx)
    #expect(!fx.model.canControl)
    await fx.model.control("interrupt")
    #expect(fx.bridge.sent.isEmpty)
  }

  @Test func answersAreRefusedOnlyWhenTheBridgeSaysNo() async throws {
    let refused = try await fixture(capabilities: ["prompt_answers": false])
    defer { refused.model.clearSelection() }
    await selectLive(refused)
    await refused.model.answerPrompt("p-1", optionIndex: 0)
    #expect(refused.bridge.sent.isEmpty)
    #expect(refused.model.actionError == "Prompt answers unsupported by this bridge")

    let unknown = try await fixture(capabilities: [:])
    defer { unknown.model.clearSelection() }
    await selectLive(unknown)
    await unknown.model.answerPrompt("p-1", optionIndex: 0)
    #expect(unknown.bridge.sent == ["answer:p-1"])
  }

  @Test func bridgeRefusalReasonIsReportedWithoutTouchingTheConnection() async throws {
    let fx = try await fixture()
    defer { fx.model.clearSelection() }
    await selectLive(fx)
    fx.bridge.failure = BridgeError.protocolError("answer it in the terminal")
    await fx.model.answerQuestions("q-toolu_1", [QuestionAnswer(selected: [0], other: nil)])
    #expect(fx.model.actionError == "answer it in the terminal")
    #expect(fx.model.streamError == nil)
  }

  @Test func staleActionResultIsDroppedAfterSwitchingAgents() async throws {
    let fx = try await fixture()
    defer { fx.model.clearSelection() }
    await selectLive(fx)
    fx.bridge.latency = 50_000_000
    fx.bridge.failure = BridgeError.protocolError("late refusal")
    let action = Task {
      await fx.model.answerQuestions("q", [QuestionAnswer(selected: [0], other: nil)])
    }
    try await Task.sleep(nanoseconds: 10_000_000)
    fx.model.select(profileID: fx.profileID, agent: nil)
    await action.value
    #expect(fx.model.actionError == nil)
    #expect(fx.model.selectedAgent == nil)
  }

  @Test func agentFromAnotherMachineIsRefused() async throws {
    let fx = try await fixture()
    let foreign = AgentRow(
      machineID: UUID().uuidString, sessionID: "s-1", label: "pi", branch: nil, working: false)
    fx.model.select(profileID: fx.profileID, agent: foreign)
    #expect(fx.model.selectedAgent == nil)
    #expect(fx.model.streamError == "Agent belongs to a different machine")
  }

  @Test func streamReducesPromptsAndStatusForTheSelectedAgent() async throws {
    let card = PromptCard(
      promptID: "p-1", type: "tool_approval", title: "Approve", message: nil, toolName: "bash",
      command: "make test", options: [], selectedIndex: 0, questions: [])
    let fx = try await fixture(
      events: [
        .open(sessionID: "s-1", generation: 1),
        .agentStatus(sessionID: "s-1", state: "idle", detail: nil),
        .entries([StreamEntry(id: "a", seq: 1, kind: "output", text: "hi", branch: nil)]),
        .promptRequest(sessionID: "s-1", prompt: card),
      ], holdOpen: true)
    defer { fx.model.clearSelection() }
    await selectLive(fx)
    for _ in 0..<100 {
      if fx.model.stream.pendingPrompt == card { break }
      await Task.yield()
    }
    #expect(fx.model.stream.pendingPrompt == card)
    #expect(fx.model.stream.entries.map(\.text) == ["hi"])
    #expect(fx.model.streamError == nil)
    fx.bridge.continuation?.yield(.error(code: "not_found", message: "Gone"))
    await fx.model.awaitStreamForTesting()
    #expect(fx.model.stream.pendingPrompt == nil)
    #expect(!fx.model.isLive)
    #expect(fx.model.stream.entries.map(\.text) == ["hi"])
  }

  @Test func refreshBurstCoalescesAndSettlesWithoutHidingSourceLoss() async throws {
    let fx = try await fixture()
    defer { fx.model.clearSelection() }
    let gate = SnapshotGate()
    fx.bridge.snapshotFactory = { await gate.read() }
    fx.model.retryPause = { _ in throw CancellationError() }
    await selectLive(fx)
    for _ in 0..<100 {
      if await gate.reads == 1 { break }
      await Task.yield()
    }
    #expect(await gate.reads == 1)
    for _ in 0..<20 { fx.model.requestConversation() }
    #expect(await gate.reads == 1)
    fx.bridge.continuation?.yield(
      .error(code: "upstream_unavailable", message: "Source unavailable"))
    await fx.model.awaitStreamForTesting()
    #expect(!fx.model.isLive)
    #expect(fx.model.streamError != nil)
    await gate.release()
    for _ in 0..<100 {
      if fx.model.turns.last?.text == "Settled 2" { break }
      await Task.yield()
    }
    #expect(await gate.reads == 2)
    #expect(fx.model.turns.last?.text == "Settled 2")
    #expect(!fx.model.isLive)
    #expect(fx.model.streamError != nil)
  }

  @Test func foregroundRecoveryRevalidatesNativeBindingAndLegacyNeedsReselection() async throws {
    for legacy in [false, true] {
      let fx = try await fixture(nativeRef: legacy ? nil : "pi:original")
      defer { fx.model.clearSelection() }
      await selectLive(fx)
      fx.bridge.continuation?.yield(.promptRequest(sessionID: "s-1", prompt: approval))
      for _ in 0..<100 {
        if fx.model.stream.pendingPrompt != nil { break }
        await Task.yield()
      }
      #expect(fx.model.stream.pendingPrompt == approval)
      fx.model.setForeground(false)
      #expect(!fx.model.isLive)
      #expect(fx.model.stream.pendingPrompt == nil)
      #expect(await fx.model.sendPrompt("background callback") == false)
      fx.model.setForeground(true)
      if legacy {
        await fx.model.awaitStreamForTesting()
        #expect(fx.bridge.attempts == 1)
        #expect(fx.bridge.bindingChecks == 0)
        #expect(!fx.model.isLive)
        await selectLive(fx)
      } else {
        for _ in 0..<100 {
          if fx.bridge.attempts == 2 && fx.model.isLive { break }
          await Task.yield()
        }
        #expect(fx.bridge.bindingChecks == 1)
      }
      #expect(fx.bridge.attempts == 2)
      #expect(fx.model.isLive)
      #expect(fx.model.stream.pendingPrompt == nil)
      #expect(fx.bridge.sent.isEmpty)
    }
  }

  private var approval: PromptCard {
    PromptCard(
      promptID: "p-stale", type: "tool_approval", title: "Approve", message: nil,
      toolName: "bash", command: "synthetic-command", options: [], selectedIndex: 0, questions: [])
  }

  private var liveEvents: [AgentStreamEvent] {
    [
      .open(sessionID: "s-1", generation: 1),
      .agentStatus(sessionID: "s-1", state: "working", detail: "Old detail"),
      .promptRequest(sessionID: "s-1", prompt: approval),
    ]
  }

  @Test func sourceOverflowAndEOFLossUseFiniteBudgetAndInvalidateActions() async throws {
    for mode in 0..<3 {
      let fx = try await fixture(events: liveEvents)
      defer { fx.model.clearSelection() }
      fx.model.retryPause = { _ in }
      if mode == 0 {
        fx.bridge.events.append(.error(code: "upstream_unavailable", message: "Source unavailable"))
      }
      if mode == 1 { fx.bridge.streamFailure = BridgeError.streamOverflow }
      fx.model.select(profileID: fx.profileID, agent: fx.agent)
      await fx.model.awaitStreamForTesting()
      #expect(fx.bridge.attempts == 5)
      #expect(fx.bridge.bindingChecks == 4)
      #expect(!fx.model.isLive)
      #expect(fx.model.stream.pendingPrompt == nil)
      #expect(fx.model.stream.agentStatusDetail == nil)
      #expect(fx.model.streamError != nil)
      #expect(await fx.model.sendPrompt("stale") == false)
      #expect(fx.bridge.sent.isEmpty)
    }
  }

  @Test func legacyAndReplacedIdentityNeverReceiveAutomaticReattach() async throws {
    for legacy in [true, false] {
      let fx = try await fixture(events: liveEvents, nativeRef: legacy ? nil : "pi:original")
      defer { fx.model.clearSelection() }
      fx.model.retryPause = { _ in }
      fx.bridge.binding = false
      fx.model.select(profileID: fx.profileID, agent: fx.agent)
      await fx.model.awaitStreamForTesting()
      #expect(fx.bridge.attempts == 1)
      #expect(fx.bridge.bindingChecks == (legacy ? 0 : 1))
      #expect(!fx.model.isLive)
      #expect(fx.model.stream.pendingPrompt == nil)
      #expect(
        fx.model.streamError == "Agent session identity is unavailable or replaced; reselect it")
    }
  }

  @Test func terminalFailuresInvalidateWithoutRetry() async throws {
    let failures: [any Error] = [
      BridgeError.protocolError("Malformed"), BridgeError.unauthorized,
      URLError(.serverCertificateUntrusted),
    ]
    for failure in failures {
      let fx = try await fixture(events: liveEvents)
      defer { fx.model.clearSelection() }
      fx.bridge.streamFailure = failure
      fx.model.retryPause = { _ in Issue.record("Terminal failures must not retry") }
      fx.model.select(profileID: fx.profileID, agent: fx.agent)
      await fx.model.awaitStreamForTesting()
      #expect(fx.bridge.attempts == 1)
      #expect(!fx.model.isLive)
      #expect(fx.model.stream.pendingPrompt == nil)
      #expect(fx.model.stream.agentStatusDetail == nil)
      #expect(fx.model.streamError != nil)
      await fx.model.answerPrompt("p-stale")
      #expect(fx.bridge.sent.isEmpty)
    }
  }

  @Test func freshBaselineRequiredAndRecoveryNeverReplaysWrites() async throws {
    let fx = try await fixture(events: [.open(sessionID: "s-1", generation: 1)], holdOpen: true)
    defer { fx.model.clearSelection() }
    fx.model.retryPause = { _ in }
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    for _ in 0..<100 {
      if fx.bridge.attempts == 1 { break }
      await Task.yield()
    }
    #expect(!fx.model.isLive)
    #expect(await fx.model.sendPrompt("before baseline") == false)
    await fx.model.control("interrupt")
    await fx.model.answerPrompt("stale")
    #expect(fx.bridge.sent.isEmpty)
    fx.bridge.continuation?.yield(.agentStatus(sessionID: "s-1", state: "idle", detail: nil))
    for _ in 0..<100 {
      if fx.model.isLive { break }
      await Task.yield()
    }
    #expect(fx.model.isLive)
    #expect(await fx.model.sendPrompt("only once"))
    fx.bridge.continuation?.yield(.promptRequest(sessionID: "s-1", prompt: approval))
    for _ in 0..<100 {
      if fx.model.stream.pendingPrompt != nil { break }
      await Task.yield()
    }
    #expect(fx.model.stream.pendingPrompt == approval)
    fx.bridge.continuation?.yield(.error(code: "upstream_unavailable", message: "Lost source"))
    for _ in 0..<100 {
      if fx.bridge.attempts == 2 { break }
      await Task.yield()
    }
    #expect(fx.bridge.attempts == 2)
    #expect(fx.bridge.bindingChecks == 1)
    #expect(!fx.model.isLive)
    #expect(fx.model.stream.pendingPrompt == nil)
    #expect(fx.bridge.sent == ["prompt:only once"])
    fx.bridge.continuation?.yield(.agentStatus(sessionID: "s-1", state: "idle", detail: nil))
    for _ in 0..<100 {
      if fx.model.isLive { break }
      await Task.yield()
    }
    #expect(fx.model.isLive)
    #expect(fx.model.streamError == nil)
    #expect(fx.bridge.sent == ["prompt:only once"])
  }

  @Test func protocolViolationEndsTheStreamWithItsReason() async throws {
    let fx = try await fixture(events: [
      .entries([StreamEntry(id: "a", seq: 1, kind: "output", text: "x", branch: nil)])
    ])
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    await fx.model.awaitStreamForTesting()
    #expect(fx.model.streamError == "Entries arrived before stream_open")
  }
}
