import CappuccinoCore
import SwiftUI

/// Ports ui/HistoryScreen.kt: saved conversations kept on this device, newest first. Opening one
/// shows its read-only copy; deleting removes only that saved copy, never the agent's session.
@MainActor public struct HistoryScreen: View {
  let machines: MachineConnections
  let store: SavedConversationStore
  @State private var saved: [SavedConversation] = []
  @State private var problem: String?

  public init(machines: MachineConnections, store: SavedConversationStore) {
    self.machines = machines
    self.store = store
  }

  public var body: some View {
    NavigationStack {
      List {
        if !saved.isEmpty {
          Section {
            ForEach(saved) { conversation in
              NavigationLink {
                SavedTranscriptView(
                  conversation: conversation, machineName: machineName(conversation))
              } label: {
                SavedRow(conversation: conversation, machineName: machineName(conversation))
              }
            }
            .onDelete(perform: delete)
          } header: {
            Text("Kept on this device for 30 days · \(saved.count) saved")
          }
        }
        if let problem {
          Text(problem).foregroundStyle(.red).accessibilityIdentifier("history-error")
        }
      }
      .overlay {
        if saved.isEmpty {
          ContentUnavailableView(
            "No saved sessions", systemImage: "clock.arrow.circlepath",
            description: Text(
              "Conversations are kept while agents run and stay here for 30 days."))
        }
      }
      .navigationTitle("History")
      .onAppear(perform: reload)
    }
  }

  private func reload() {
    do {
      saved = try store.list()
      problem = nil
    } catch {
      problem = "Saved history could not be read."
    }
  }

  private func delete(at offsets: IndexSet) {
    for index in offsets {
      let item = saved[index]
      guard let machineID = item.machineID else { continue }
      do {
        try store.delete(machineID: machineID, conversationID: item.conversationID)
      } catch {
        problem = "The saved copy could not be deleted."
        return
      }
    }
    reload()
  }

  private func machineName(_ conversation: SavedConversation) -> String {
    machines.profiles.first { $0.machineID == conversation.machineID }?.name ?? "Unknown machine"
  }
}

private struct SavedRow: View {
  let conversation: SavedConversation
  let machineName: String
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    VStack(alignment: .leading, spacing: 4) {
      Text(conversation.agent.label).font(.body.weight(.semibold))
      Text(
        "\(machineName) · saved \(conversation.savedAt.formatted(.relative(presentation: .named))) · \(messageLabel)"
      )
      .font(.footnote)
      .foregroundStyle(palette.muted)
    }
  }

  private var messageLabel: String {
    let count = conversation.turns.count
    return count == 1 ? "1 message" : "\(count) messages"
  }
}

/// The saved copy of one conversation. It is read-only history: live state comes from the agent.
private struct SavedTranscriptView: View {
  let conversation: SavedConversation
  let machineName: String
  @Environment(\.colorScheme) private var colorScheme

  var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    ScrollView {
      LazyVStack(alignment: .leading, spacing: 14) {
        Text("Saved copy from \(machineName) · read only")
          .font(.footnote)
          .foregroundStyle(palette.muted)
        if conversation.omittedMessages > 0 {
          Text("Older messages aren't saved.")
            .font(.footnote)
            .foregroundStyle(palette.muted)
        }
        ForEach(conversation.turns, id: \.id) { turn in
          TurnRow(turn: turn, newest: false, assistantLabel: conversation.agent.label)
        }
      }
      .padding(16)
    }
    .navigationTitle(conversation.agent.label)
    .inlineTitle()
  }
}
