import CappuccinoCore
import SwiftUI

/// The composer: one floating panel at the bottom. Commands (Continue, agent commands, recent
/// prompts, Clear draft, New session), the text field, Stop while working, Send, and the model,
/// context and cache line. Stop and New session need the bridge's agent control, as on Android.
@MainActor struct Composer: View {
  let session: SessionModel
  @State private var draft = ""
  @State private var confirmingNewSession = false
  @State private var confirmingCommand = false
  /// Kept after the dialog closes so its title does not change while it animates away.
  @State private var commandToConfirm = ""
  /// Sent prompts, newest first, for the Recent prompts menu (Android keeps them per composer).
  @State private var recentPrompts: [String] = []
  /// A prompt is on its way to the bridge and not yet accepted.
  @State private var sending = false

  var body: some View {
    let status = currentStatus
    let agentName = session.selectedAgent?.label ?? "agent"
    GlassGroup {
      VStack(alignment: .leading, spacing: 8) {
        TextField("Message \(agentName)…", text: $draft, axis: .vertical)
          .disabled(!session.isLive)
          .lineLimit(1...6)
          .padding(12)
          .accessibilityIdentifier("promptInput")
          .glassPanel(cornerRadius: 22)

        if sending {
          HStack(spacing: 6) {
            ProgressView().controlSize(.mini)
            Text("Sending…")
          }
          .font(.caption2)
          .foregroundStyle(.secondary)
          .padding(.horizontal, 4)
          .accessibilityElement(children: .combine)
          .accessibilityIdentifier("sending")
        }

        HStack(spacing: 8) {
          if session.isLive {
            commandsMenu(idle: isIdle(status), working: status == "working")
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
            !canSend || sending || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
          )
          .accessibilityLabel("Send")
        }

        // One quiet line: model, context and cache, shown only when the bridge reports them.
        let info = [
          modelChip(session.stats),
          session.stats.flatMap(contextSummary).map { "context \($0)" },
          session.stats?.cachePercent.map { "cache \($0)%" },
        ].compactMap { $0 }
        if !info.isEmpty {
          Text(info.joined(separator: " · "))
            .font(.caption2)
            .foregroundStyle(.secondary)
            .lineLimit(1)
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
      Button("Run \(commandToConfirm)") {
        // The draft is sent when it is this command with its arguments; otherwise the bare command.
        send(slashCommand(draft) == commandToConfirm ? draft : commandToConfirm, confirmed: true)
      }
      Button("Cancel", role: .cancel) {}
    } message: {
      Text(slashCommandWarning(commandToConfirm))
    }
  }

  /// Ordinary text sends at once; an agent command waits for the owner to confirm it.
  private func submitDraft() {
    // A command Android refuses is not offered for confirmation; the refusal reason is shown.
    if let command = slashCommand(draft), disallowedSlashCommandReason(command) == nil {
      commandToConfirm = command
      confirmingCommand = true
    } else {
      send(draft, confirmed: false)
    }
  }

  /// Keeps the draft until the bridge accepts it; a refusal leaves it to retry.
  private func send(_ prompt: String, confirmed: Bool) {
    let text = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty, !sending else { return }
    sending = true
    Task {
      let accepted = await session.sendPrompt(text, confirmed: confirmed)
      sending = false
      guard accepted else { return }
      recentPrompts = rememberingPrompt(text, in: recentPrompts)
      if draft.trimmingCharacters(in: .whitespacesAndNewlines) == text { draft = "" }
    }
  }

  private var canSend: Bool {
    session.isLive && session.selectedAgent != nil
      && session.capabilities?["prompt_input"] != false
  }

  /// Continue and the agent commands are prompts; New session needs agent control.
  private func commandsMenu(idle: Bool, working: Bool) -> some View {
    Menu {
      if idle {
        Button("Continue", systemImage: "play") { send("continue", confirmed: false) }
          .disabled(!canSend || sending)
      }
      Section("Agent commands") {
        ForEach(commonSlashCommands, id: \.command) { item in
          let blocked = working && item.command == "/new"
          Button {
            if item.command == "/compact" {
              commandToConfirm = item.command
              confirmingCommand = true
            } else {
              draft = "\(item.command) "
            }
          } label: {
            Text(item.command).monospaced()
            Text(blocked ? "Stop the agent first" : item.description)
          }
          .disabled(blocked || !canSend || sending)
        }
      }
      if !recentPrompts.isEmpty {
        Menu("Recent prompts", systemImage: "clock.arrow.circlepath") {
          ForEach(recentPrompts.prefix(8), id: \.self) { prompt in
            Button(prompt) { draft = prompt }
          }
        }
      }
      Section {
        Button("Clear draft", systemImage: "xmark.circle", role: .destructive) { draft = "" }
          .disabled(draft.isEmpty)
        if session.canControl {
          Button("New session", systemImage: "square.and.pencil") { confirmingNewSession = true }
            .disabled(!idle)
        }
      }
    } label: {
      Image(systemName: "square.grid.2x2").frame(width: 32, height: 32)
    }
    .glassButton()
    .accessibilityLabel("Commands")
  }

  private var currentStatus: String {
    guard session.isLive else { return "unknown" }
    return liveStatus(
      pendingPrompt: session.stream.pendingPrompt != nil,
      streamStatus: session.stream.agentStatus,
      listedStatus: session.selectedAgent?.status ?? "idle")
  }
}
