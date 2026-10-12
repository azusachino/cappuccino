import Foundation

/// Ports core/TranscriptActivity.kt: a turn read in order, where prose stays prose and
/// consecutive thinking/tool work becomes one activity group. Tool steps follow
/// pi-pocket's turns.js and transcript.js (MIT); Pi and Claude Code name the same
/// operations and their arguments differently.

public enum TranscriptSegment: Equatable, Sendable {
  case prose(ConversationPart)
  case activity([ConversationPart])
}

extension ConversationPart {
  fileprivate var isActivity: Bool {
    switch self {
    case .thinking, .toolCall: true
    case .text, .responseStatus: false
    }
  }
}

func transcriptSegments(_ parts: [ConversationPart]) -> [TranscriptSegment] {
  var segments: [TranscriptSegment] = []
  var activity: [ConversationPart] = []
  for part in parts {
    if part.isActivity {
      activity.append(part)
      continue
    }
    if !activity.isEmpty { segments.append(.activity(activity)) }
    activity = []
    segments.append(.prose(part))
  }
  if !activity.isEmpty { segments.append(.activity(activity)) }
  return segments
}

public enum ToolKind: Equatable, Sendable {
  case read, edit, write, command, search, list, other

  public var glyph: String {
    switch self {
    case .read: "→"
    case .edit: "✎"
    case .write: "+"
    case .command: "$"
    case .search, .list: "⌕"
    case .other: "·"
    }
  }
}

func toolKind(_ name: String) -> ToolKind {
  switch name.lowercased() {
  case "read", "view", "notebookread": .read
  case "edit", "multiedit", "notebookedit", "apply_patch": .edit
  case "write": .write
  case "bash", "shell", "exec", "run_command": .command
  case "grep", "find", "glob", "search": .search
  case "ls": .list
  default: .other
  }
}

/// Tool input as an object; anything else counts as no arguments.
private func arguments(_ part: ToolCall) -> BridgeJSON? {
  try? BridgeJSON.parse(Data(part.input.utf8))
}

extension BridgeJSON {
  /// The first non-blank string among `keys`. Non-string and blank values are skipped.
  fileprivate func text(_ keys: String...) -> String? {
    for key in keys {
      if let value = fields[key] as? String,
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
      {
        return value
      }
    }
    return nil
  }

  fileprivate func number(_ key: String) -> Int64? {
    try? optionalLong(key)
  }
}

private func pathOf(_ args: BridgeJSON?) -> String? {
  args?.text("path", "file_path", "notebook_path")
}

/// Splits on "\n" only, as Kotlin's split('\n') does, so a CRLF keeps its carriage return.
private func lines(_ text: String) -> [String] {
  text.components(separatedBy: "\n")
}

/// What a group did, in the order first done: "Thought, searched, read 2 files, ran 1 command".
public func activitySummary(_ parts: [ConversationPart]) -> String {
  struct Did {
    let noun: String
    var things: Set<String>
  }
  var order: [String] = []
  var did: [String: [Did]] = [:]
  func ensure(_ verb: String) {
    if did[verb] == nil {
      did[verb] = []
      order.append(verb)
    }
  }
  for (index, part) in parts.enumerated() {
    switch part {
    case .thinking:
      ensure("thought")
    case .toolCall(let call):
      let args = arguments(call)
      let (verb, noun, target): (String, String?, String?) =
        switch toolKind(call.name) {
        case .read: ("read", "file", pathOf(args))
        case .edit: ("edited", "file", pathOf(args))
        case .write: ("wrote", "file", pathOf(args))
        case .command: ("ran", "command", nil)
        case .search: ("searched", nil, nil)
        case .list: ("listed", "folder", pathOf(args))
        case .other: ("used \(call.name.lowercased())", nil, nil)
        }
      ensure(verb)
      guard let noun else { continue }
      // A path names one thing however often it is touched; a command is counted per call.
      let thing = target ?? "call:\(index)"
      if let position = did[verb]?.firstIndex(where: { $0.noun == noun }) {
        did[verb]?[position].things.insert(thing)
      } else {
        did[verb]?.append(Did(noun: noun, things: [thing]))
      }
    case .text, .responseStatus:
      continue
    }
  }
  var previousNoun: String?
  var pieces: [String] = []
  for verb in order {
    let nouns = did[verb] ?? []
    var counted: [String] = []
    for (position, entry) in nouns.enumerated() {
      let count = entry.things.count
      // "Edited 1 file, wrote 1": a noun just said is not said again.
      if position == 0 && entry.noun == previousNoun {
        counted.append("\(count)")
      } else {
        counted.append("\(count) \(plural(count, entry.noun))")
      }
    }
    previousNoun = nouns.last?.noun
    pieces.append(counted.isEmpty ? verb : "\(verb) \(counted.joined(separator: " and "))")
  }
  let summary = pieces.joined(separator: ", ")
  return summary.prefix(1).uppercased() + summary.dropFirst()
}

