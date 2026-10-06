import Foundation

// Transcript domain models per behavior-spec v0 (history/reconciliation) and
// fixtures/history-active-branch.json shapes: one entry is a chat line with
// an optional tool call; gap entries are visible placeholders (seq null).

public struct TranscriptToolCall: Equatable, Codable, Sendable {
  public let name: String
  public let args: [String: String]
  public let detail: String?

  public init(name: String, args: [String: String], detail: String?) {
    self.name = name
    self.args = args
    self.detail = detail
  }
}

public struct TranscriptEntry: Identifiable, Equatable, Codable, Sendable {
  public let seq: Int?
  public let id: String
  public let kind: String  // "user" | "assistant" | "gap"
  public let text: String
  public let branch: String?
  public let complete: Bool
  public let tool: TranscriptToolCall?

  /// Stable identity for dedupe/reconciliation; the bridge supplies ids on
  /// the wire, fixtures derive one deterministically.
  public init(
    seq: Int?, id: String?, kind: String, text: String, branch: String?, complete: Bool,
    tool: TranscriptToolCall?
  ) {
    self.seq = seq
    self.id = id ?? Self.synthesizedID(seq: seq, kind: kind, text: text)
    self.kind = kind
    self.text = text
    self.branch = branch
    self.complete = complete
    self.tool = tool
  }

  static func synthesizedID(seq: Int?, kind: String, text: String) -> String {
    let seqPart = seq.map(String.init) ?? "null"
    return "\(kind)-\(seqPart)-\(text.hashValue)"
  }

  public var isGap: Bool { kind == "gap" }
  public var isUser: Bool { kind == "user" }
}

public struct TranscriptFixture: Equatable, Sendable {
  public let sessionID: String
  public let activeBranch: String?
  public let entries: [TranscriptEntry]

  /// Maps fixtures/history-active-branch.json shapes.
  public static func load(from data: Data) throws -> TranscriptFixture {
    let object = try JSONDecoder().decode(FixtureDocument.self, from: data)
    return object.transcriptFixture
  }

  /// Coding keys keep the fixture's snake_case wire names out of the model.
  private struct FixtureDocument: Decodable {
    let sessionID: String
    let activeBranch: String?
    let entries: [WireEntry]

    enum CodingKeys: String, CodingKey {
      case sessionID = "session_id"
      case activeBranch = "active_branch"
      case entries
    }

    var transcriptFixture: TranscriptFixture {
      TranscriptFixture(
        sessionID: sessionID,
        activeBranch: activeBranch,
        entries: entries.map(\.transcriptEntry)
      )
    }
  }

  private struct WireEntry: Decodable {
    let seq: Int?
    let kind: String
    let text: String
    let tool: ToolPayload?

    var transcriptEntry: TranscriptEntry {
      TranscriptEntry(
        seq: seq, id: nil, kind: kind, text: text, branch: nil, complete: true,
        tool: tool.map(\.toolCall))
    }
  }

  private struct ToolPayload: Decodable {
    let name: String
    let args: [String: JSONValueBox]
    let detail: String?

    var toolCall: TranscriptToolCall {
      TranscriptToolCall(name: name, args: args.mapValues(\.base), detail: detail)
    }
  }

  /// Tool args are free-form JSON in the fixture; rendered as key: value.
  private struct JSONValueBox: Decodable {
    let base: String

    init(from decoder: Decoder) throws {
      let container = try decoder.singleValueContainer()
      if let text = try? container.decode(String.self) {
        base = text
      } else if let number = try? container.decode(Double.self) {
        base =
          number.truncatingRemainder(dividingBy: 1) == 0
          ? String(Int(number)) : String(number)
      } else if let flag = try? container.decode(Bool.self) {
        base = flag ? "true" : "false"
      } else if container.decodeNil() {
        base = "null"
      } else if let dictionary = try? container.decode([String: JSONValueBox].self) {
        base =
          dictionary
          .sorted(by: { $0.key < $1.key })
          .map { key, value in "\(key): \(value.base)" }
          .joined(separator: ", ")
      } else if let array = try? container.decode([JSONValueBox].self) {
        base = array.map(\.base).joined(separator: ", ")
      } else {
        base = "unreadable value"
      }
    }
  }
}
