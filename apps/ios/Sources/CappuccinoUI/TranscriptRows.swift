import CappuccinoCore
import SwiftUI

#if canImport(UIKit)
  import UIKit
#elseif canImport(AppKit)
  import AppKit
#endif

/// One turn: a prompt from you as a bubble on the right, or the agent's reply as a card on the
/// left with its thinking (one collapsed row), activity and outcome. Both carry their time, as
/// Android's UserPromptRow and AgentResponseRow do.
struct TurnRow: View {
  let turn: ConversationTurn
  let newest: Bool
  let assistantLabel: String
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let time = localTime(turn.timestamp)
    if turn.role.lowercased() == "user" {
      PromptRow(turn: turn, time: time)
    } else {
      let palette = CatppuccinPalette.current(colorScheme)
      let split = separatingThinking(turn.parts)
      VStack(alignment: .leading, spacing: 10) {
        Text(assistantLabel).font(.caption.bold()).foregroundStyle(palette.accent)
        if let thinking = split.thinking {
          ThinkingRow(text: thinking)
        }
        if let text = turn.text, !text.trimmingCharacters(in: .whitespaces).isEmpty {
          ReplyText(text: text)
        }
        ForEach(Array(transcriptSegments(split.rest).enumerated()), id: \.offset) { _, segment in
          switch segment {
          case .prose(let part):
            ProseRow(part: part)
          case .activity(let parts):
            ActivityGroupView(parts: parts, startsOpen: newest)
          }
        }
        if let time {
          TurnTime(time: time, speaker: assistantLabel)
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
      }
      .padding(12)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(.background.secondary, in: RoundedRectangle(cornerRadius: 12))
      .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(.quaternary, lineWidth: 0.6))
      .padding(.trailing, 24)
    }
  }
}

private struct PromptRow: View {
  let turn: ConversationTurn
  let time: LocalTime?
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    VStack(alignment: .trailing, spacing: 2) {
      Text(turn.text ?? "")
        .textSelection(.enabled)
      if let time {
        TurnTime(time: time, speaker: "You")
      }
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .background(
      palette.accent.opacity(0.12),
      in: UnevenRoundedRectangle(
        topLeadingRadius: 16, bottomLeadingRadius: 16, bottomTrailingRadius: 4,
        topTrailingRadius: 16)
    )
    .padding(.leading, 40)
    .frame(maxWidth: .infinity, alignment: .trailing)
  }
}

/// A turn's local time at the bubble's bottom end; VoiceOver hears who spoke and the full time.
private struct TurnTime: View {
  let time: LocalTime
  let speaker: String

  var body: some View {
    Text(time.short)
      .font(.caption2.monospaced())
      .foregroundStyle(.secondary)
      .accessibilityLabel("\(speaker), \(time.full)")
  }
}

/// A turn's thinking as one row, closed until the reader opens it.
private struct ThinkingRow: View {
  let text: String

  var body: some View {
    DisclosureGroup {
      Text(text)
        .font(.footnote)
        .foregroundStyle(.secondary)
        .textSelection(.enabled)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 4)
    } label: {
      Label("Thinking", systemImage: "brain").font(.subheadline)
    }
    .padding(10)
    .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
  }
}

/// A reply in the transcript, whole up to the inline limit (as Android's transcript shows it),
/// with anything longer one tap away.
private struct ReplyText: View {
  let text: String
  @State private var showingFull = false

  var body: some View {
    let inline = transcriptInlineText(text)
    VStack(alignment: .leading, spacing: 4) {
      MarkdownView(text: inline)
      if inline != text {
        Button("View full message") { showingFull = true }
          .font(.subheadline)
      }
    }
    .sheet(isPresented: $showingFull) {
      NavigationStack {
        ScrollView {
          MarkdownView(text: text)
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .navigationTitle("Message")
        .toolbar {
          ToolbarItem(placement: .confirmationAction) {
            Button("Done") { showingFull = false }
          }
        }
      }
    }
  }
}

/// Markdown laid out block by block: headings at body size in bold (as Android's transcript
/// shows them), paragraphs, list items with their markers, code, quotes and rules.
struct MarkdownView: View {
  let text: String

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      ForEach(Array(markdownBlocks(text).enumerated()), id: \.offset) { _, block in
        blockView(block)
      }
    }
    .textSelection(.enabled)
    .frame(maxWidth: .infinity, alignment: .leading)
  }

  @ViewBuilder
  private func blockView(_ block: MarkdownBlock) -> some View {
    switch block.kind {
    case .paragraph:
      Text(block.text)
    case .heading:
      Text(block.text).font(.body.bold())
    case .listItem(let marker, let depth):
      HStack(alignment: .firstTextBaseline, spacing: 6) {
        Text(marker).foregroundStyle(.secondary)
        Text(block.text)
      }
      .padding(.leading, CGFloat(depth - 1) * 16)
    case .code:
      CodeBlock(code: block.text)
    case .quote:
      Text(block.text)
        .foregroundStyle(.secondary)
        .padding(.leading, 10)
        .overlay(alignment: .leading) {
          Rectangle().fill(.tertiary).frame(width: 3)
        }
    case .rule:
      Divider()
    }
  }
}

