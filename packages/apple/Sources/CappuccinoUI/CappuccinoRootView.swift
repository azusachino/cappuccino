import CappuccinoCore
import SwiftUI

/// The app's shell: Machines and Settings. One MachineConnections and one SessionModel live
/// for the app; the scene phase decides whether discovery runs.
@MainActor public struct CappuccinoRootView: View {
  @State private var machines: MachineConnections
  @State private var session: SessionModel
  @AppStorage(AppearanceMode.storageKey) private var appearance = AppearanceMode.system.rawValue
  @Environment(\.scenePhase) private var scenePhase
  @Environment(\.colorScheme) private var colorScheme

  public init(machines: MachineConnections = MachineConnections()) {
    _machines = State(initialValue: machines)
    _session = State(initialValue: SessionModel(machines: machines))
  }

  public var body: some View {
    let palette = CatppuccinPalette.current(colorScheme)
    TabView {
      MachinesScreen(machines: machines, session: session)
        .tabItem { Label("Machines", systemImage: "desktopcomputer") }
      SettingsScreen(machines: machines)
        .tabItem { Label("Settings", systemImage: "gearshape") }
    }
    .glassTabBar()
    .tint(palette.accent)
    .preferredColorScheme(AppearanceMode(rawValue: appearance)?.colorScheme)
    .onAppear { machines.setForeground(scenePhase == .active) }
    .onChange(of: scenePhase) { _, phase in machines.setForeground(phase == .active) }
  }
}
