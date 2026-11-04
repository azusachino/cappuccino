import Foundation
import Testing

@testable import CappuccinoCore

/// Ports BridgeClientTest's conversation, prompt and control cases. Extending the
/// serialized BridgeClientTests suite keeps the shared StubURLProtocol handler safe.
extension BridgeClientTests {
  private func client() -> BridgeClient {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = [StubURLProtocol.self]
    return BridgeClient(port: 7392, session: URLSession(configuration: configuration))
  }

  private var base: URL { URL(string: "http://127.0.0.1:7392")! }

  private var machine: String { "11111111-1111-4111-8111-111111111111" }
  private var machineID: UUID { UUID(uuidString: machine)! }

  @Test func conversationIdentityFollowsTheLocatorNotTheIdentity() async throws {
    let digest = String(repeating: "a", count: 64)
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/v2/agents/chosen/conversation/sync")
      return (
        200,
        Data(
          #"{"session_id":"chosen","mode":"append","machine_id":"\#(machine)","conversation_id":"pi:\#(digest)","upserts":[]}"#
            .utf8)
      )
    }
    let snapshot = try await client().conversationSnapshot(
      base: base, session: "chosen", agent: "pi", machineID: machineID)
    #expect(snapshot.conversationID == "pi:\(digest)")
    #expect(snapshot.source == "canonical_log")
    #expect(snapshot.machineID?.uuidString.lowercased() == machine)
  }

  @Test func rejectsMalformedIdentityAndScrollbackIdentityClaims() async {
    let digest = String(repeating: "a", count: 64)
    for body in [

      #"{"session_id":"chosen","mode":"append","machine_id":"\#(machine)","conversation_id":"pane","upserts":[]}"#,
      #"{"session_id":"chosen","mode":"scrollback","machine_id":"\#(machine)","conversation_id":"pi:\#(digest)","upserts":[]}"#,
      #"{"session_id":"chosen","mode":"append","machine_id":"\#(machine)","conversation_id":123,"upserts":[]}"#,
      #"{"session_id":"chosen","mode":"append","machine_id":"\#(machine)","conversation_id":"claude:\#(digest)","upserts":[]}"#,
    ] {
      StubURLProtocol.handler = { _ in (200, Data(body.utf8)) }
      await #expect(throws: BridgeError.self) {
        try await client().conversationSnapshot(
          base: base, session: "chosen", agent: "pi", machineID: machineID)
      }
    }
  }

  @Test func conversationCarriesSessionStatsWhenTheBridgeSendsThem() async throws {
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(
          #"{"session_id":"s-1","mode":"append","upserts":[],"stats":{"model":"glm","thinking_level":"low","context_tokens":1100,"cache_percent":60}}"#
            .utf8)
      )
    }
    let snapshot = try await client().conversationSnapshot(
      base: base, session: "s-1", machineID: machineID)
    #expect(
      snapshot.stats
        == SessionStats(
          model: "glm", thinkingLevel: "low", contextTokens: 1100, contextWindow: nil,
          cachePercent: 60))
    StubURLProtocol.handler = { _ in
      (200, Data(#"{"session_id":"s-1","mode":"append","upserts":[]}"#.utf8))
    }
    #expect(
      try await client().conversationSnapshot(base: base, session: "s-1", machineID: machineID)
        .stats == nil)
  }

  @Test func locatorWithColonIsOnePercentEncodedPathComponent() async throws {
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/v2/agents/w1:p3K/conversation/sync")
      #expect(request.url?.absoluteString.contains("w1%3Ap3K") == true)
      return (200, Data(#"{"session_id":"w1:p3K","mode":"scrollback","upserts":[]}"#.utf8))
    }
    _ = try await client().conversationSnapshot(base: base, session: "w1:p3K", machineID: machineID)
  }

  @Test func fetchesConversationTurnsAndParts() async throws {
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(
          #"{"session_id":"s-1","mode":"append","upserts":[{"id":"t1","role":"user","text":"hello"}]}"#
            .utf8)
      )
    }
    let turns = try await client().conversationSnapshot(
      base: base, session: "s-1", machineID: machineID
    ).turns
    #expect(
      turns == [ConversationTurn(id: "t1", role: "user", timestamp: nil, text: "hello", parts: [])])
  }

  @Test func answersEveryQuestionInOneStructuredSubmission() async throws {
    let recorder = BodyRecorder()
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/v2/agents/s-1/prompt")
      #expect(request.httpMethod == "POST")
      recorder.bodies.append(requestBody(request))
      return (200, Data(#"{"event":"question_answered"}"#.utf8))
    }
    try await client().answerQuestions(
      base: base, session: "s-1", binding: binding, promptID: "q-toolu_1",
      answers: [
        QuestionAnswer(questionID: "q-1", selectedOptionIDs: ["opt-1"], freeText: nil),
        QuestionAnswer(
          questionID: "q-2", selectedOptionIDs: ["opt-0", "opt-2"], freeText: "a \"bird\""),
      ])
    let sent = try JSONSerialization.jsonObject(with: recorder.bodies[0]) as? [String: Any]
    #expect(sent?["type"] as? String == "answer_question")
    #expect(sent?["prompt_id"] as? String == "q-toolu_1")
    #expect((sent?["expected_session"] as? [String: String])?["pane_id"] == binding.paneID)
    let answers = sent?["answers"] as? [[String: Any]]
    #expect(answers?[0]["selected_option_ids"] as? [String] == ["opt-1"])
    #expect(answers?[0]["free_text"] == nil)
    #expect(answers?[1]["free_text"] as? String == "a \"bird\"")
  }

  @Test func refusalReasonFromTheEnvelopeReachesTheOwner() async {
    StubURLProtocol.handler = { _ in
      (
        409,
        Data(#"{"event":"error","code":"stale_prompt","message":"answer it in the terminal"}"#.utf8)
      )
    }
    do {
      try await client().answerQuestions(
        base: base, session: "s-1", binding: binding, promptID: "q",
        answers: [QuestionAnswer(questionID: "q", selectedOptionIDs: ["opt-0"], freeText: nil)])
      Issue.record("a refused answer must throw")
    } catch {
      #expect(error as? BridgeError == .protocolError("answer it in the terminal"))
    }
  }

  @Test func controlsAgentOnlyWhenTheBridgeAdvertisesIt() async throws {
    let recorder = BodyRecorder()
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/v2/agents/s-1/control")
      #expect(request.httpMethod == "POST")
      recorder.bodies.append(requestBody(request))
      return (200, Data(#"{"event":"control_sent"}"#.utf8))
    }
    await #expect(throws: BridgeError.self) {
      try await client().controlAgent(
        base: base, session: "s-1", binding: binding, action: "interrupt",
        capabilities: ["agent_control": false])
    }
    #expect(recorder.bodies.isEmpty)
    try await client().controlAgent(
      base: base, session: "s-1", binding: binding, action: "interrupt",
      capabilities: ["agent_control": true])
    try await client().controlAgent(
      base: base, session: "s-1", binding: binding, action: "new_session")
    let sent = try recorder.bodies.map {
      try JSONSerialization.jsonObject(with: $0) as? [String: Any]
    }
    #expect(sent.map { $0?["action"] as? String } == ["interrupt", "new_session"])
    for body in sent {
      #expect(
        body?["expected_session"] as? [String: String] == [
          "machine_id": binding.machineID, "pane_id": binding.paneID,
          "native_session_ref": binding.nativeSessionRef,
        ])
    }
  }

}

final class BodyRecorder: @unchecked Sendable {
  var bodies: [Data] = []
}

/// URLSession hands a stubbed request its body as a stream, not as httpBody.
func requestBody(_ request: URLRequest) -> Data {
  guard let stream = request.httpBodyStream else { return request.httpBody ?? Data() }
  stream.open()
  defer { stream.close() }
  var data = Data()
  var buffer = [UInt8](repeating: 0, count: 1024)
  while stream.hasBytesAvailable {
    let count = stream.read(&buffer, maxLength: buffer.count)
    if count <= 0 { break }
    data.append(buffer, count: count)
  }
  return data
}
