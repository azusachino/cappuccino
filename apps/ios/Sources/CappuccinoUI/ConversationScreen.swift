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
  @State private var showingHistory = false
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
          if session.isLoadingHistory, session.turns.isEmpty {
            HStack(spacing: 8) {
              ProgressView()
              Text("Loading history…").foregroundStyle(palette.muted)
            }
            .accessibilityIdentifier("loading-history")
          } else if !session.isLive, session.streamError == nil, session.turns.isEmpty {
            HStack(spacing: 8) {
              ProgressView()
              Text("Connecting…").foregroundStyle(palette.muted)
            }
            .accessibilityIdentifier("connecting")
          }
          ForEach(chatTurns(session.turns)) { turn in
            TurnRow(
              turn: turn, newest: newestTurnIDs(session.turns).contains(turn.id),
              assistantLabel: session.selectedAgent?.label ?? "Assistant")
          }
          // With no canonical history, the live output stands in, labelled as Android labels it.
          if session.turns.isEmpty, !session.stream.rows().isEmpty {
            ScrollbackRows(
              rows: session.stream.rows(),
              isScrollback: ["scrollback", "terminal_scrollback"].contains(
                session.conversationSource))
          }
          LiveStatusLine(status: liveStatusWord, detail: session.stream.agentStatusDetail)
          // The sheet can be swiped away; the waiting prompt stays reachable here, as Android
          // keeps its card on the page.
          if let prompt = session.stream.pendingPrompt, !promptOpen {
            PendingPromptRow(title: prompt.title) { promptOpen = true }
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
      // The jump control sits in the bottom bar above the composer, so it never overlaps the
      // send button.
      .bottomComposer {
        VStack(alignment: .trailing, spacing: 8) {
          // Connection trouble stays above the composer, visible wherever the reader has scrolled.
          if let error = session.streamError {
            StreamBanner(
              message: session.isReconnecting
                ? "Reconnecting; earlier output may be missing" : error,
              reconnecting: session.isReconnecting, palette: palette
            ) { session.retryStream() }
          }
          if !follow.following {
            JumpToLatestButton(hasNewOutput: hasNewOutput) {
              follow.jumpToLatest()
              hasNewOutput = false
              withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
            }
          }
          Composer(session: session)
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 4)
      }
    }
    .onChange(of: session.stream.pendingPrompt?.promptID) { _, promptID in
      promptOpen = promptID != nil
    }
    .onAppear { promptOpen = session.stream.pendingPrompt != nil }
    .navigationTitle(session.selectedAgent?.label ?? "Conversation")
    .inlineTitle()
    // The conversation has its own composer and back button; the app's tabs stay on the list.
    .hideTabBarOnIOS()
    .toolbar {
      ToolbarItem(placement: .principal) {
        VStack(spacing: 0) {
          Text(session.selectedAgent?.label ?? "").font(.headline)
          Text(statusLabel(liveStatusWord)).font(.caption).foregroundStyle(.secondary)
        }
      }
      ToolbarItem(placement: .primaryAction) {
        Menu {
          Button("Connection details", systemImage: "info.circle") { showingDetails = true }
          if session.historyStore != nil, session.selectedAgent != nil {
            Button("Conversation history", systemImage: "clock.arrow.circlepath") {
              showingHistory = true
            }
          }
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
    .sheet(isPresented: $showingHistory) {
      if let store = session.historyStore, let agent = session.selectedAgent {
        AgentHistorySheet(
          store: store, agent: agent, machineName: session.selectedProfile?.name ?? "",
          currentConversationID: session.conversationID)
      }
    }
  }

  private var liveStatusWord: String {
    guard session.isLive else { return "unknown" }
    return liveStatus(
      pendingPrompt: session.stream.pendingPrompt != nil,
      streamStatus: session.stream.agentStatus,
      listedStatus: session.selectedAgent?.status ?? "idle")
  }
}

/// The badge word for a status, as in the header and agent rows (Android's status labels).
func statusLabel(_ status: String) -> String {
  switch status {
  case "working": "Working"
  case "blocked": "Blocked"
  case "done": "Done"
  case "idle": "Idle"
  default: "Unknown"
  }
}

/// The square's colour beside a status word.
func statusColor(_ status: String) -> Color {
  switch status {
  case "working": .blue
  case "blocked": .orange
  case "done": .green
  default: .secondary
  }
}

/// The last folder of a working directory, as Android's header shows it.
func folderName(_ cwd: String?) -> String? {
  guard let cwd, !cwd.isEmpty else { return nil }
  let folder = (cwd.hasSuffix("/") ? String(cwd.dropLast()) : cwd).split(separator: "/").last
  return folder.map(String.init)
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

/// The stream's state when it is not live: an amber strip while it retries, as Android's warning
/// banner shows it, or the failure with Retry once it has given up. Retry only re-reads; it never
/// replays a prompt, answer or control.
private struct StreamBanner: View {
  let message: String
  let reconnecting: Bool
  let palette: CatppuccinPalette
  let retry: () -> Void

  var body: some View {
    HStack(spacing: 10) {
      if reconnecting {
        ProgressView().controlSize(.small)
      } else {
        Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(palette.error)
      }
      Text(message)
        .font(.footnote)
        .foregroundStyle(palette.text)
        .frame(maxWidth: .infinity, alignment: .leading)
      if !reconnecting {
        Button("Retry", action: retry)
          .buttonStyle(.bordered)
          .controlSize(.small)
          .accessibilityIdentifier("retry-stream")
      }
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .background(
      (reconnecting ? palette.warn : palette.error).opacity(0.18), in: .rect(cornerRadius: 12)
    )
    .accessibilityElement(children: .contain)
    .accessibilityIdentifier(reconnecting ? "reconnecting" : "stream-error")
  }
}

/// A prompt waiting for the owner while its sheet is closed: what it asks, and a way back.
private struct PendingPromptRow: View {
  let title: String
  let open: () -> Void

  var body: some View {
    HStack(spacing: 10) {
      Image(systemName: "questionmark.bubble").foregroundStyle(.orange)
      Text(title).font(.subheadline).lineLimit(2)
      Spacer()
      Button("Answer", action: open).buttonStyle(.borderedProminent)
    }
    .padding(12)
    .background(.background.secondary, in: .rect(cornerRadius: 12))
    .accessibilityElement(children: .combine)
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

/// Live output lines shown when the transcript is not available, labelled as Android labels them.
private struct ScrollbackRows: View {
  let rows: [OutputRow]
  let isScrollback: Bool
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    VStack(alignment: .leading, spacing: 6) {
      Text(
        isScrollback
          ? "Recent output · Scrollback fallback" : "Recent output · History not verified"
      )
      .font(.caption2)
      .foregroundStyle(palette.muted)
      ForEach(rows, id: \.key) { row in
        Text(row.text)
          .font(.system(.footnote, design: .monospaced))
          .foregroundStyle(row.gap ? palette.muted : palette.text)
          .textSelection(.enabled)
      }
    }
    .accessibilityIdentifier("scrollback-rows")
  }
}
