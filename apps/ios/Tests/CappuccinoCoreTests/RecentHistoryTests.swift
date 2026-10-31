import Foundation
import Testing

@testable import CappuccinoCore

/// Ports RecentHistoryTest, SessionStatusTest and QuestionFormTest.
struct RecentHistoryTests {
  private func turn(_ id: String, text: String? = nil, parts: [ConversationPart] = [])
    -> ConversationTurn
  {
    ConversationTurn(id: id, role: "agent", timestamp: nil, text: text, parts: parts)
  }

  private func tool(_ name: String, output: String? = "result", isError: Bool? = nil)
    -> ConversationPart
  {
    .toolCall(
      ToolCall(
        name: name, input: "", output: output, isError: isError, startedAt: nil, finishedAt: nil,
        images: nil))
  }

  @Test func messagesAreBoundedInOriginalOrderWithoutMutatingInput() {
    let input = (0...220).map { turn("t\($0)", text: "reply \($0)") }
    let recent = boundedConversation(input)
    #expect(recent.turns.count == 200)
    #expect(recent.omittedMessages == 21)
    #expect(recent.turns.first?.id == "t21")
    #expect(recent.turns.last?.id == "t220")
    #expect(input.count == 221)
  }

  @Test func toolLimitAppliesEvenInsideOneMessageAndRetainsProseAndStatus() {
    let tools = (0...150).map { tool("tool\($0)", output: "result", isError: $0 == 150) }
    let input = [
      turn(
        "t", text: "Partial reply", parts: tools + [.responseStatus(status: "aborted", detail: nil)]
      )
    ]
    let recent = boundedConversation(input)
    let retained = recent.turns[0].parts.compactMap { part -> ToolCall? in
      if case .toolCall(let call) = part { return call }
      return nil
    }
    #expect(retained.count == 100)
    #expect(recent.omittedTools == 51)
    #expect(retained.first?.name == "tool51")
    #expect(retained.last?.isError == true)
    #expect(recent.turns[0].text == "Partial reply")
    #expect(recent.turns[0].parts.last == .responseStatus(status: "aborted", detail: nil))
    #expect(input[0].parts.count == 152)
  }

  @Test func toolChangesDoNotTriggerMessageFollowingButRepliesAndOutcomesDo() {
    let before = [turn("reply", text: "Same reply", parts: [tool("bash", output: nil)])]
    let after = [
      turn("reply", text: "Same reply", parts: [tool("bash", output: "failed", isError: true)])
    ]
    #expect(conversationFollowContent(before) == conversationFollowContent(after))
    #expect(conversationFollowContent([turn("tool", parts: [tool("bash", output: nil)])]).isEmpty)
    #expect(
      conversationFollowContent(before)
        != conversationFollowContent([
          turn("reply", text: "New reply", parts: [tool("bash", output: nil)])
        ]))
    #expect(
      conversationFollowContent(before)
        != conversationFollowContent([
          turn(
            "reply", text: "Same reply", parts: [.responseStatus(status: "aborted", detail: nil)])
        ]))
  }

  @Test func trimmingOlderHistoryDoesNotLookLikeANewAnswer() {
    let older = turn("older", text: "Older answer")
    let latest = turn("latest", text: "Last answer")
    #expect(conversationFollowContent([older, latest]) == conversationFollowContent([latest]))
  }

  @Test func reasoningWithToolsDoesNotTriggerFollowingWithoutAnAnswer() {
    let reply = turn("reply", text: "Last answer")
    let reasoning = turn(
      "tool-step", parts: [.thinking("Inspecting the next file"), tool("bash", output: nil)])
    #expect(conversationFollowContent([reply]) == conversationFollowContent([reply, reasoning]))
    #expect(conversationFollowContent([reasoning]).isEmpty)
  }

  @Test func responseFailuresRemainRecordedButDoNotChangeChatFollowing() {
    let reply = turn("t", text: "Partial reply")
    let failed = turn(
      "t", text: "Partial reply", parts: [.responseStatus(status: "failed", detail: "terminated")])
    #expect(conversationFollowContent([reply]) == conversationFollowContent([failed]))
    #expect(!ConversationPart.responseStatus(status: "failed", detail: "terminated").visibleInChat)
    #expect(ConversationPart.responseStatus(status: "aborted", detail: nil).visibleInChat)
  }

  @Test func omittedToolsIncludeOperationsLostOutsideTheMessageWindow() {
    let input = (0..<350).map { turn("t\($0)", parts: [tool("tool\($0)")]) }
    let recent = boundedConversation(input)
    let names = recent.turns.flatMap(\.parts).compactMap { part -> String? in
      if case .toolCall(let call) = part { return call.name }
      return nil
    }
    #expect(names.count == 100)
    #expect(recent.omittedTools == 250)
    #expect(recent.omittedMessages == 150)
    #expect(names.first == "tool250")
    #expect(names.last == "tool349")
    #expect(names.count + recent.omittedTools == 350)
    #expect(input.count == 350)
  }

  @Test func omittedToolsAreCountedEvenWhenAllAreOutsideTheRetainedWindow() {
    let input =
      [turn("old-tool", parts: [tool("read", output: nil)])]
      + (0..<201).map { turn("text\($0)", text: "reply") }
    let recent = boundedConversation(input)
    #expect(recent.omittedTools == 1)
    #expect(
      recent.turns.flatMap(\.parts).allSatisfy { if case .toolCall = $0 { false } else { true } })
  }

  @Test func smallAndEmptyWindowsAreUnchanged() {
    #expect(
      boundedConversation([]) == RecentConversation(turns: [], omittedMessages: 0, omittedTools: 0))
    let one = turn("t", text: "hello")
    #expect(boundedConversation([one]).turns == [one])
  }

  @Test func modelIdsReadAsPeopleSayThem() {
    #expect(shortModel("claude-opus-5-5") == "opus 5.5")
    #expect(shortModel("claude-haiku-5-5") == "haiku 5.5")
    #expect(shortModel("glm-5.3-flash") == "glm-5.3-flash")
  }

  @Test func tokenCountsAreShort() {
    #expect(formatTokens(950) == "950")
    #expect(formatTokens(76_000) == "76K")
    #expect(formatTokens(200_000) == "200K")
    #expect(formatTokens(1_000_000) == "1M")
    #expect(formatTokens(1_234_567) == "1.2M")
  }

  @Test func singleSelectTakesExactlyOneChoiceOrText() {
    let single = PromptQuestion(
      header: "H", question: "Q?", multiSelect: false,
      options: [
        PromptOption(id: "opt-0", label: "A", description: nil),
        PromptOption(id: "opt-1", label: "B", description: nil),
      ])
    #expect(answerComplete(single, QuestionAnswer(selected: [1], other: nil)))
    #expect(answerComplete(single, QuestionAnswer(selected: [], other: "own")))
    #expect(!answerComplete(single, QuestionAnswer(selected: [], other: nil)))
    #expect(!answerComplete(single, QuestionAnswer(selected: [0], other: "own")))
    #expect(!answerComplete(single, QuestionAnswer(selected: [0, 1], other: nil)))
  }

  @Test func multiSelectTakesAtLeastOne() {
    let multi = PromptQuestion(header: "H", question: "Q?", multiSelect: true, options: [])
    #expect(answerComplete(multi, QuestionAnswer(selected: [0, 1], other: "own")))
    #expect(answerComplete(multi, QuestionAnswer(selected: [], other: "own")))
    #expect(!answerComplete(multi, QuestionAnswer(selected: [], other: nil)))
  }
}

