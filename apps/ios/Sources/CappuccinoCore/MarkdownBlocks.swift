import Foundation

/// One block of a Markdown reply. `AttributedString(markdown:)` keeps the block structure only
/// as each run's `presentationIntent`; a single `Text` would run paragraphs, list items and
/// headings together, so replies are laid out block by block.
public struct MarkdownBlock: Equatable, Sendable {
  public enum Kind: Equatable, Sendable {
    case paragraph
    case heading(level: Int)
    case code(language: String?)
    /// `marker` is "•" or "3."; `depth` counts enclosing lists from 1.
    case listItem(marker: String, depth: Int)
    case quote
    case rule
  }

  public var kind: Kind
  public var text: AttributedString

  public init(kind: Kind, text: AttributedString) {
    self.kind = kind
    self.text = text
  }
}

/// The reply's blocks in order; plain text when it isn't Markdown.
public func markdownBlocks(_ raw: String) -> [MarkdownBlock] {
  guard
    let parsed = try? AttributedString(
      markdown: raw,
      options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .full))
  else { return [MarkdownBlock(kind: .paragraph, text: AttributedString(raw))] }

  var blocks: [MarkdownBlock] = []
  var current: PresentationIntent?? = nil
  for run in parsed.runs {
    let intent = run.presentationIntent
    var piece = AttributedString(parsed[run.range])
    piece.presentationIntent = nil
    if let last = current, last == intent, !blocks.isEmpty {
      blocks[blocks.count - 1].text.append(piece)
    } else {
      blocks.append(MarkdownBlock(kind: kind(of: intent), text: piece))
      current = .some(intent)
    }
  }
  return blocks.compactMap { block in
    var block = block
    if case .code = block.kind {
      // Code keeps its own line breaks but not the fence's trailing one.
      let trimmed = String(block.text.characters).trimmingCharacters(in: .newlines)
      block.text = AttributedString(trimmed)
    }
    if case .rule = block.kind { return block }
    return block.text.characters.isEmpty ? nil : block
  }
}

private func kind(of intent: PresentationIntent?) -> MarkdownBlock.Kind {
  guard let components = intent?.components else { return .paragraph }
  // Components run from the innermost block outwards.
  var depth = 0
  for component in components {
    if case .orderedList = component.kind { depth += 1 }
    if case .unorderedList = component.kind { depth += 1 }
  }
  for (index, component) in components.enumerated() {
    switch component.kind {
    case .header(let level):
      return .heading(level: level)
    case .codeBlock(let language):
      return .code(language: language)
    case .thematicBreak:
      return .rule
    case .listItem(let ordinal):
      let ordered = components[(index + 1)...].first { component in
        switch component.kind {
        case .orderedList, .unorderedList: true
        default: false
        }
      }
      if case .orderedList = ordered?.kind {
        return .listItem(marker: "\(ordinal).", depth: max(depth, 1))
      }
      return .listItem(marker: "•", depth: max(depth, 1))
    case .blockQuote:
      return .quote
    default:
      continue
    }
  }
  return .paragraph
}
