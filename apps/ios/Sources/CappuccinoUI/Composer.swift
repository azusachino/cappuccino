import CappuccinoCore
import SwiftUI

/// The composer: one floating panel at the bottom. Commands (only when the bridge advertises
/// agent control), the text field, Stop while working, Send, and the model, context and cache line.
/// Stop and New session need the bridge's agent control, as on Android.
@MainActor struct Composer: View {
  let session: SessionModel
  @State private var draft = ""
  @State private var confirmingNewSession = false
  @State private var confirmingCommand = false
  /// Kept after the dialog closes so its title does not change while it animates away.
  @State private var commandToConfirm = ""

  var body: some View {
    let status = currentStatus
    let agentName = session.selectedAgent?.label ?? "agent"
    GlassGroup {
      VStack(alignment: .leading, spacing: 8) {
        TextField("Message \(agentName)…", text: $draft, axis: .vertical)
          .lineLimit(1...6)
          .padding(12)
          .accessibilityIdentifier("promptInput")
          .glassPanel(cornerRadius: 22)

        HStack(spacing: 8) {
          if session.canControl {
            Menu {
              Button("New session") { confirmingNewSession = true }
            } label: {
              Image(systemName: "square.grid.2x2").frame(width: 32, height: 32)
            }
            .glassButton()
            .disabled(!isIdle(status))
            .accessibilityLabel("Commands")
          }
          if let chip = modelChip(session.stats) {
            Text(chip)
              .font(.caption2)
              .foregroundStyle(.secondary)
              .lineLimit(1)
          }
          Spacer(minLength: 0)
          if session.canControl, status == "working" {
            Button {
              Task { await session.control("interrupt") }
            } label: {
              Image(systemName: "stop.fill").frame(width: 32, height: 32)
            }
            .glassButton(tint: .red)
            .accessibilityLabel("Stop")
          }
          Button {
            submitDraft()
          } label: {
            Image(systemName: "arrow.up").frame(width: 32, height: 32)
          }
          .glassProminentButton()
          .disabled(
            session.selectedAgent == nil
              || session.capabilities?["prompt_input"] == false
              || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
          )
          .accessibilityLabel("Send")
        }

        if let context = session.stats.flatMap(contextSummary) {
          HStack(spacing: 8) {
            Text("context \(context)")
            if let cache = session.stats?.cachePercent {
              Text("cache \(cache)%")
            }
          }
          .font(.caption2)
          .foregroundStyle(.secondary)
          .padding(.horizontal, 4)
        }
      }
      .padding(12)
    }
    .confirmationDialog(
      "Start a new session?", isPresented: $confirmingNewSession, titleVisibility: .visible
    ) {
      Button("New session") {
        Task { await session.control("new_session") }
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text("\(agentName) starts a fresh conversation in the same terminal.")
    }
    .confirmationDialog(
      "Run \(commandToConfirm)?", isPresented: $confirmingCommand, titleVisibility: .visible
    ) {
      Button("Run \(commandToConfirm)") { send(confirmed: true) }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text(slashCommandWarning(commandToConfirm))
    }
  }

  /// Ordinary text sends at once; an agent command waits for the owner to confirm it.
  private func submitDraft() {
    if let command = slashCommand(draft) {
      commandToConfirm = command
      confirmingCommand = true
    } else {
      send(confirmed: false)
    }
  }

  /// Keeps the text until the bridge accepts it; a refusal leaves it to retry.
  private func send(confirmed: Bool) {
    let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
    Task {
      if await session.sendPrompt(text, confirmed: confirmed),
        draft.trimmingCharacters(in: .whitespacesAndNewlines) == text
      {
        draft = ""
      }
    }
  }

  private var currentStatus: String {
    liveStatus(
      pendingPrompt: session.stream.pendingPrompt != nil,
      streamStatus: session.stream.agentStatus,
      listedStatus: session.selectedAgent?.status ?? "idle")
  }
}

extension View {
  /// Bottom placement for the composer: a floating bar on iOS 26, an inset before.
  @ViewBuilder func bottomComposer<Panel: View>(@ViewBuilder _ panel: () -> Panel) -> some View {
    #if os(iOS)
      if #available(iOS 26, *) {
        self.safeAreaBar(edge: .bottom) { panel() }
      } else {
        self.safeAreaInset(edge: .bottom) { panel() }
      }
    #else
      self.safeAreaInset(edge: .bottom) { panel() }
    #endif
  }

  /// A glass panel on iOS 26 and later, a material panel before.
  @ViewBuilder fileprivate func glassPanel(cornerRadius: CGFloat) -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
    } else {
      self.background(.regularMaterial, in: RoundedRectangle(cornerRadius: cornerRadius))
    }
  }

  @ViewBuilder fileprivate func glassButton(tint: Color? = nil) -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.buttonStyle(.glass).tint(tint)
    } else {
      self.buttonStyle(.bordered).tint(tint)
    }
  }

  @ViewBuilder fileprivate func glassProminentButton() -> some View {
    if #available(iOS 26, macOS 26, *) {
      self.buttonStyle(.glassProminent)
    } else {
      self.buttonStyle(.borderedProminent)
    }
  }
}

/// Groups the composer's glass so its panels blend on iOS 26; a plain container before.
private struct GlassGroup<Content: View>: View {
  @ViewBuilder let content: () -> Content

  var body: some View {
    if #available(iOS 26, macOS 26, *) {
      GlassEffectContainer { content() }
    } else {
      content()
    }
  }
}
