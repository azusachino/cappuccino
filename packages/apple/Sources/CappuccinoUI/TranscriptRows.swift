import CappuccinoCore
import SwiftUI

/// One turn: a prompt from you, or the agent's reply with its activity and outcome.
struct TurnRow: View {
  let turn: ConversationTurn
  let newest: Bool
  let assistantLabel: String

  var body: some View {
    if turn.role.lowercased() == "user" {
      PromptRow(turn: turn)
    } else {
      VStack(alignment: .leading, spacing: 10) {
        Text(assistantLabel).font(.caption.bold()).foregroundStyle(.secondary)
        if let text = turn.text, !text.trimmingCharacters(in: .whitespaces).isEmpty {
          ReplyText(text: text)
        }
        ForEach(Array(transcriptSegments(turn.parts).enumerated()), id: \.offset) { _, segment in
          switch segment {
          case .prose(let part):
            ProseRow(part: part)
          case .activity(let parts):
            ActivityGroupView(parts: parts, startsOpen: newest)
          }
        }
      }
    }
  }
}

private struct PromptRow: View {
  let turn: ConversationTurn

  var body: some View {
    let time = localTime(turn.timestamp)
    VStack(alignment: .leading, spacing: 4) {
      Text("› You \(time?.short ?? "")")
        .font(.caption.bold())
        .foregroundStyle(.secondary)
        .accessibilityLabel(time.map { "You, \($0.full)" } ?? "You")
      Text(turn.text ?? "")
        .textSelection(.enabled)
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(12)
    .background(.quaternary, in: RoundedRectangle(cornerRadius: 12))
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
      ScrollView(.horizontal, showsIndicators: false) {
        Text(block.text).font(.system(.footnote, design: .monospaced))
      }
      .padding(10)
      .background(.quaternary.opacity(0.5), in: .rect(cornerRadius: 8))
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

private struct ProseRow: View {
  let part: ConversationPart

  var body: some View {
    switch part {
    case .text(let text):
      ReplyText(text: text)
    case .thinking(let text):
      Text(text).font(.footnote).foregroundStyle(.secondary)
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
    default:
      EmptyView()
    }
  }
}
