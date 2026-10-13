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

/// A reply shown inline up to the preview limit, with the full text one tap away.
private struct ReplyText: View {
  let text: String
  @State private var showingFull = false

  var body: some View {
    let preview = messagePreview(text)
    VStack(alignment: .leading, spacing: 4) {
      Text(markdownText(preview))
        .textSelection(.enabled)
      if preview != text {
        Button("View full message") { showingFull = true }
          .font(.subheadline)
      }
    }
    .sheet(isPresented: $showingFull) {
      NavigationStack {
        ScrollView {
          Text(markdownText(transcriptInlineText(text)))
            .textSelection(.enabled)
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

/// Markdown as text. Headings keep body size and are bold, as the Android transcript shows them.
func markdownText(_ raw: String) -> AttributedString {
  guard
    var rendered = try? AttributedString(
      markdown: raw,
      options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .full))
  else { return AttributedString(raw) }
  for run in rendered.runs {
    guard let intent = run.presentationIntent,
      intent.components.contains(where: { if case .header = $0.kind { true } else { false } })
    else { continue }
    rendered[run.range].font = .body.bold()
  }
  return rendered
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
