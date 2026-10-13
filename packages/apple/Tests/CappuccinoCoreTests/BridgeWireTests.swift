import Foundation
import Testing

@testable import CappuccinoCore

/// Ports BridgeDetailsTest, CanonicalConversationIdTest and the wire cases of BridgeProtocolTest.
struct BridgeWireTests {
  private let prefix =
    #""event":"paired","machine_id":"00000000-0000-0000-0000-000000000001","plugin":"cappuccino-bridge""#

  private func json(_ text: String) throws -> BridgeJSON {
    try BridgeJSON.parse(Data(text.utf8))
  }

  @Test func legacyHandshakeIsUnknownRatherThanUnsupported() throws {
    let details = try parseBridgeDetails(json("{\(prefix),\"protocol\":1}"))
    #expect(details.version == nil)
    #expect(details.capabilities == nil)
    #expect(details.protocolVersion == 1)
  }

  @Test func advertisedFalseIsDistinctFromMissingAndTrue() throws {
    let details = try parseBridgeDetails(
      json(
        "{\(prefix),\"protocol\":1,\"bridge_version\":\"0.1.0\",\"machine_name\":\"  Mini \",\"capabilities\":{\"live_output\":true,\"prompt_input\":false}}"
      ))
    #expect(details.version == "0.1.0")
    #expect(details.machineName == "Mini")
    #expect(details.capabilities?["live_output"] == true)
    #expect(details.capabilities?["prompt_input"] == false)
    #expect(details.capabilities?["prompt_answers"] == nil)
  }

  @Test func malformedCapabilitiesAreRejected() {
    #expect(throws: BridgeError.self) {
      try parseBridgeDetails(
        json("{\(prefix),\"protocol\":1,\"capabilities\":{\"prompt_input\":\"yes\"}}"))
    }
    #expect(throws: BridgeError.self) {
      try parseBridgeDetails(json("{\(prefix),\"protocol\":1,\"capabilities\":true}"))
    }
  }

  @Test func incompatibleProtocolExplainsTheConflict() {
    do {
      _ = try parseBridgeDetails(json("{\(prefix),\"protocol\":2}"))
      Issue.record("incompatible protocol must fail closed")
    } catch let error as BridgeError {
      #expect(error.message.contains("protocol 2"))
      #expect(error.message.contains("requires 1"))
    } catch {
      Issue.record("unexpected error \(error)")
    }
  }

  @Test func oversizedHTTPBodiesAreRejected() {
    let body = Data(repeating: 0x20, count: maximumBridgeHTTPBodyBytes + 1)
    #expect(throws: BridgeError.self) { try BridgeJSON.parse(body) }
  }

  @Test func acceptsEachSupportedAgentPrefix() {
    let digest = String(repeating: "a", count: 64)
    for agent in ["pi", "claude", "agy"] {
      #expect(CanonicalConversationID.isCanonical("\(agent):\(digest)"))
      #expect(CanonicalConversationID.isCanonical("\(agent):\(digest)", agent: agent))
    }
  }

  @Test func rejectsUnknownAgentsShortDigestsAndUppercase() {
    let digest = String(repeating: "a", count: 64)
    #expect(!CanonicalConversationID.isCanonical("codex:\(digest)"))
    #expect(!CanonicalConversationID.isCanonical("claude:\(String(repeating: "a", count: 63))"))
    #expect(!CanonicalConversationID.isCanonical("claude:\(String(repeating: "A", count: 64))"))
    #expect(!CanonicalConversationID.isCanonical("claude:\(digest):extra"))
  }

  @Test func identityMustStartWithTheOwningAgentsProvider() {
    let digest = String(repeating: "a", count: 64)
    #expect(!CanonicalConversationID.isCanonical("pi:\(digest)", agent: "claude"))
  }

  @Test func parsesAskUserQuestionCardsWithTheirQuestions() throws {
    let card = try parsePromptCard(
      json(
        """
        {"prompt_id":"q-toolu_1","type":"ask_user_question","title":"Pick a color?","tool_name":"AskUserQuestion","options":[],"selected_index":0,"questions":[{"header":"Color","question":"Pick a color?","multi_select":false,"options":[{"id":"opt-0","label":"Red","description":"warm"},{"id":"opt-1","label":"Blue"}]},{"header":"Pets","question":"Which pets?","multi_select":true,"options":[{"id":"opt-0","label":"Cat"}]}]}
        """))
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
    let screen = try parsePromptCard(
      json(
        #"{"prompt_id":"p-1","type":"tool_approval","title":"T","options":[],"selected_index":0}"#))
    #expect(screen.questions.isEmpty)
  }

  @Test func preservesStructuredToolArgumentsAndResultsForDisclosure() throws {
    let turns = try parseConversationTurns(
      json(
        #"{"turns":[{"id":"entry","role":"agent","parts":[{"type":"tool_call","name":"bash","input":{"command":"printf \"hello\"","timeout":10,"nested":[true,null]},"output":"hello"}]}]}"#
      ))
    guard case .toolCall(let tool) = turns[0].parts[0] else {
      Issue.record("expected a tool call")
      return
    }
    #expect(tool.name == "bash")
    let input = try json(tool.input)
    #expect(try input.string("command") == "printf \"hello\"")
    #expect(try input.long("timeout") == 10)
    #expect(tool.output == "hello")
  }

  @Test func toolImageCountIsParsedAndAbsentStaysNil() throws {
    let turns = try parseConversationTurns(
      json(
        #"{"turns":[{"id":"t","role":"agent","parts":[{"type":"tool_call","name":"Read","input":{"file_path":"shot.png"},"output":"","images":2},{"type":"tool_call","name":"Read","input":"file"}]}]}"#
      ))
    guard case .toolCall(let first) = turns[0].parts[0],
      case .toolCall(let second) = turns[0].parts[1]
    else {
      Issue.record("expected two tool calls")
      return
    }
    #expect(first.images == 2)
    #expect(second.images == nil)
  }

  @Test func toolActivityStateFollowsErrorThenResult() {
    func call(output: String?, isError: Bool?) -> ToolCall {
      ToolCall(
        name: "bash", input: "", output: output, isError: isError, startedAt: nil,
        finishedAt: nil, images: nil)
    }
    #expect(call(output: "x", isError: true).activityState == .failed)
    #expect(call(output: "x", isError: false).activityState == .resultAvailable)
    #expect(call(output: nil, isError: nil).activityState == .resultNotRecorded)
  }

  @Test func malformedPartsKeepTheTurnButDropItsParts() throws {
    let turns = try parseConversationTurns(
      json(
        #"{"turns":[{"id":"t","role":"agent","text":"hi","parts":[{"type":"text","text":"ok"},{"type":"tool_call","name":"x","is_error":"yes"}]}]}"#
      ))
    #expect(turns[0].text == "hi")
    #expect(turns[0].parts.isEmpty)
  }

  @Test func parsesOnlyAbortedOrFailedResponseStatuses() throws {
    let turns = try parseConversationTurns(
      json(
        #"{"turns":[{"id":"t","role":"agent","parts":[{"type":"response_status","status":"completed"},{"type":"response_status","status":"aborted","detail":"stopped"},{"type":"thinking","text":"hm"},{"type":"unknown"}]}]}"#
      ))
    #expect(
      turns[0].parts == [
        .responseStatus(status: "aborted", detail: "stopped"),
        .thinking("hm"),
      ])
  }

  @Test func rejectsTurnsWithoutAnIdentity() {
    #expect(throws: BridgeError.self) {
      try parseConversationTurns(json(#"{"turns":[{"role":"user"}]}"#))
    }
  }
}
