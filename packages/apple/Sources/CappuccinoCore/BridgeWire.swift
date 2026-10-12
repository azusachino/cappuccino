import CoreFoundation
import Foundation

/// Bridge wire models and fail-closed parsers, ported from core/BridgeProtocol.kt,
/// io/BridgeDetails.kt and core/CanonicalConversationId.kt. Any malformed field
/// throws; a missing advertisement is nil (unknown), never a guess.
let maximumBridgeHTTPBodyBytes = 2 * 1024 * 1024

/// Typed view over one decoded JSON object. Accessors mirror core/JsonObject.kt.
struct BridgeJSON {
  let fields: [String: Any]

  init(_ fields: [String: Any]) {
    self.fields = fields
  }

  static func parse(_ data: Data) throws -> BridgeJSON {
    guard data.count <= maximumBridgeHTTPBodyBytes else {
      throw DaemonClientError.protocolError("HTTP body exceeds local limit")
    }
    guard let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
      throw DaemonClientError.protocolError("Expected JSON object")
    }
    return BridgeJSON(object)
  }

  func string(_ key: String) throws -> String {
    guard let value = fields[key] as? String else { throw invalid(key) }
    return value
  }

  func optionalString(_ key: String) throws -> String? {
    switch fields[key] {
    case nil, is NSNull: return nil
    case let value as String: return value
    default: throw invalid(key)
    }
  }

  func long(_ key: String) throws -> Int64 {
    guard let value = integer(fields[key]) else { throw invalid(key) }
    return value
  }

  func optionalLong(_ key: String) throws -> Int64? {
    switch fields[key] {
    case nil, is NSNull: return nil
    default:
      guard let value = integer(fields[key]) else { throw invalid(key) }
      return value
    }
  }

  func boolean(_ key: String) throws -> Bool {
    guard let value = flag(fields[key]) else { throw invalid(key) }
    return value
  }

  func array(_ key: String) throws -> [BridgeJSON] {
    guard let rows = fields[key] as? [Any] else { throw invalid(key) }
    return try rows.map { row in
      guard let object = row as? [String: Any] else { throw invalid("\(key) row") }
      return BridgeJSON(object)
    }
  }

  func optionalObject(_ key: String) -> BridgeJSON? {
    (fields[key] as? [String: Any]).map(BridgeJSON.init)
  }

  private func invalid(_ key: String) -> DaemonClientError {
    .protocolError("Invalid \(key)")
  }

  private func integer(_ value: Any?) -> Int64? {
    guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID() else {
      return nil
    }
    let integer = number.int64Value
    return Double(integer) == number.doubleValue ? integer : nil
  }

  private func flag(_ value: Any?) -> Bool? {
    guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
      return nil
    }
    return number.boolValue
  }
}

/// Verified handshake metadata. `capabilities` nil means the bridge did not advertise any.
public struct BridgeDetails: Equatable, Sendable {
  public let machineID: UUID
  public let machineName: String?
  public let version: String?
  public let protocolVersion: Int64
  public let capabilities: [String: Bool]?
}

/// Checks `/api/session`'s identity and protocol, then reads its advertisements.
func parseBridgeDetails(_ json: BridgeJSON) throws -> BridgeDetails {
  guard try json.string("event") == "paired", try json.string("plugin") == bridgePluginID else {
    throw DaemonClientError.protocolError("Unsupported bridge session response")
  }
  let protocolVersion = try json.long("protocol")
  guard protocolVersion == 1 else {
    throw DaemonClientError.protocolError(
      "Incompatible bridge protocol \(protocolVersion); app requires 1")
  }
  guard let machineID = UUID(uuidString: try json.string("machine_id")) else {
    throw DaemonClientError.protocolError("Invalid machine identity")
  }
  let capabilities: [String: Bool]?
  switch json.fields["capabilities"] {
  case nil, is NSNull:
    capabilities = nil
  case let object as [String: Any]:
    var flags: [String: Bool] = [:]
    for (key, value) in object {
      guard let flag = value as? NSNumber, CFGetTypeID(flag) == CFBooleanGetTypeID() else {
        throw DaemonClientError.protocolError("Invalid bridge capability: \(key)")
      }
      flags[key] = flag.boolValue
    }
    capabilities = flags
  default:
    throw DaemonClientError.protocolError("Invalid bridge capabilities")
  }
  let name = try json.optionalString("machine_name")?.trimmingCharacters(
    in: .whitespacesAndNewlines)
  return BridgeDetails(
    machineID: machineID,
    machineName: name?.isEmpty == false ? name : nil,
    version: try json.optionalString("bridge_version"),
    protocolVersion: protocolVersion,
    capabilities: capabilities
  )
}

let bridgePluginID = "cappuccino-bridge"

/// A canonical conversation id is `<agent>:<64 lowercase hex>`. The agent prefix must
/// be the provider of the agent that owns it (core/CanonicalConversationId.kt).
public enum CanonicalConversationID {
  static let providers: Set<String> = ["pi", "claude", "agy"]

  public static func isCanonical(_ value: String) -> Bool {
    let parts = value.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
    guard parts.count == 2, providers.contains(String(parts[0])) else { return false }
    let digest = parts[1]
    return digest.count == 64
      && digest.allSatisfy { ("0"..."9").contains($0) || ("a"..."f").contains($0) }
  }

  public static func isCanonical(_ value: String, agent: String) -> Bool {
    value.hasPrefix("\(agent):") && isCanonical(value)
  }
}

