import CappuccinoCore
import SwiftUI

/// Machines: each saved bridge with its connection state and agents. Selecting an agent
/// opens its conversation; selecting never starts, stops or replaces an agent.
@MainActor public struct MachinesScreen: View {
  let machines: MachineConnections
  let session: SessionModel
  @State private var adding = false
  @State private var renaming: BridgeProfile?
  @State private var inspecting: BridgeProfile?
  @State private var removing: BridgeProfile?
  @State private var openAgent = false

  public init(machines: MachineConnections, session: SessionModel) {
    self.machines = machines
    self.session = session
  }

  public var body: some View {
    NavigationStack {
      List {
        if machines.profiles.isEmpty {
          ContentUnavailableView(
            "No machines configured",
            systemImage: "desktopcomputer",
            description: Text(
              "Add the machine running the Cappuccino bridge: loopback or your tailnet address."))
        }
        ForEach(machines.profiles) { profile in
          MachineSection(
            profile: profile, state: machines.connections[profile.id] ?? MachineConnection(),
            session: session, machines: machines,
            onRename: { renaming = profile }, onInspect: { inspecting = profile },
            onRemove: { removing = profile },
            onOpen: { agent in
              session.select(profileID: profile.id, agent: agent)
              openAgent = true
            })
        }
      }
      .navigationTitle("Machines")
      .navigationDestination(isPresented: $openAgent) {
        ConversationScreen(machines: machines, session: session)
      }
      .onChange(of: openAgent) { _, open in
        if !open { session.clearSelection() }
      }
      .toolbar {
        ToolbarItem(placement: .primaryAction) {
          Button {
            adding = true
          } label: {
            Label("Add machine", systemImage: "plus")
          }
        }
        ToolbarItem(placement: .secondaryAction) {
          Button {
            for profile in machines.profiles { machines.refresh(profile.id) }
          } label: {
            Label("Refresh", systemImage: "arrow.clockwise")
          }
          .disabled(machines.profiles.isEmpty)
        }
      }
      .sheet(isPresented: $adding) {
        AddMachineSheet(machines: machines)
      }
      .sheet(item: $renaming) { profile in
        RenameMachineSheet(machines: machines, profile: profile)
      }
      .sheet(item: $inspecting) { profile in
        ConnectionDetailsSheet(profile: profile, state: machines.connections[profile.id])
      }
      .confirmationDialog(
        "Remove this machine?",
        isPresented: Binding(
          get: { removing != nil }, set: { if !$0 { removing = nil } }),
        titleVisibility: .visible
      ) {
        Button("Remove", role: .destructive) {
          if let profile = removing { try? machines.remove(profile.id) }
          removing = nil
        }
      } message: {
        Text("Saved agents and history for this machine are not deleted on the bridge.")
      }
    }
  }
}

private struct MachineSection: View {
  let profile: BridgeProfile
  let state: MachineConnection
  let session: SessionModel
  let machines: MachineConnections
  let onRename: () -> Void
  let onInspect: () -> Void
  let onRemove: () -> Void
  let onOpen: (AgentRow) -> Void

  var body: some View {
    Section {
      ConnectionStatusRow(phase: state.phase, refreshing: state.refreshing)
      ForEach(state.agents) { agent in
        Button {
          onOpen(agent)
        } label: {
          AgentRowLabel(agent: agent)
        }
        .accessibilityHint("Opens the conversation")
      }
      if state.phase == .connected && state.agents.isEmpty {
        Text("No agents running on this machine.").foregroundStyle(.secondary)
      }
    } header: {
      HStack {
        Text(profile.name)
        Spacer()
        Menu {
          Button("Refresh", systemImage: "arrow.clockwise") { machines.refresh(profile.id) }
          Button("Connection details", systemImage: "info.circle", action: onInspect)
          Button("Rename", systemImage: "pencil", action: onRename)
          Button("Disconnect", systemImage: "bolt.slash") { machines.disconnect(profile.id) }
          Button("Remove", systemImage: "trash", role: .destructive, action: onRemove)
        } label: {
          Image(systemName: "ellipsis.circle")
            .accessibilityLabel("Machine actions for \(profile.name)")
        }
      }
    }
  }
}

