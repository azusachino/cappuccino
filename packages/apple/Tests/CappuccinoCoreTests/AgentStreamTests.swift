import Foundation
import Testing

@testable import CappuccinoCore

/// Ports the stream cases of BridgeProtocolTest: parsing, ordering, gaps, retention and retry budget.
struct AgentStreamTests {
  private func entry(_ id: String, _ seq: Int64, _ text: String = "x") -> StreamEntry {
    StreamEntry(id: id, seq: seq, kind: "output", text: text, branch: nil)
  }

  private func opened(_ generation: Int64 = 1) throws -> StreamState {
    try reduceStream(
      StreamState(), .open(sessionID: "s", generation: generation), selectedSession: "s")
  }

  @Test func retryBudgetAllowsInitialPlusFourBoundedRetriesAndResetsPerAttachment() {
    var budget = StreamRetryBudget()
    #expect((0..<4).map { _ in budget.nextDelayMillis() } == [1_000, 2_000, 4_000, 8_000])
    #expect(budget.nextDelayMillis() == nil)
    var fresh = StreamRetryBudget()
    #expect(fresh.nextDelayMillis() == 1_000)
  }

  @Test func reducerOrdersDeduplicatesGapsAndResetsConnectionWindow() throws {
    var state = try opened()
    let first = entry("a", 4, "first")
    state = try reduceStream(state, .entries([first, first]), selectedSession: "s")
    #expect(state.entries.count == 1)
    #expect(state.rows().first?.gap == true)
    #expect(try reduceStream(state, .reset(generation: 1), selectedSession: "s") == state)
    state = try reduceStream(state, .reset(generation: 2), selectedSession: "s")
    #expect(state.entries.isEmpty)
    #expect(throws: BridgeError.self) {
      try reduceStream(state, .reset(generation: 1), selectedSession: "s")
    }
    #expect(throws: BridgeError.self) {
      try reduceStream(state, .open(sessionID: "other", generation: 3), selectedSession: "s")
    }
  }

  @Test func reducerRejectsConflictingIdsAndSequencesWithinAndAcrossBatches() throws {
    let open = try opened()
    #expect(throws: BridgeError.self) {
      try reduceStream(
        open, .entries([entry("a", 1, "x"), entry("a", 1, "y")]), selectedSession: "s")
    }
    #expect(throws: BridgeError.self) {
      try reduceStream(open, .entries([entry("a", 1), entry("b", 1)]), selectedSession: "s")
    }
    let populated = try reduceStream(open, .entries([entry("a", 1)]), selectedSession: "s")
    #expect(throws: BridgeError.self) {
      try reduceStream(populated, .entries([entry("b", 1)]), selectedSession: "s")
    }
  }

  @Test func entriesBeforeOpenAndUnknownEventsFailClosed() {
    #expect(throws: BridgeError.self) {
      try reduceStream(StreamState(), .entries([entry("a", 1)]), selectedSession: "s")
    }
    #expect(throws: BridgeError.self) {
      try parseStreamFrame(#"{"event":"entries","entries":[{"id":"x"}]}"#)
    }
    #expect(throws: BridgeError.self) { try parseStreamFrame(#"{"event":"mystery"}"#) }
  }

  @Test func retentionCapsRowsAndUtf8Bytes() throws {
    var state = try opened()
    let batch = (1...(maximumOutputRows + 2)).map { entry("id\($0)", Int64($0)) }
    state = try reduceStream(state, .entries(batch), selectedSession: "s")
    #expect(state.entries.count == maximumOutputRows)
    #expect(!state.markers.isEmpty)
    state = try reduceStream(state, .reset(generation: 2), selectedSession: "s")
    let large = String(repeating: "雪", count: maximumOutputUTF8Bytes / 3 + 1)
    state = try reduceStream(state, .entries([entry("large", 1, large)]), selectedSession: "s")
    #expect(state.entries.isEmpty)
  }

  @Test func parsesAskUserQuestionCardsWithTheirQuestions() throws {
    let event = try parseStreamFrame(
      """
      {"event":"prompt_request","session_id":"s-1","prompt":{"prompt_id":"q-toolu_1","type":"ask_user_question","title":"Pick a color?","tool_name":"AskUserQuestion","options":[],"selected_index":0,"questions":[{"header":"Color","question":"Pick a color?","multi_select":false,"options":[{"id":"opt-0","label":"Red","description":"warm"},{"id":"opt-1","label":"Blue"}]},{"header":"Pets","question":"Which pets?","multi_select":true,"options":[{"id":"opt-0","label":"Cat"}]}]}}
      """)
    guard case .promptRequest(let session, let card) = event else {
      Issue.record("expected a prompt request")
      return
    }
    #expect(session == "s-1")
    #expect(card.questions.count == 2)
    #expect(
      card.questions[0]
        == PromptQuestion(
          header: "Color", question: "Pick a color?", multiSelect: false,
          options: [
            PromptOption(id: "opt-0", label: "Red", description: "warm"),
            PromptOption(id: "opt-1", label: "Blue", description: nil),
          ]))
    #expect(card.questions[1].multiSelect)
  }

  @Test func parsesStatusAndResolutionEvents() throws {
    #expect(
      try parseStreamFrame(
        #"{"event":"agent_status","session_id":"s-1","state":"working","detail":"running tests"}"#)
        == .agentStatus(sessionID: "s-1", state: "working", detail: "running tests"))
    #expect(
      try parseStreamFrame(#"{"event":"prompt_resolved","session_id":"s-1","prompt_id":"p-1"}"#)
        == .promptResolved(sessionID: "s-1", promptID: "p-1"))
    #expect(
      try parseStreamFrame(
        #"{"event":"error","code":"stale_prompt","message":"answer it in the terminal"}"#)
        == .error(code: "stale_prompt", message: "answer it in the terminal"))
  }

  @Test func reduceUpdatesAgentStatusAndPromptState() throws {
    var state = StreamState(sessionID: "s-1")
    state = try reduceStream(
      state, .agentStatus(sessionID: "s-1", state: "working", detail: "compiling"),
      selectedSession: "s-1")
    #expect(state.agentStatus == "working")
    #expect(state.agentStatusDetail == "compiling")
    let card = PromptCard(
      promptID: "p-1", type: "confirm", title: "Confirm action", message: nil, toolName: nil,
      command: nil, options: [], selectedIndex: 0, questions: [])
    state = try reduceStream(
      state, .promptRequest(sessionID: "s-1", prompt: card), selectedSession: "s-1")
    #expect(state.pendingPrompt == card)
    state = try reduceStream(
      state, .promptResolved(sessionID: "s-1", promptID: "p-1"), selectedSession: "s-1")
    #expect(state.pendingPrompt == nil)
  }
}
