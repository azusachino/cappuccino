import CappuccinoCore
import Foundation
import SwiftUI

/// View model for one agent's live transcript. Owns the stream lifecycle:
/// starts on appear, stops deterministically on disappear/background, and
/// re-applies the reconciliation contract client-side (idempotent ids,
/// visible gaps, reset clears and reloads). Failures are visible, never
/// silently retried.
/// One agent's live active-branch transcript: chat bubbles, monospaced code
/// blocks, expandable tool rows, visible gap placeholders.
public struct TranscriptView: View {
  @StateObject private var model: TranscriptModel
  @Environment(\.scenePhase) private var scenePhase

  public init(model: TranscriptModel) {
    _model = StateObject(wrappedValue: model)
  }

  public var body: some View {
    Group {
      switch model.phase {
      case .loading:
        ProgressView("Connecting to stream…")
      case .failed(let message):
        // Disconnect banner over preserved history: the unread transcript
        // stays visible and the failure is explicit — no blind retry.
        VStack(spacing: 0) {
          banner(message)
          transcript
        }
      case .streaming:
        transcript
      }
    }
    .navigationTitle(model.session)
    #if os(iOS)
      .navigationBarTitleDisplayMode(.inline)
    #endif
    .onAppear { model.start() }
    .onDisappear { model.stop() }
    .onChange(of: scenePhase) { _, phase in
      // Backgrounding stops the stream; .inactive (launch/interruption
      // transitions) does not — it is not the background state.
      if phase == .active {
        model.sceneBecameActive()
      } else if phase == .background {
        model.sceneBecameInactive()
      }
    }
  }

  private func banner(_ message: String) -> some View {
    HStack(spacing: 8) {
      Image(systemName: "antenna.radiowaves.left.and.right.slash")
      Text(message)
        .font(.footnote)
      Spacer()
      Button("Reconnect") { model.start() }
        .font(.footnote.weight(.medium))
        .accessibilityIdentifier("transcript-retry")
    }
    .padding(10)
    .background(Color.orange.opacity(0.18))
    .accessibilityElement(children: .combine)
    .accessibilityLabel("Stream unavailable: \(message)")
    .accessibilityIdentifier("stream-banner")
  }

  private var transcript: some View {
    ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 12) {
          if let branch = model.branch {
            Label(branch, systemImage: "arrow.triangle.branch")
              .font(.caption)
              .foregroundStyle(.secondary)
              .accessibilityIdentifier("transcript-branch")
          }
          ForEach(model.entries) { entry in
            if entry.isGap {
              TranscriptGapRow(entry: entry)
                .id(entry.id)
            } else if let tool = entry.tool {
              TranscriptToolRow(entry: entry, tool: tool)
                .id(entry.id)
            } else {
              TranscriptBubbleRow(entry: entry)
                .id(entry.id)
            }
          }
        }
        .padding()
        // Live tail: keep the newest entry on screen (chat behavior).
        .onChange(of: model.entries.count) { _, _ in
          if let last = model.entries.last {
            proxy.scrollTo(last.id, anchor: .bottom)
          }
        }
      }
    }
  }
}

struct TranscriptBubbleRow: View {
  let entry: TranscriptEntry

  var body: some View {
    HStack {
      if entry.isUser { Spacer(minLength: 48) }
      VStack(alignment: .leading, spacing: 4) {
        ForEach(Array(EntryContent.segments(of: entry.text).enumerated()), id: \.offset) {
          _, segment in
          EntryContent.SegmentView(segment: segment)
        }
      }
      .padding(10)
      .background(
        entry.isUser ? Color.accentColor.opacity(0.18) : Color.gray.opacity(0.12)
      )
      .clipShape(RoundedRectangle(cornerRadius: 12))
      if !entry.isUser { Spacer(minLength: 48) }
    }
    .accessibilityElement(children: .combine)
    .accessibilityLabel(
      entry.isUser ? "You said: \(entry.text)" : "Agent: \(entry.text)")
  }
}

/// Splits an entry into typed segments preserving original order: prose and
/// ```-fenced code interleave exactly as authored.
enum EntryContent {
  enum Segment: Equatable {
    case text(String)
    case code(String)
  }

  static func segments(of text: String) -> [Segment] {
    var result: [Segment] = []
    var buffer: [String] = []
    var inCode = false
    func flush() {
      guard !buffer.isEmpty else { return }
      let joined = buffer.joined(separator: "\n")
      result.append(inCode ? .code(joined) : .text(joined))
      buffer = []
    }
    for line in text.components(separatedBy: "\n") {
      if line.hasPrefix("```") {
        flush()
        inCode.toggle()
        continue
      }
      buffer.append(line)
    }
    flush()
    return result
  }

  struct SegmentView: View {
    let segment: Segment

    var body: some View {
      switch segment {
      case .code(let code):
        Text(code)
          .font(.system(.footnote, design: .monospaced))
          .padding(8)
          .frame(maxWidth: .infinity, alignment: .leading)
          .background(Color.gray.opacity(0.07))
          .clipShape(RoundedRectangle(cornerRadius: 8))
          .accessibilityLabel("Code: \(code)")
      case .text(let text):
        Text(text)
          .font(.body)
      }
    }
  }
}

struct TranscriptToolRow: View {
  let entry: TranscriptEntry
  let tool: TranscriptToolCall
  @State private var expanded = false

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Button {
        withAnimation { expanded.toggle() }
      } label: {
        HStack(spacing: 6) {
          Image(systemName: expanded ? "chevron.down" : "chevron.right")
            .font(.caption2)
          Text("Worked for: \(tool.name)")
            .font(.subheadline.weight(.medium))
          Spacer()
        }
      }
      .accessibilityLabel("Worked for: \(tool.name)")
      .accessibilityHint(expanded ? "Collapses tool details" : "Expands tool details")
      .accessibilityIdentifier("tool-row-\(tool.name)")
      if expanded {
        VStack(alignment: .leading, spacing: 4) {
          ForEach(tool.args.sorted(by: { $0.key < $1.key }), id: \.key) { key, value in
            Text("\(key): \(value)")
              .font(.system(.caption, design: .monospaced))
          }
          if let detail = tool.detail {
            Text(detail)
              .font(.caption)
              .foregroundStyle(.secondary)
          }
        }
        .padding(8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color.gray.opacity(0.07))
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .accessibilityIdentifier("tool-details-\(tool.name)")
      }
    }
  }
}

struct TranscriptGapRow: View {
  let entry: TranscriptEntry

  var body: some View {
    HStack(spacing: 8) {
      Rectangle().fill(Color.secondary.opacity(0.4)).frame(height: 1)
      Text(entry.text)
        .font(.caption)
        .foregroundStyle(.secondary)
      Rectangle().fill(Color.secondary.opacity(0.4)).frame(height: 1)
    }
    .accessibilityElement(children: .combine)
    .accessibilityLabel("Missing entries: \(entry.text)")
    .accessibilityIdentifier("gap-row")
  }
}
