import CappuccinoCore
import SwiftUI

/// The conversation page for the selected agent: its header, transcript, live status and
/// the jump-to-latest control. Following is the default; the reader's own scroll or drag stops it.
@MainActor public struct ConversationScreen: View {
  let machines: MachineConnections
  let session: SessionModel
  @State private var follow = FollowState()
  @State private var hasNewOutput = false
  @State private var showingDetails = false
  @State private var promptOpen = false
  @Environment(\.colorScheme) private var colorScheme

  public init(machines: MachineConnections, session: SessionModel) {
    self.machines = machines
    self.session = session
  }

  public var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    ScrollViewReader { proxy in
      ScrollView {
        LazyVStack(alignment: .leading, spacing: 14) {
          if session.omittedTurns > 0 {
            Text("Older messages aren't loaded.")
              .font(.footnote)
              .foregroundStyle(palette.muted)
          }
          ForEach(chatTurns(session.turns)) { turn in
            TurnRow(
              turn: turn, newest: newestTurnIDs(session.turns).contains(turn.id),
              assistantLabel: session.selectedAgent?.label ?? "Assistant")
          }
          LiveStatusLine(status: liveStatusWord, detail: session.stream.agentStatusDetail)
          if let error = session.streamError {
            Text(error).foregroundStyle(palette.error)
          }
          if let error = session.actionError {
            Text(error).foregroundStyle(palette.error).accessibilityIdentifier("action-error")
          }
          Color.clear
            .frame(height: 1)
            .id("bottom")
            .onAppear { follow.readerMoved(distanceFromBottom: 0) }
            .onDisappear { follow.readerMoved(distanceFromBottom: .infinity) }
        }
        .padding(16)
      }
      .simultaneousGesture(DragGesture().onChanged { _ in follow.dragged() })
      .onChange(of: session.turns.count) { _, _ in
        if follow.following {
          hasNewOutput = false
          proxy.scrollTo("bottom", anchor: .bottom)
        } else {
          hasNewOutput = true
        }
      }
      .sheet(isPresented: $promptOpen) {
        if let prompt = session.stream.pendingPrompt {
          PromptSheet(session: session, prompt: prompt)
        }
      }
      .bottomComposer {
        Composer(session: session)
          .padding(.horizontal, 12)
          .padding(.bottom, 4)
      }
      .overlay(alignment: .bottomTrailing) {
        if !follow.following {
          JumpToLatestButton(hasNewOutput: hasNewOutput) {
            follow.jumpToLatest()
            hasNewOutput = false
            withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
          }
          .padding(16)
        }
      }
    }
    .onChange(of: session.stream.pendingPrompt?.promptID) { _, promptID in
      promptOpen = promptID != nil
    }
    .navigationTitle(session.selectedAgent?.label ?? "Conversation")
    .inlineTitle()
    .toolbar {
      ToolbarItem(placement: .principal) {
        VStack(spacing: 0) {
          Text(session.selectedAgent?.label ?? "").font(.headline)
          Text(headerDetail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
      }
      ToolbarItem(placement: .primaryAction) {
        Menu {
          Button("Connection details", systemImage: "info.circle") { showingDetails = true }
        } label: {
          Label("Conversation actions", systemImage: "ellipsis.circle")
        }
      }
    }
    .sheet(isPresented: $showingDetails) {
      if let profile = session.selectedProfile {
        ConnectionDetailsSheet(profile: profile, state: machines.connections[profile.id])
      }
    }
  }

  private var liveStatusWord: String {
    liveStatus(
      pendingPrompt: session.stream.pendingPrompt != nil,
      streamStatus: session.stream.agentStatus,
      listedStatus: session.selectedAgent?.working == true ? "working" : "idle")
  }

  private var headerDetail: String {
    let state = statusWord(liveStatusWord)
    let machine = session.selectedProfile?.name ?? ""
    let branch = session.selectedAgent?.branch
    return [state, machine, branch].compactMap { $0 }.filter { !$0.isEmpty }.joined(
      separator: " · ")
  }
}

/// The word for a live status. Colour never carries it alone.
func statusWord(_ status: String) -> String {
  switch status {
  case "working": "Working"
  case "blocked": "Waiting for your answer"
  case "": "Idle"
  default: "Idle"
  }
}

private struct JumpToLatestButton: View {
  let hasNewOutput: Bool
  let action: () -> Void

  var body: some View {
    Button(action: action) {
      Image(systemName: "arrow.down")
        .font(.headline)
        .frame(width: 44, height: 44)
    }
    .modifier(GlassCircle())
    .accessibilityLabel(hasNewOutput ? "Jump to new output" : "Jump to latest")
  }
}

/// Liquid Glass circle on iOS 26 and later; a material circle before.
private struct GlassCircle: ViewModifier {
  func body(content: Content) -> some View {
    if #available(iOS 26, macOS 26, *) {
      content.glassEffect(.regular.interactive(), in: .circle)
    } else {
      content.background(.regularMaterial, in: Circle())
    }
  }
}

private struct LiveStatusLine: View {
  let status: String
  let detail: String?

  var body: some View {
    if status == "working" || status == "blocked" {
      VStack(alignment: .leading, spacing: 2) {
        Label(
          statusWord(status),
          systemImage: status == "working" ? "ellipsis.circle" : "questionmark.circle"
        )
        .font(.subheadline)
        if let detail, !detail.isEmpty {
          Text(detail).font(.caption).foregroundStyle(.secondary)
        }
      }
    }
  }
}
