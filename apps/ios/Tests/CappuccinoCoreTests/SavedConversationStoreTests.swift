import Foundation
import Testing

@testable import CappuccinoCore

struct SavedConversationStoreTests {
  private let machine = UUID()
  private let base = Date(timeIntervalSince1970: 1_800_000_000)

  private func directory() -> URL {
    URL.temporaryDirectory.appendingPathComponent("saved-\(UUID().uuidString)")
  }

  /// A canonical `pi:<64 hex>` id; `n` keeps each conversation's digest distinct.
  private func conversationID(_ n: Int) -> String {
    "pi:" + String(repeating: "0", count: 60) + String(format: "%04x", n)
  }

  private func saved(_ n: Int, at date: Date? = nil) -> SavedConversation {
    SavedConversation(
      agent: AgentRow(
        machineID: machine.uuidString, sessionID: "s\(n)", label: "Pi \(n)", branch: nil,
        working: false, provider: "pi", status: "idle"),
      conversationID: conversationID(n), savedAt: date ?? base,
      turns: [
        ConversationTurn(
          id: "t\(n)", role: "user", timestamp: nil, text: "hello", parts: []),
        ConversationTurn(
          id: "a\(n)", role: "assistant", timestamp: "2026-11-01T00:00:00Z", text: nil,
          parts: [
            .thinking("plan"),
            .text("answer"),
            .responseStatus(status: "failed", detail: "timeout"),
            .toolCall(
              ToolCall(
                name: "bash", input: "ls", output: nil, isError: nil, startedAt: nil,
                finishedAt: nil, images: 2)),
          ]),
      ],
      omittedMessages: 3, omittedTools: 1)
  }

  @Test func roundTripsEveryPartKindAndTheCounts() throws {
    let store = SavedConversationStore(directory: directory())
    let original = saved(1)
    try store.save(original)
    let read = try #require(
      try store.read(machineID: machine, conversationID: original.conversationID))
    // The store keeps the agent's identity and label, not its live status, as Android does.
    #expect(read.agent.label == original.agent.label)
    #expect(read.agent.sessionID == original.agent.sessionID)
    #expect(read.agent.status == "unknown")
    #expect(read.conversationID == original.conversationID)
    #expect(read.savedAt == original.savedAt)
    #expect(read.turns == original.turns)
    #expect(read.omittedMessages == 3)
    #expect(read.omittedTools == 1)
  }

  @Test func missingConversationReadsAsNil() throws {
    let store = SavedConversationStore(directory: directory())
    #expect(try store.read(machineID: machine, conversationID: conversationID(9)) == nil)
    #expect(try store.list().isEmpty)
  }

  @Test func listsNewestFirstAndDeleteRemovesOne() throws {
    let store = SavedConversationStore(directory: directory())
    try store.save(saved(1, at: base))
    try store.save(saved(2, at: base.addingTimeInterval(60)))
    #expect(try store.list().map(\.conversationID) == [conversationID(2), conversationID(1)])
    try store.delete(machineID: machine, conversationID: conversationID(2))
    #expect(try store.list().map(\.conversationID) == [conversationID(1)])
  }

  @Test func rejectsIdentityThatDoesNotMatchTheProvider() {
    let store = SavedConversationStore(directory: directory())
    var wrongPrefix = saved(1)
    wrongPrefix = SavedConversation(
      agent: AgentRow(
        machineID: machine.uuidString, sessionID: "s1", label: "Claude", branch: nil,
        working: false, provider: "claude", status: "idle"),
      conversationID: wrongPrefix.conversationID, savedAt: base, turns: [],
      omittedMessages: 0, omittedTools: 0)
    #expect(throws: SavedConversationError.invalidIdentity) { try store.save(wrongPrefix) }
  }

  @Test func prunesEntriesPastRetentionOnSave() throws {
    let dir = directory()
    try SavedConversationStore(directory: dir).save(saved(1, at: base))
    let later = base.addingTimeInterval(SavedConversationStore.retention + 1)
    let store = SavedConversationStore(directory: dir, now: { later })
    try store.save(saved(2, at: later))
    #expect(try store.list().map(\.conversationID) == [conversationID(2)])
  }

  @Test func keepsOnlyTheNewestEntriesWithinTheCount() throws {
    let store = SavedConversationStore(directory: directory())
    let total = SavedConversationStore.maxEntries + 2
    for n in 1...total {
      try store.save(saved(n, at: base.addingTimeInterval(Double(n))))
    }
    let listed = try store.list()
    #expect(listed.count == SavedConversationStore.maxEntries)
    #expect(listed.first?.conversationID == conversationID(total))
    #expect(listed.last?.conversationID == conversationID(3))
  }

  @Test func corruptFileIsSkippedByListAndThrowsOnRead() throws {
    let dir = directory()
    let store = SavedConversationStore(directory: dir)
    try store.save(saved(1))
    let damaged = try FileManager.default.contentsOfDirectory(
      at: dir, includingPropertiesForKeys: nil)
    try Data("not json".utf8).write(to: damaged[0])
    #expect(try store.list().isEmpty)
    #expect(throws: (any Error).self) {
      try store.read(machineID: machine, conversationID: conversationID(1))
    }
  }
}
