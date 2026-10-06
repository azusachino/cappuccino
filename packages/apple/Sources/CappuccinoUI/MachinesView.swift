import CappuccinoCore
import Foundation
import SwiftUI

/// State machine for the Machines tab. No credentials anywhere: a machine is
/// just a base URL (stored in UserDefaults, editable), the bridge has no auth,
/// and the tailnet/loopback boundary is the security model. Failures surface
/// visibly per machine — never a silent retry.
@MainActor
public final class MachinesModel: ObservableObject {
  public enum Phase: Equatable {
    case noMachine
    case connecting(url: String)
    case connected(url: String)
  }

  static let baseURLKey = "machine.baseURL"

  @Published public private(set) var phase: Phase = .noMachine
  @Published public private(set) var agents: [AgentRow] = []
  @Published public private(set) var errorText: String?
  @Published public var machineURLInput = ""

  let daemon: DaemonServing
  let transcriptStreaming: TranscriptStreaming
  let durableReload: (@Sendable (URL, String) async throws -> Void)?
  let defaults: UserDefaults

  public init(
    daemon: DaemonServing, transcriptStreaming: TranscriptStreaming,
    durableReload: (@Sendable (URL, String) async throws -> Void)? = nil,
    defaults: UserDefaults = .standard
  ) {
    self.daemon = daemon
    self.transcriptStreaming = transcriptStreaming
    self.durableReload = durableReload
    self.defaults = defaults
    let fresh = ProcessInfo.processInfo.arguments.contains("-cappuccino-fresh")
    if !fresh, let saved = defaults.string(forKey: Self.baseURLKey), !saved.isEmpty {
      phase = .connected(url: saved)
    } else if let prefilled = ProcessInfo.processInfo.environment["CAPP_DEMO_MACHINE_URL"] {
      // UI-test/preview hook: pre-fill the sheet without keyboard typing.
      machineURLInput = prefilled
    }
  }

  var machineURL: URL? {
    if case .connected(let url) = phase, let url = URL(string: url) {
      return url
    }
    return nil
  }

  /// The URL of the connected machine, for opening transcripts.
  public var connectedURL: URL? { machineURL }

  /// Whether a machine is connected (transcripts open only then).
  public var isConnected: Bool {
    if case .connected = phase { return true }
    return false
  }

  /// Re-lists agents for the stored machine. Failures are visible and keep
  /// the machine selected (the daemon may simply be down).
  public func refresh() async {
    guard let url = machineURL else {
      phase = .noMachine
      return
    }
    do {
      agents = try await daemon.listAgents(baseURL: url)
      errorText = nil
    } catch let error as DaemonClientError {
      errorText = error.message
    } catch {
      errorText = error.localizedDescription
    }
  }

  public func addMachine() async {
    let text = machineURLInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let url = URL(string: text), let scheme = url.scheme?.lowercased(),
      scheme == "http" || scheme == "https", url.host != nil
    else {
      errorText = "Enter the machine's base URL, e.g. http://127.0.0.1:7392"
      return
    }
    phase = .connecting(url: text)
    errorText = nil
    do {
      let machine = try await daemon.pair(baseURL: url)
      defaults.set(text, forKey: Self.baseURLKey)
      phase = .connected(url: text)
      machineURLInput = ""
      _ = machine
      await refresh()
    } catch let error as DaemonClientError {
      phase = defaults.string(forKey: Self.baseURLKey).map(Phase.connected) ?? .noMachine
      errorText = error.message
    } catch {
      phase = defaults.string(forKey: Self.baseURLKey).map(Phase.connected) ?? .noMachine
      errorText = error.localizedDescription
    }
  }

  public func removeMachine() {
    defaults.removeObject(forKey: Self.baseURLKey)
    phase = .noMachine
    agents = []
    errorText = nil
  }
}

/// Machines tab: add-machine sheet, per-machine failure banner and the agent
/// list. No token field anywhere — the bridge has no auth by design.
public struct MachinesView: View {
  @StateObject private var model: MachinesModel