public struct StepWords: Equatable, Sendable {
  public let label: String
  public let subject: String
}

/// A step's bold label and muted subject: the tool, and what it touched or ran.
public func stepWords(_ call: ToolCall) -> StepWords {
  let args = arguments(call)
  let subject: String
  switch toolKind(call.name) {
  case .read:
    let range: String =
      args.flatMap { a in
        a.number("offset").map { offset in
          ":\(offset)" + (a.number("limit").map { "+\($0)" } ?? "")
        }
      } ?? ""
    subject = (pathOf(args) ?? "") + range
  case .edit, .write, .list:
    subject = pathOf(args) ?? ""
  case .command:
    subject = args?.text("command", "cmd") ?? ""
  case .search:
    subject = [args?.text("pattern", "query"), pathOf(args)].compactMap { $0 }.joined(
      separator: "  ")
  case .other:
    subject = ""
  }
  return StepWords(label: call.name.lowercased(), subject: lines(subject).first ?? "")
}

public enum Tone: Equatable, Sendable {
  case plain, ok, err
}

public struct StepOutcome: Equatable, Sendable {
  public let text: String
  public let tone: Tone

  public init(_ text: String, tone: Tone = .plain) {
    self.text = text
    self.tone = tone
  }
}

private func lineCount(_ text: String) -> Int {
  if text.isEmpty { return 0 }
  return lines(text.hasSuffix("\n") ? String(text.dropLast()) : text).count
}

private func plural(_ count: Int, _ word: String, many: String? = nil) -> String {
  count == 1 ? word : (many ?? "\(word)s")
}

private func countPhrase(_ count: Int, _ word: String, many: String? = nil) -> String {
  "\(count) \(plural(count, word, many: many))"
}

/// Lines added and removed, ignoring lines an edit's old and new text share at either end.
private func editCounts(old: String, new: String) -> (added: Int, removed: Int) {
  let before = lines(old)
  let after = lines(new)
  var head = 0
  while head < before.count, head < after.count, before[head] == after[head] { head += 1 }
  var tail = 0
  while tail < before.count - head, tail < after.count - head,
    before[before.count - 1 - tail] == after[after.count - 1 - tail]
  {
    tail += 1
  }
  return (after.count - head - tail, before.count - head - tail)
}

private func matches(_ text: String, _ pattern: String, _ options: NSRegularExpression.Options = [])
  -> Bool
{
  guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else {
    return false
  }
  return regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)) != nil
}

/// First capture group of `pattern`, like Kotlin's Regex.find(...)?.groupValues?.get(1).
private func capture(
  _ text: String, _ pattern: String, _ options: NSRegularExpression.Options = []
) -> String? {
  guard let regex = try? NSRegularExpression(pattern: pattern, options: options),
    let match = regex.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
    match.numberOfRanges > 1,
    let range = Range(match.range(at: 1), in: text)
  else { return nil }
  return String(text[range])
}

private func replacing(_ text: String, _ pattern: String, with replacement: String = "") -> String {
  guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
  return regex.stringByReplacingMatches(
    in: text, range: NSRange(text.startIndex..., in: text), withTemplate: replacement)
}

private let testCommandPattern =
  #"\b(?:test|tests|spec|jest|vitest|pytest|mocha|rspec|phpunit|ctest|gradlew)\b"#

