import Foundation

/// Ports io/OfflineConversations.kt: the saved transcripts behind History, kept on this device for
/// 30 days. A saved transcript is read-only history and never establishes live session state.
public struct SavedConversation: Identifiable, Equatable, Sendable {
  public var id: String { "\(agent.machineID)/\(conversationID)" }
  public let agent: AgentRow
  public let conversationID: String
  public let savedAt: Date
  public let turns: [ConversationTurn]
  public let omittedMessages: Int
  public let omittedTools: Int

  public init(
    agent: AgentRow, conversationID: String, savedAt: Date, turns: [ConversationTurn],
    omittedMessages: Int, omittedTools: Int
  ) {
    self.agent = agent
    self.conversationID = conversationID
    self.savedAt = savedAt
    self.turns = turns
    self.omittedMessages = omittedMessages
    self.omittedTools = omittedTools
  }

  public var machineID: UUID? { UUID(uuidString: agent.machineID) }
}

public enum SavedConversationError: Error, Equatable {
  case invalidIdentity
  case tooLarge
  case corrupt
}

/// One JSON file per conversation, written atomically. Saves prune past retention and past the
/// entry and byte caps, dropping the oldest first.
public struct SavedConversationStore: Sendable {
  public static let maxEntries = 50
  public static let maxBytes = 64 * 1024 * 1024
  public static let maxEntryBytes = 4 * 1024 * 1024
  public static let retention: TimeInterval = 30 * 24 * 60 * 60

  /// The app's store under Application Support. Device and iCloud backups can include it.
  public static let app = SavedConversationStore(
    directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("saved-conversations"))

  private let directory: URL
  private let now: @Sendable () -> Date

  public init(directory: URL, now: @escaping @Sendable () -> Date = { Date() }) {
    self.directory = directory
    self.now = now
  }

  public func save(_ conversation: SavedConversation) throws {
    guard
      let machineID = conversation.machineID,
      CanonicalConversationID.isCanonical(
        conversation.conversationID, agent: conversation.agent.provider ?? "")
    else { throw SavedConversationError.invalidIdentity }
    let data = try JSONEncoder().encode(SavedRecord(conversation))
    guard data.count <= Self.maxEntryBytes else { throw SavedConversationError.tooLarge }
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    try data.write(to: file(machineID, conversation.conversationID), options: .atomic)
    try prune()
  }

  /// The saved transcript for one conversation, or nil when none was saved.
  public func read(machineID: UUID, conversationID: String) throws -> SavedConversation? {
    let url = file(machineID, conversationID)
    guard FileManager.default.fileExists(atPath: url.path) else { return nil }
    return try decode(url)
  }

  /// Every readable saved transcript, newest first. Unreadable files are skipped, not fatal.
  public func list() throws -> [SavedConversation] {
    try entryFiles()
      .compactMap { try? decode($0) }
      .sorted { $0.savedAt > $1.savedAt }
  }

  /// The saved transcripts of one agent, its machine and session, newest first.
  public func list(for agent: AgentRow) throws -> [SavedConversation] {
    guard let machineID = UUID(uuidString: agent.machineID) else { return [] }
    return try list().filter { $0.machineID == machineID && $0.agent.sessionID == agent.sessionID }
  }

  public func delete(machineID: UUID, conversationID: String) throws {
    let url = file(machineID, conversationID)
    if FileManager.default.fileExists(atPath: url.path) {
      try FileManager.default.removeItem(at: url)
    }
  }

  private func prune() throws {
    let cutoff = now().addingTimeInterval(-Self.retention)
    var live: [(url: URL, savedAt: Date, size: Int)] = []
    for url in try entryFiles() {
      let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      guard let saved = try? decode(url), saved.savedAt >= cutoff else {
        try FileManager.default.removeItem(at: url)
        continue
      }
      live.append((url, saved.savedAt, size))
    }
    var total = 0
    for (index, entry) in live.sorted(by: { $0.savedAt > $1.savedAt }).enumerated() {
      if index < Self.maxEntries, total + entry.size <= Self.maxBytes {
        total += entry.size
      } else {
        try FileManager.default.removeItem(at: entry.url)
      }
    }
  }

  private func entryFiles() throws -> [URL] {
    guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
    return try FileManager.default.contentsOfDirectory(
      at: directory, includingPropertiesForKeys: [.fileSizeKey], options: .skipsHiddenFiles
    ).filter { $0.pathExtension == "json" }
  }

  private func file(_ machineID: UUID, _ conversationID: String) -> URL {
    let name = "\(machineID.uuidString)_\(conversationID.replacingOccurrences(of: ":", with: "-"))"
    return directory.appendingPathComponent("\(name).json")
  }

  private func decode(_ url: URL) throws -> SavedConversation {
    let data = try Data(contentsOf: url)
    guard data.count <= Self.maxEntryBytes else { throw SavedConversationError.corrupt }
    return try JSONDecoder().decode(SavedRecord.self, from: data).conversation()
  }
}

/// The on-disk schema. It is private to the store so the in-memory models stay free of Codable.
private struct SavedRecord: Codable {
  let machineID: String
  let sessionID: String
  let label: String
  let provider: String?
  let conversationID: String
  let savedAt: Date
  let omittedMessages: Int
  let omittedTools: Int
  let turns: [SavedTurn]

  init(_ conversation: SavedConversation) {
    machineID = conversation.agent.machineID
    sessionID = conversation.agent.sessionID
    label = conversation.agent.label
    provider = conversation.agent.provider
    conversationID = conversation.conversationID
    savedAt = conversation.savedAt
    omittedMessages = conversation.omittedMessages
    omittedTools = conversation.omittedTools
    turns = conversation.turns.map(SavedTurn.init)
  }

  func conversation() throws -> SavedConversation {
    guard
      UUID(uuidString: machineID) != nil,
      CanonicalConversationID.isCanonical(conversationID, agent: provider ?? "")
    else { throw SavedConversationError.corrupt }
    let agent = AgentRow(
      machineID: machineID, sessionID: sessionID, label: label, branch: nil, working: false,
      provider: provider, status: "unknown")
    return SavedConversation(
      agent: agent, conversationID: conversationID, savedAt: savedAt,
      turns: turns.map(\.turn), omittedMessages: omittedMessages, omittedTools: omittedTools)
  }
}

private struct SavedTurn: Codable {
  let id: String
  let role: String
  let timestamp: String?
  let text: String?
  let parts: [SavedPart]

  init(_ turn: ConversationTurn) {
    id = turn.id
    role = turn.role
    timestamp = turn.timestamp
    text = turn.text
    parts = turn.parts.map(SavedPart.init)
  }

  var turn: ConversationTurn {
    ConversationTurn(
      id: id, role: role, timestamp: timestamp, text: text, parts: parts.map(\.part))
  }
}

private enum SavedPart: Codable {
  case text(String)
  case thinking(String)
  case responseStatus(status: String, detail: String?)
  case toolCall(ToolCall)

  init(_ part: ConversationPart) {
    switch part {
    case .text(let text): self = .text(text)
    case .thinking(let text): self = .thinking(text)
    case .responseStatus(let status, let detail):
      self = .responseStatus(status: status, detail: detail)
    case .toolCall(let call): self = .toolCall(call)
    }
  }

  var part: ConversationPart {
    switch self {
    case .text(let text): .text(text)
    case .thinking(let text): .thinking(text)
    case .responseStatus(let status, let detail): .responseStatus(status: status, detail: detail)
    case .toolCall(let call): .toolCall(call)
    }
  }
}
