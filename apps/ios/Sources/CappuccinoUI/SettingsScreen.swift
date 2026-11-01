import CappuccinoCore
import SwiftUI

/// Ports ui/SettingsScreen.kt: theme, saved connections with their logs, help links, and About.
/// Connections are added and managed on the Machines tab.
@MainActor public struct SettingsScreen: View {
  let machines: MachineConnections
  @AppStorage(AppearanceMode.storageKey) private var appearance = AppearanceMode.system.rawValue
  @State private var showingLogs = false
  @State private var showingGuide = false

  public init(machines: MachineConnections) {
    self.machines = machines
  }

  public var body: some View {
    NavigationStack {
      Form {
        Section {
          Picker("Appearance", selection: $appearance) {
            ForEach(AppearanceMode.allCases) { mode in
              Text(mode.title).tag(mode.rawValue)
            }
          }
          .pickerStyle(.inline)
        } header: {
          Text("Theme")
        } footer: {
          Text("Catppuccin Latte in light mode and Catppuccin Mocha in dark mode.")
        }
        Section("Connections") {
          LabeledContent("Saved machines", value: "\(machines.profiles.count)")
          Button("Connection logs") { showingLogs = true }
            .accessibilityIdentifier("connection-logs")
        }
        Section("Help") {
          Link("Documentation", destination: Self.readme)
          Link("Bridge setup", destination: Self.irohSetup)
          Link("Herdr", destination: Self.herdr)
          Link("Iroh", destination: Self.iroh)
        }
        Section("About") {
          Button("About Cappuccino") { showingGuide = true }
          ShareLink(item: Self.version) {
            LabeledContent("Version", value: Self.version)
          }
        }
      }
      .navigationTitle("Settings")
      .sheet(isPresented: $showingLogs) {
        ConnectionLogsSheet(log: machines.log)
      }
      .sheet(isPresented: $showingGuide) {
        AboutGuideSheet()
      }
    }
  }

  private static let readme = URL(string: "https://github.com/azusachino/cappuccino#readme")!
  private static let irohSetup = URL(
    string: "https://github.com/azusachino/cappuccino/blob/main/docs/iroh-setup.md")!
  private static let herdr = URL(string: "https://herdr.dev")!
  private static let iroh = URL(string: "https://iroh.computer")!

  private static var version: String {
    let base =
      Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    return "\(base)(v1)"
  }
}

/// Ports the About guide from SettingsScreen.kt: four basic steps, in the same order and words.
private struct AboutGuideSheet: View {
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      List {
        Section("Basic instructions") {
          GuideStep(
            number: 1, title: "Pair your machine",
            detail:
              "Run 'cappuccino-bridge pair' on your computer to show a pairing QR code, then tap + on the Machines tab to scan it. Or connect via your Tailnet address."
          )
          GuideStep(
            number: 2, title: "Monitor running agents",
            detail:
              "See active agents across all paired machines. Tap any session to read live output, reasoning, and tool executions in real time."
          )
          GuideStep(
            number: 3, title: "Steer & approve",
            detail:
              "Confirm pending tool approvals, reply to questions, or prompt your agent while away from your desk."
          )
          GuideStep(
            number: 4, title: "Always running",
            detail:
              "Agents run inside Herdr on your host machine. Disconnecting or quitting Cappuccino never terminates your agents or loses session state."
          )
        }
      }
      .navigationTitle("About Cappuccino")
      .inlineTitle()
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("Done") { dismiss() }
        }
      }
    }
  }
}

private struct GuideStep: View {
  let number: Int
  let title: String
  let detail: String

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      Text("\(number). \(title)").font(.headline)
      Text(detail).font(.callout).foregroundStyle(.secondary)
    }
    .padding(.vertical, 2)
  }
}