/// A test runner's own summary ("48 passed · 3.1s"); never guessed for other output.
func testOutcome(_ output: String, command: String) -> String? {
  guard
    matches(command, testCommandPattern, .caseInsensitive)
      || matches(output, #"(?m)^(?:# pass|ℹ pass|Tests:) "#)
  else { return nil }
  func count(_ node: String, _ word: String, _ alternate: String) -> String? {
    capture(output, "(?m)^(?:#|ℹ) \(node) (\\d+)")
      ?? capture(output, "(?m)^\\s*Tests:?\\s+(?:[^\\n]*?\\D)?(\\d+) \(word)")
      ?? capture(output, "\\b(\\d+) (?:\(word)|\(alternate))\\b")
  }
  guard let passed = count("pass", "passed", "passing") else { return nil }
  let failed = count("fail", "failed", "failing").flatMap { Int($0) }.flatMap { $0 > 0 ? $0 : nil }
  let seconds = capture(output, #"\b(?:in|Duration|Time:)\s+([\d.]+)\s?s\b"#).flatMap {
    Double($0)
  }
  var pieces = ["\(passed) passed"]
  if let failed { pieces.append("\(failed) failed") }
  if let seconds {
    pieces.append(seconds < 10 ? String(format: "%.1fs", seconds) : "\(Int(seconds))s")
  }
  return pieces.joined(separator: " · ")
}

/// A failed call's error, short: an exit code, or the first line that says something.
func shortError(_ output: String?) -> String {
  let text = output ?? ""
  if let code = capture(text, "(?i)(?:exited with code|exit code:?) (\\d+)") {
    return "exit \(code)"
  }
  if matches(text, "(?i)timed out") { return "timed out" }
  let line = lines(text)
    .map { replacing($0.trimmingCharacters(in: .whitespaces), #"^\[[\w -]+\]\s*"#) }
    .first { !$0.isEmpty && !matches($0, #"^</?[\w-]+>$"#) }
  guard let line else { return "failed" }
  let clean = replacing(line, "(?i)^error:\\s*")
  return clean.count > 48 ? String(clean.prefix(47)) + "…" : clean
}

/// What a call came to, from what it recorded. nil when nothing is recorded or worth saying.
func stepOutcome(_ call: ToolCall) -> StepOutcome? {
  switch call.activityState {
  case .failed:
    return StepOutcome(shortError(call.output), tone: .err)
  case .resultNotRecorded:
    return nil
  case .resultAvailable:
    return availableOutcome(call)
  }
}

private func availableOutcome(_ call: ToolCall) -> StepOutcome? {
  let output = call.output ?? ""
  let args = arguments(call)
  switch toolKind(call.name) {
  case .edit:
    let edits = editPairs(args)
    guard !edits.isEmpty else { return nil }
    let totals = edits.reduce((added: 0, removed: 0)) { sum, pair in
      let counts = editCounts(old: pair.old, new: pair.new)
      return (sum.added + counts.added, sum.removed + counts.removed)
    }
    return StepOutcome("+\(totals.added) −\(totals.removed)", tone: .ok)
  case .write:
    return args?.text("content").map { StepOutcome("+\(lineCount($0))", tone: .ok) }
  case .read:
    let images = call.images ?? 0
    if !output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      return StepOutcome(countPhrase(lineCount(output), "line"))
    }
    if images > 0 {
      return StepOutcome(images == 1 ? "image" : "\(images) images")
    }
    return nil
  case .search:
    if matches(output, "^(?:No matches|No files found)") { return StepOutcome("no matches") }
    if output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return nil }
    return StepOutcome(
      countPhrase(
        lineCount(output.trimmingCharacters(in: .whitespacesAndNewlines)), "match", many: "matches")
    )
  case .list:
    if output.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      return StepOutcome("none")
    }
    return StepOutcome(
      countPhrase(
        lineCount(output.trimmingCharacters(in: .whitespacesAndNewlines)), "entry", many: "entries")
    )
  case .command:
    if let summary = testOutcome(output, command: args?.text("command", "cmd") ?? "") {
      return StepOutcome(summary, tone: .ok)
    }
    let count = lineCount(output.trimmingCharacters(in: .whitespacesAndNewlines))
    return StepOutcome(count == 0 ? "no output" : countPhrase(count, "line"))
  case .other:
    return nil
  }
}

/// Edit input is one old/new pair, or an `edits` array of pairs. Malformed arrays count as none.
private func editPairs(_ args: BridgeJSON?) -> [(old: String, new: String)] {
  guard let args else { return [] }
  if let old = args.text("old_string", "oldText") {
    return [(old, args.text("new_string", "newText") ?? "")]
  }
  guard let rows = try? args.array("edits") else { return [] }
  return rows.map {
    ($0.text("old_string", "oldText") ?? "", $0.text("new_string", "newText") ?? "")
  }
}

/// Wall time from the first recorded start to the last recorded result, in milliseconds.
func activityDurationMillis(_ parts: [ConversationPart]) -> Int? {
  let tools = parts.compactMap { part -> ToolCall? in
    if case .toolCall(let call) = part { return call }
    return nil
  }
  guard let start = tools.compactMap({ instant($0.startedAt) }).min(),
    let end = tools.compactMap({ instant($0.finishedAt) }).max()
  else { return nil }
  return durationMillis(from: start, to: end)
}

func toolDurationMillis(_ call: ToolCall) -> Int? {
  guard let start = instant(call.startedAt), let end = instant(call.finishedAt) else { return nil }
  return durationMillis(from: start, to: end)
}

private func durationMillis(from start: Date, to end: Date) -> Int? {
  let millis = Int(((end.timeIntervalSince(start)) * 1000).rounded())
  return millis < 0 ? nil : millis
}

func compactDuration(millis: Int) -> String {
  switch millis {
  case ..<1_000: return "\(millis)ms"
  case ..<10_000: return String(format: "%.1fs", Double(millis) / 1_000)
  case ..<60_000: return "\(millis / 1_000)s"
  default: return "\(millis / 60_000)m \((millis / 1_000) % 60)s"
  }
}

private func instant(_ value: String?) -> Date? {
  guard let value else { return nil }
  if let date = try? Date(
    value, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
  {
    return date
  }
  return try? Date(value, strategy: Date.ISO8601FormatStyle())
}