  public init(model: MachinesModel) {
    _model = StateObject(wrappedValue: model)
  }

  public var body: some View {
    NavigationStack {
      Group {
        switch model.phase {
        case .noMachine:
          noMachine
        case .connecting:
          ProgressView("Connecting…")
        case .connected(let url):
          connected(url: url)
        }
      }
      .navigationTitle("Machines")
    }
  }

  private var noMachine: some View {
    VStack(spacing: 16) {
      ContentUnavailableView(
        "No machines added",
        systemImage: "desktopcomputer",
        description: Text(
          "Add the machine running the Cappuccino bridge (loopback or tailnet address). The bridge has no login: your tailnet is the boundary."
        )
      )
      MachineSheet(model: model)
    }
  }

  private func connected(url: String) -> some View {
    List {
      if let error = model.errorText {
        Text(error)
          .foregroundStyle(.red)
          .accessibilityIdentifier("machine-error")
      }
      LabeledContent("Machine", value: url)
        .accessibilityIdentifier("machine-url")
      Section("Agents") {
        ForEach(model.agents) { agent in
          NavigationLink {
            if let machineURL = model.machineURL {
              TranscriptView(
                model: TranscriptModel(
                  session: agent.sessionID,
                  machineURL: machineURL,
                  streaming: model.transcriptStreaming,
                  branch: agent.branch,
                  durableReload: model.durableReload)
              )
            } else {
              ContentUnavailableView(
                "No machine connected",
                systemImage: "desktopcomputer")
            }
          } label: {
            agentRow(agent)
          }
          .accessibilityIdentifier("open-agent-\(agent.sessionID)")
        }
        if model.agents.isEmpty {
          Text("No agents running on this machine.")
            .foregroundStyle(.secondary)
        }
      }
      Section {
        Button("Remove machine", role: .destructive) { model.removeMachine() }
          .accessibilityIdentifier("unpair")
      } footer: {
        Text(
          "Selecting an agent never starts, stops or replaces it; delivery and approvals stay unavailable in this slice."
        )
      }
    }
    .refreshable { await model.refresh() }
  }

  private func agentRow(_ agent: AgentRow) -> some View {
    VStack(alignment: .leading, spacing: 4) {
      HStack {
        Text(agent.label)
          .font(.headline)
          .accessibilityIdentifier("agent-label-\(agent.sessionID)")
        Spacer()
        if agent.working {
          Image(systemName: "circle.dotted")
            .foregroundStyle(.orange)
            .accessibilityLabel("Working")
        }
      }
      Text(agent.sessionID)
        .font(.caption)
        .foregroundStyle(.secondary)
      if let branch = agent.branch {
        Label(branch, systemImage: "arrow.triangle.branch")
          .font(.caption)
          .accessibilityIdentifier("agent-branch-\(agent.sessionID)")
      } else {
        Label("No branch", systemImage: "questionmark.folder")
          .font(.caption)
          .foregroundStyle(.secondary)
          .accessibilityIdentifier("agent-branch-\(agent.sessionID)")
      }
    }
    .padding(.vertical, 2)
  }
}

struct MachineSheet: View {
  let model: MachinesModel

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("Machine base URL")
        .font(.subheadline.weight(.medium))
      TextField(
        "http://127.0.0.1:7392",
        text: Binding(get: { model.machineURLInput }, set: { model.machineURLInput = $0 })
      )
      .textFieldStyle(.roundedBorder)
      .autocorrectionDisabled()
      .accessibilityIdentifier("machine-url-input")
      Button("Add machine") {
        Task { await model.addMachine() }
      }
      .buttonStyle(.borderedProminent)
      .accessibilityIdentifier("add-machine")
      if let error = model.errorText {
        Text(error)
          .font(.footnote)
          .foregroundStyle(.red)
          .accessibilityIdentifier("pairing-error")
      }
    }
    .padding(.horizontal)
  }
}