private struct ConnectionStatusRow: View {
  let phase: MachineConnectionPhase
  let refreshing: Bool

  var body: some View {
    switch phase {
    case .disconnected:
      Label("Disconnected", systemImage: "circle.slash").foregroundStyle(.secondary)
    case .connecting:
      Label(refreshing ? "Refreshing…" : "Connecting…", systemImage: "arrow.triangle.2.circlepath")
        .foregroundStyle(.secondary)
    case .connected:
      Label("Connected", systemImage: "checkmark.circle").foregroundStyle(.green)
    case .failed(let message):
      Label("Failed: \(message)", systemImage: "exclamationmark.triangle")
        .foregroundStyle(.red)
    }
  }
}

/// An agent in a machine's list: its kind, label, status word and branch.
struct AgentRowLabel: View {
  let agent: AgentRow

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: "terminal")
        .frame(width: 24)
        .foregroundStyle(.secondary)
      VStack(alignment: .leading, spacing: 2) {
        Text(agent.label).font(.headline)
        HStack(spacing: 6) {
          Rectangle()
            .fill(agent.working ? Color.green : Color.secondary)
            .frame(width: 8, height: 8)
          Text(agent.working ? "Working" : "Idle")
          if let branch = agent.branch {
            Text("· \(branch)").lineLimit(1)
          }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
      }
      Spacer()
      Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.tertiary)
    }
    .contentShape(Rectangle())
    .accessibilityElement(children: .combine)
  }
}

private struct AddMachineSheet: View {
  let machines: MachineConnections
  @Environment(\.dismiss) private var dismiss
  @State private var address = ""
  @State private var label = ""
  @State private var error: String?
  @State private var connecting = false

  var body: some View {
    NavigationStack {
      Form {
        Section {
          TextField("Bridge address", text: $address, prompt: Text("http://127.0.0.1:7392"))
            .urlEntry()
            .autocorrectionDisabled()
            .accessibilityLabel("Bridge address")
          TextField("Label (optional)", text: $label)
        } footer: {
          Text("The bridge has no login: your tailnet is the boundary.")
        }
        if let error {
          Section { Text(error).foregroundStyle(.red).accessibilityIdentifier("pairing-error") }
        }
      }
      .navigationTitle("Add machine")
      .inlineTitle()
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("Connect") {
            connecting = true
            Task {
              defer { connecting = false }
              do {
                try await machines.addMachine(address: address, label: label)
                dismiss()
              } catch {
                self.error = OwnerMessage.text(for: error)
              }
            }
          }
          .disabled(address.trimmingCharacters(in: .whitespaces).isEmpty || connecting)
        }
      }
    }
  }
}

private struct RenameMachineSheet: View {
  let machines: MachineConnections
  let profile: BridgeProfile
  @Environment(\.dismiss) private var dismiss
  @State private var name: String

  init(machines: MachineConnections, profile: BridgeProfile) {
    self.machines = machines
    self.profile = profile
    _name = State(initialValue: profile.name)
  }

  var body: some View {
    NavigationStack {
      Form {
        TextField("Name", text: $name)
      }
      .navigationTitle("Rename")
      .inlineTitle()
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          Button("Save") {
            try? machines.rename(profile.id, to: name)
            dismiss()
          }
          .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
      }
    }
  }
}

struct ConnectionDetailsSheet: View {
  let profile: BridgeProfile
  let state: MachineConnection?
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      List {
        Section("Bridge") {
          LabeledContent("Address", value: profile.endpoint.absoluteString)
          LabeledContent("Machine ID", value: profile.machineID.uuidString.lowercased())
          LabeledContent("Version", value: state?.details?.version ?? "Not advertised")
          LabeledContent(
            "Protocol", value: state?.details.map { String($0.protocolVersion) } ?? "Unknown")
        }
        Section("Capabilities") {
          if let capabilities = state?.details?.capabilities {
            ForEach(capabilities.keys.sorted(), id: \.self) { key in
              LabeledContent(key, value: capabilities[key] == true ? "On" : "Off")
            }
          } else {
            Text("Not advertised by this bridge.").foregroundStyle(.secondary)
          }
        }
      }
      .navigationTitle(profile.name)
      .inlineTitle()
      .toolbar {
        ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
      }
    }
  }
}