public struct PromptOption: Equatable, Sendable {
  public let id: String
  public let label: String
  public let description: String?
}

/// One AskUserQuestion question; screen-read prompts carry none.
public struct PromptQuestion: Equatable, Sendable {
  public let header: String
  public let question: String
  public let multiSelect: Bool
  public let options: [PromptOption]
}

public struct PromptCard: Equatable, Sendable {
  public let promptID: String
  public let type: String
  public let title: String
  public let message: String?
  public let toolName: String?
  public let command: String?
  public let options: [PromptOption]
  public let selectedIndex: Int
  public let questions: [PromptQuestion]
}

/// One question's answer: chosen option indexes and/or one line of own text.
public struct QuestionAnswer: Equatable, Sendable {
  public let selected: [Int]
  public let other: String?

  public init(selected: [Int], other: String?) {
    self.selected = selected
    self.other = other
  }
}

public struct ConversationTurn: Equatable, Sendable {
  public let id: String
  public let role: String
  public let timestamp: String?
  public let text: String?
  public let parts: [ConversationPart]
}

public enum ConversationPart: Equatable, Sendable {
  case text(String)
  case thinking(String)
  case responseStatus(status: String, detail: String?)
  case toolCall(ToolCall)
}

public struct ToolCall: Equatable, Sendable {
  public let name: String
  public let input: String
  public let output: String?
  public let isError: Bool?
  public let startedAt: String?
  public let finishedAt: String?
  public let images: Int?

  /// Mirrors core/ToolActivityState.kt: a missing result is not evidence of activity.
  public var activityState: ToolActivityState {
    if isError == true { return .failed }
    if output != nil { return .resultAvailable }
    return .resultNotRecorded
  }
}

public enum ToolActivityState: Equatable, Sendable {
  case resultNotRecorded
  case resultAvailable
  case failed
}

/// The session's model and context use; the window is known only for some models.
public struct SessionStats: Equatable, Sendable {
  public let model: String?
  public let thinkingLevel: String?
  public let contextTokens: Int64?
  public let contextWindow: Int64?
  public let cachePercent: Int64?
}

func parsePromptCard(_ json: BridgeJSON) throws -> PromptCard {
  PromptCard(
    promptID: try json.string("prompt_id"),
    type: try json.string("type"),
    title: try json.string("title"),
    message: try json.optionalString("message"),
    toolName: try json.optionalString("tool_name"),
    command: try json.optionalString("command"),
    options: try parseOptions(json),
    selectedIndex: Int(try json.long("selected_index")),
    questions: try parseQuestions(json)
  )
}

private func parseOptions(_ json: BridgeJSON) throws -> [PromptOption] {
  try json.array("options").map { option in
    PromptOption(
      id: try option.string("id"),
      label: try option.string("label"),
      description: try option.optionalString("description"))
  }
}

private func parseQuestions(_ json: BridgeJSON) throws -> [PromptQuestion] {
  guard json.fields["questions"] != nil else { return [] }
  return try json.array("questions").map { question in
    PromptQuestion(
      header: try question.optionalString("header") ?? "",
      question: try question.string("question"),
      multiSelect: try question.boolean("multi_select"),
      options: try parseOptions(question))
  }
}

/// Parses `turns`. Like BridgeProtocol.kt, a turn whose parts are malformed keeps
/// its text but loses its parts; the rest of the conversation still parses.
func parseConversationTurns(_ json: BridgeJSON) throws -> [ConversationTurn] {
  try json.array("turns").map { turn in
    ConversationTurn(
      id: try turn.string("id"),
      role: try turn.string("role"),
      timestamp: try turn.optionalString("timestamp"),
      text: try turn.optionalString("text"),
      parts: (try? parseParts(turn)) ?? [])
  }
}

private func parseParts(_ turn: BridgeJSON) throws -> [ConversationPart] {
  try turn.array("parts").compactMap { part -> ConversationPart? in
    switch try part.string("type") {
    case "text":
      return .text(try part.string("text"))
    case "thinking":
      return .thinking(try part.string("text"))
    case "response_status":
      let status = try part.string("status")
      guard status == "aborted" || status == "failed" else { return nil }
      return .responseStatus(status: status, detail: try part.optionalString("detail"))
    case "tool_call":
      return .toolCall(try parseToolCall(part))
    default:
      return nil
    }
  }
}

private func parseToolCall(_ part: BridgeJSON) throws -> ToolCall {
  let isError: Bool?
  switch part.fields["is_error"] {
  case nil, is NSNull:
    isError = nil
  case let flag as NSNumber where CFGetTypeID(flag) == CFBooleanGetTypeID():
    isError = flag.boolValue
  default:
    throw DaemonClientError.protocolError("Invalid tool error flag")
  }
  return ToolCall(
    name: try part.string("name"),
    input: try toolInput(part.fields["input"]),
    output: try part.optionalString("output"),
    isError: isError,
    startedAt: try part.optionalString("started_at"),
    finishedAt: try part.optionalString("finished_at"),
    images: try part.optionalLong("images").map { Int($0) })
}

/// Structured tool input is kept as its JSON text so the disclosure can show it verbatim.
private func toolInput(_ value: Any?) throws -> String {
  guard let value else { return "" }
  if let text = value as? String { return text }
  if value is NSNull { return "null" }
  let data = try JSONSerialization.data(
    withJSONObject: value, options: [.sortedKeys, .fragmentsAllowed])
  return String(decoding: data, as: UTF8.self)
}
