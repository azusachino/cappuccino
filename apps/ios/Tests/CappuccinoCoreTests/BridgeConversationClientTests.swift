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

  @Test func conversationIdentityFollowsTheLocatorNotTheIdentity() async throws {
    let digest = String(repeating: "a", count: 64)
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/chosen/conversation")
      return (
        200,
        Data(
          #"{"session_id":"chosen","source":"canonical_log","machine_id":"\#(machine)","conversation_id":"pi:\#(digest)","turns":[]}"#
            .utf8)
      )
    }
    let snapshot = try await client().conversationSnapshot(
      base: base, session: "chosen", agent: "pi")
    #expect(snapshot.conversationID == "pi:\(digest)")
    #expect(snapshot.source == "canonical_log")
    #expect(snapshot.machineID?.uuidString.lowercased() == machine)
  }

  @Test func rejectsWrongLocatorMalformedIdentityAndScrollbackIdentityClaims() async {
    let digest = String(repeating: "a", count: 64)
    for body in [
      #"{"session_id":"other","source":"canonical_log","machine_id":"\#(machine)","conversation_id":"pi:\#(digest)","turns":[]}"#,
      #"{"session_id":"chosen","source":"canonical_log","conversation_id":"pi:\#(digest)","turns":[]}"#,
      #"{"session_id":"chosen","source":"canonical_log","machine_id":"\#(machine)","conversation_id":"pane","turns":[]}"#,
      #"{"session_id":"chosen","source":"scrollback","machine_id":"\#(machine)","conversation_id":"pi:\#(digest)","turns":[]}"#,
      #"{"session_id":"chosen","source":"canonical_log","machine_id":"\#(machine)","conversation_id":123,"turns":[]}"#,
      #"{"session_id":"chosen","source":"canonical_log","machine_id":"\#(machine)","conversation_id":"claude:\#(digest)","turns":[]}"#,
    ] {
      StubURLProtocol.handler = { _ in (200, Data(body.utf8)) }
      await #expect(throws: BridgeError.self) {
        try await client().conversationSnapshot(base: base, session: "chosen", agent: "pi")
      }
    }
  }

  @Test func conversationCarriesSessionStatsWhenTheBridgeSendsThem() async throws {
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(
          #"{"session_id":"s-1","source":"canonical_log","turns":[],"stats":{"model":"glm","thinking_level":"low","context_tokens":1100,"cache_percent":60}}"#
            .utf8)
      )
    }
    let snapshot = try await client().conversationSnapshot(base: base, session: "s-1")
    #expect(
      snapshot.stats
        == SessionStats(
          model: "glm", thinkingLevel: "low", contextTokens: 1100, contextWindow: nil,
          cachePercent: 60))
    StubURLProtocol.handler = { _ in
      (200, Data(#"{"session_id":"s-1","source":"canonical_log","turns":[]}"#.utf8))
    }
    #expect(try await client().conversationSnapshot(base: base, session: "s-1").stats == nil)
  }

  @Test func locatorWithColonIsOnePercentEncodedPathComponent() async throws {
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/w1:p3K/conversation")
      #expect(request.url?.absoluteString.contains("w1%3Ap3K") == true)
      return (200, Data(#"{"session_id":"w1:p3K","source":"scrollback","turns":[]}"#.utf8))
    }
    _ = try await client().conversationSnapshot(base: base, session: "w1:p3K")
  }

  @Test func fetchesConversationTurnsAndParts() async throws {
    StubURLProtocol.handler = { _ in
      (
        200,
        Data(
          #"{"session_id":"s-1","source":"canonical_log","turns":[{"id":"t1","role":"user","text":"hello"}]}"#
            .utf8)
      )
    }
    let turns = try await client().conversationSnapshot(base: base, session: "s-1").turns
    #expect(
      turns == [ConversationTurn(id: "t1", role: "user", timestamp: nil, text: "hello", parts: [])])
  }

  @Test func answersEveryQuestionInOneStructuredSubmission() async throws {
    let recorder = BodyRecorder()
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/s-1/prompt")
      #expect(request.httpMethod == "POST")
      recorder.bodies.append(requestBody(request))
      return (200, Data(#"{"event":"question_answered"}"#.utf8))
    }
    try await client().answerQuestions(
      base: base, session: "s-1", promptID: "q-toolu_1",
      answers: [
        QuestionAnswer(selected: [1], other: nil),
        QuestionAnswer(selected: [0, 2], other: "a \"bird\""),
      ])
    let sent = try JSONSerialization.jsonObject(with: recorder.bodies[0]) as? [String: Any]
    #expect(sent?["type"] as? String == "answer_question")
    #expect(sent?["prompt_id"] as? String == "q-toolu_1")
    let answers = sent?["answers"] as? [[String: Any]]
    #expect(answers?[0]["selected"] as? [Int] == [1])
    #expect(answers?[0]["other"] == nil)
    #expect(answers?[1]["other"] as? String == "a \"bird\"")
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
        base: base, session: "s-1", promptID: "q",
        answers: [QuestionAnswer(selected: [0], other: nil)])
      Issue.record("a refused answer must throw")
    } catch {
      #expect(error as? BridgeError == .protocolError("answer it in the terminal"))
    }
  }

  @Test func controlsAgentOnlyWhenTheBridgeAdvertisesIt() async throws {
    let recorder = BodyRecorder()
    StubURLProtocol.handler = { request in
      #expect(request.url?.path == "/api/agents/s-1/control")
      #expect(request.httpMethod == "POST")
      recorder.bodies.append(requestBody(request))
      return (200, Data(#"{"event":"control_sent"}"#.utf8))
    }
    await #expect(throws: BridgeError.self) {
      try await client().controlAgent(
        base: base, session: "s-1", action: "interrupt", capabilities: ["agent_control": false])
    }
    #expect(recorder.bodies.isEmpty)
    try await client().controlAgent(
      base: base, session: "s-1", action: "interrupt", capabilities: ["agent_control": true])
    try await client().controlAgent(base: base, session: "s-1", action: "new_session")
    #expect(
      recorder.bodies.map { String(decoding: $0, as: UTF8.self) }
        == [#"{"action":"interrupt"}"#, #"{"action":"new_session"}"#])
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
