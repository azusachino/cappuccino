import CappuccinoCore
import Foundation
import SwiftUI

/// State machine for the Machines tab: unpaired → pairing → paired (listing)
/// with explicit failure states, per behavior-spec "no silent retries".
@MainActor
public final class MachinesModel: ObservableObject {
  public enum Phase: Equatable {
    case unpaired
    case pairing
    case paired(machineID: String)
  }

  @Published public private(set) var phase: Phase = .unpaired
  @Published public private(set) var agents: [AgentRow] = []
  @Published public private(set) var errorText: String?
  @Published public var tokenInput = ""

  let daemon: DaemonServing
  let tokens: TokenStoring

  public init(daemon: DaemonServing, tokens: TokenStoring) {
    self.daemon = daemon
    self.tokens = tokens
    if let saved = tokens.loadToken(), !saved.isEmpty {
      phase = .paired(machineID: "")
    }
  }

  /// Restores a paired session by re-listing with the saved token. Failures
  /// surface visibly (they may mean the daemon is simply unreachable).
  public func refresh() async {
    guard let token = tokens.loadToken(), !token.isEmpty else {
      phase = .unpaired
      return
    }
    do {
      agents = try await daemon.listAgents(token: token)
      errorText = nil
      if case .unpaired = phase { phase = .paired(machineID: "") }
    } catch let error as DaemonClientError {
      errorText = error.message
    } catch {
      errorText = error.localizedDescription
    }
  }

  public func pair() async {
    let token = tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !token.isEmpty else {
      errorText = "Paste the one-time pairing token shown by the daemon."
      return
    }
    phase = .pairing
    errorText = nil
    do {
      let machine = try await daemon.pair(token: token)
      try tokens.saveToken(token)
      phase = .paired(machineID: machine.machineID)
      tokenInput = ""
      await refresh()
    } catch let error as DaemonClientError {
      // A rejected token is dropped so a retry never resubmits a stale secret.
      tokenInput = ""
      phase = tokens.loadToken()?.isEmpty == false ? phase : .unpaired
      errorText = error.message
    } catch {
      tokenInput = ""
      phase = .unpaired
      errorText = "Pairing failed: \(String(describing: error))"
    }
  }

  public func unpair() {
    tokens.deleteToken()
    phase = .unpaired
    agents = []
    errorText = nil
  }
}

/// Machines tab: pairing sheet, per-machine failure banner and the agent list.
public struct MachinesView: View {
  @StateObject private var model: MachinesModel

  public init(model: MachinesModel) {
    _model = StateObject(wrappedValue: model)
  }

  public var body: some View {
    NavigationStack {
      Group {
        switch model.phase {
        case .unpaired:
          unpaired
        case .pairing:
          ProgressView("Pairing…")
        case .paired(let machineID):
          paired(machineID: machineID)
        }
      }
      .navigationTitle("Machines")
    }
  }

  private var unpaired: some View {
    VStack(spacing: 16) {
      ContentUnavailableView(
        "No machines added",
        systemImage: "desktopcomputer",
        description: Text(
          "Pair this phone with the companion daemon on your Mac over tailnet or localhost. The one-time token stays in the Keychain."
        )
      )
      PairingSheet(model: model)
    }
  }

  private func paired(machineID: String) -> some View {
    List {
      if let error = model.errorText {
        Text(error)
          .foregroundStyle(.red)
          .accessibilityIdentifier("machine-error")
      }
      if !machineID.isEmpty {
        LabeledContent("Machine", value: machineID)
      }
      Section("Agents") {
        ForEach(model.agents) { agent in
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
        if model.agents.isEmpty {
          Text("No agents running on this machine.")
            .foregroundStyle(.secondary)
        }
      }
      Section {
        Button("Unpair machine", role: .destructive) { model.unpair() }
          .accessibilityIdentifier("unpair")
      } footer: {
        Text(
          "Selecting an agent never starts, stops or replaces it; delivery and approvals stay unavailable in this slice."
        )
      }
    }
    .refreshable { await model.refresh() }
  }
}

struct PairingSheet: View {
  let model: MachinesModel

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("Paste the one-time pairing token")
        .font(.subheadline.weight(.medium))
      SecureField("Pairing token", text: Binding(get: { model.tokenInput }, set: { model.tokenInput = $0 }))
        .textFieldStyle(.roundedBorder)
        .autocorrectionDisabled()
        .accessibilityIdentifier("pairing-token")
      Button("Pair machine") {
        Task { await model.pair() }
      }
      .buttonStyle(.borderedProminent)
      .accessibilityIdentifier("pair-button")
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