/// A code block, scrolled sideways rather than wrapped, with a copy action.
private struct CodeBlock: View {
  let code: AttributedString
  @State private var copied = false

  var body: some View {
    VStack(alignment: .trailing, spacing: 4) {
      Button {
        copyToPasteboard(String(code.characters))
        copied = true
      } label: {
        Label(copied ? "Copied" : "Copy", systemImage: copied ? "checkmark" : "doc.on.doc")
          .font(.caption)
      }
      .buttonStyle(.borderless)
      .accessibilityLabel(copied ? "Code copied" : "Copy code")
      ScrollView(.horizontal, showsIndicators: false) {
        Text(code).font(.system(.footnote, design: .monospaced))
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }
    .padding(10)
    .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 8))
    .task(id: copied) {
      guard copied else { return }
      try? await Task.sleep(for: .seconds(2))
      copied = false
    }
  }
}

private func copyToPasteboard(_ text: String) {
  #if canImport(UIKit)
    UIPasteboard.general.string = text
  #elseif canImport(AppKit)
    NSPasteboard.general.clearContents()
    NSPasteboard.general.setString(text, forType: .string)
  #endif
}

private struct ProseRow: View {
  let part: ConversationPart

  var body: some View {
    switch part {
    case .text(let text):
      ReplyText(text: text)
    case .thinking(let text):
      Text(text).font(.footnote).foregroundStyle(.secondary)
    case .responseStatus(let status, _) where status == "failed":
      // Android keeps a failed response out of the chat (`visibleInChat`).
      EmptyView()
    case .responseStatus(let status, let detail):
      VStack(alignment: .leading, spacing: 2) {
        Label(
          status == "aborted" ? "Response aborted" : "Response failed",
          systemImage: status == "aborted" ? "xmark.circle" : "exclamationmark.triangle"
        )
        .font(.subheadline.bold())
        if let detail, !detail.isEmpty {
          Text(detail).font(.caption).foregroundStyle(.secondary)
        }
      }
      .padding(12)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
    case .toolCall:
      EmptyView()
    }
  }
}

/// Activity: one collapsed summary with its duration; expanded, a step per tool call.
struct ActivityGroupView: View {
  let parts: [ConversationPart]
  @State private var expanded: Bool

  init(parts: [ConversationPart], startsOpen: Bool) {
    self.parts = parts
    _expanded = State(initialValue: startsOpen)
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Button {
        expanded.toggle()
      } label: {
        HStack {
          Text(activitySummary(parts))
            .font(.subheadline)
            .frame(maxWidth: .infinity, alignment: .leading)
          if let millis = activityDurationMillis(parts) {
            Text(compactDuration(millis: millis)).font(.caption).foregroundStyle(.secondary)
          }
          Image(systemName: expanded ? "chevron.down" : "chevron.right")
            .font(.footnote)
            .foregroundStyle(.tertiary)
        }
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityHint(expanded ? "Collapses the activity" : "Shows each step")
      if expanded {
        ForEach(Array(parts.enumerated()), id: \.offset) { _, part in
          StepRow(part: part)
        }
      }
    }
    .padding(10)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 10))
  }
}

private struct StepRow: View {
  let part: ConversationPart

  var body: some View {
    switch part {
    case .thinking(let text):
      Text("Thought: \(text)").font(.footnote).foregroundStyle(.secondary)
    case .toolCall(let call):
      let words = stepWords(call)
      let outcome = stepOutcome(call)
      // Each step opens to what the tool was given and what it returned, as Android's does.
      DisclosureGroup {
        VStack(alignment: .leading, spacing: 6) {
          if !call.input.isEmpty {
            Text(call.input).textSelection(.enabled)
          }
          if let output = call.output, !output.isEmpty {
            Text(output)
              .foregroundStyle(call.isError == true ? .red : .secondary)
              .textSelection(.enabled)
          } else if call.output == nil {
            Text("No result recorded").foregroundStyle(.secondary)
          }
        }
        .font(.caption.monospaced())
        .padding(.top, 4)
      } label: {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
          Text(toolKind(call.name).glyph).frame(width: 14)
          Text(words.label).bold()
          Text(words.subject).foregroundStyle(.secondary).lineLimit(1)
          Spacer(minLength: 0)
          if let outcome {
            Text(outcome.text).foregroundStyle(outcome.tone == .err ? .red : .secondary)
          }
        }
        .font(.footnote.monospaced())
      }
    default:
      EmptyView()
    }
  }
}