@Suite struct SessionStatusLineTests {
  @Test func contextShowsPercentAndWindowWhenKnown() {
    let known = SessionStats(
      model: "claude-opus-5-5", thinkingLevel: "low", contextTokens: 76_000,
      contextWindow: 200_000, cachePercent: 60)
    #expect(contextSummary(known) == "38% · 76K / 200K")
    #expect(modelChip(known) == "opus 5.5 · low")

    let terra = SessionStats(
      model: "gpt-6.1-terra", thinkingLevel: "high", contextTokens: 130_000,
      contextWindow: nil, cachePercent: nil)
    #expect(contextSummary(terra) == "26% · 130K / 500K")
    #expect(defaultContextWindow(model: "gpt-6.1-terra") == 500_000)
    #expect(defaultContextWindow(model: "gpt-5.6-terra") == 500_000)
    #expect(defaultContextWindow(model: "terra") == 500_000)
    #expect(defaultContextWindow(model: "gemini-2.5-pro") == 1_000_000)
    #expect(defaultContextWindow(model: "glm-5.3-flash") == 1_000_000)
    #expect(defaultContextWindow(model: nil, agent: "agy") == 1_000_000)
    #expect(defaultContextWindow(model: "deepseek-chat") == 64_000)
    #expect(defaultContextWindow(model: "gpt-4o") == 128_000)
    #expect(defaultContextWindow(model: "qwen-2.5-coder") == 128_000)
  }

  @Test func contextShowsTokensAloneWhenWindowIsUnknownAndNothingWithoutTokens() {
    let unknown = SessionStats(
      model: nil, thinkingLevel: nil, contextTokens: 1_100, contextWindow: nil, cachePercent: nil)
    #expect(contextSummary(unknown) == "1.1K")
    #expect(modelChip(unknown) == nil)
    let none = SessionStats(
      model: nil, thinkingLevel: nil, contextTokens: nil, contextWindow: nil, cachePercent: nil)
    #expect(contextSummary(none) == nil)
  }

  @Test func onlyWorkingAndBlockedAreNotIdle() {
    #expect(!isIdle("working"))
    #expect(!isIdle("blocked"))
    #expect(isIdle("idle"))
    #expect(isIdle(""))
  }
}
