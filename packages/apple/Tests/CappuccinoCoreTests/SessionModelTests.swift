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

  func conversationSnapshot(base: URL, session: String, agent: String?) async throws
    -> ConversationSnapshot
  {
    snapshot
  }

  func events(base: URL, session: String) -> AsyncThrowingStream<AgentStreamEvent, Error> {
    let events = self.events
    return AsyncThrowingStream { continuation in
      for event in events { continuation.yield(event) }
      continuation.finish()
    }
  }

  func controlAgent(base: URL, session: String, action: String, capabilities: [String: Bool]?)
    async throws
  {
    try await act("control:\(action)")
  }

  func submitPrompt(base: URL, session: String, text: String) async throws {
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
    capabilities: [String: Bool] = ["agent_control": true], events: [AgentStreamEvent] = []
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
    bridge.events = events
    let counter = ClientCounter()
    let model = SessionModel(machines: machines) {
      counter.count += 1
      return bridge
    }
    let agent = AgentRow(
      machineID: machineID.uuidString, sessionID: "s-1", label: "pi", branch: nil, working: false,
      provider: "pi")
    return Fixture(
      model: model, bridge: bridge, counter: counter, profileID: profile.id, machineID: machineID,
      agent: agent)
  }

  @Test func controlsRequireAdvertisedCapabilityAndUseAFreshClientEach() async throws {
    let fx = try await fixture()
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    let before = fx.counter.count
    await fx.model.control("interrupt")
    await fx.model.control("new_session")
    await fx.model.awaitStreamForTesting()
    #expect(fx.bridge.sent == ["control:interrupt", "control:new_session"])
    #expect(fx.counter.count >= before + 2)
    #expect(fx.model.actionError == nil)
  }

  @Test func controlIsNotOfferedWhenTheBridgeDeclinesIt() async throws {
    let fx = try await fixture(capabilities: ["agent_control": false])
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    #expect(!fx.model.canControl)
    await fx.model.control("interrupt")
    #expect(fx.bridge.sent.isEmpty)
  }

  @Test func answersAreRefusedOnlyWhenTheBridgeSaysNo() async throws {
    let refused = try await fixture(capabilities: ["prompt_answers": false])
    refused.model.select(profileID: refused.profileID, agent: refused.agent)
    await refused.model.answerPrompt("p-1", optionIndex: 0)
    #expect(refused.bridge.sent.isEmpty)
    #expect(refused.model.actionError == "Prompt answers unsupported by this bridge")

    let unknown = try await fixture(capabilities: [:])
    unknown.model.select(profileID: unknown.profileID, agent: unknown.agent)
    await unknown.model.answerPrompt("p-1", optionIndex: 0)
    #expect(unknown.bridge.sent == ["answer:p-1"])
  }

  @Test func bridgeRefusalReasonIsReportedWithoutTouchingTheConnection() async throws {
    let fx = try await fixture()
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    fx.bridge.failure = DaemonClientError.protocolError("answer it in the terminal")
    await fx.model.answerQuestions("q-toolu_1", [QuestionAnswer(selected: [0], other: nil)])
    #expect(fx.model.actionError == "answer it in the terminal")
    #expect(fx.model.streamError == nil)
  }

  @Test func staleActionResultIsDroppedAfterSwitchingAgents() async throws {
    let fx = try await fixture()
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    fx.bridge.latency = 50_000_000
    fx.bridge.failure = DaemonClientError.protocolError("late refusal")
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
    let fx = try await fixture(events: [
      .open(sessionID: "s-1", generation: 1),
      .entries([StreamEntry(id: "a", seq: 1, kind: "output", text: "hi", branch: nil)]),
      .promptRequest(sessionID: "s-1", prompt: card),
    ])
    fx.model.select(profileID: fx.profileID, agent: fx.agent)
    await fx.model.awaitStreamForTesting()
    #expect(fx.model.stream.pendingPrompt == card)
    #expect(fx.model.stream.entries.map(\.text) == ["hi"])
    #expect(fx.model.streamError == nil)
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
