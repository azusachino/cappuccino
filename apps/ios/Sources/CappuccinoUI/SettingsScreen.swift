import CappuccinoCore
import SwiftUI

/// Theme, saved connections and version. Connections are managed on the Machines tab.
@MainActor public struct SettingsScreen: View {
  let machines: MachineConnections
  @AppStorage(AppearanceMode.storageKey) private var appearance = AppearanceMode.system.rawValue

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
        }
        Section("About") {
          LabeledContent("Version", value: Self.version)
        }
      }
      .navigationTitle("Settings")
    }
  }

  private static var version: String {
    let base =
      Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1.0"
    return "\(base)(v1)"
  }
}
