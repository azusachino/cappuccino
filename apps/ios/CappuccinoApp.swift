import CappuccinoCore
import CappuccinoUI
import SwiftUI

@main
struct CappuccinoApp: App {
  /// UI journeys pass `-cappuccino-fixture` to run against the canned bridge.
  private static let arguments = ProcessInfo.processInfo.arguments

  init() {
    // `-cappuccino-fresh` forgets saved machines so a journey starts with none.
    if Self.arguments.contains("-cappuccino-fresh") {
      try? BridgeProfileStore().save([])
    }
  }

  var body: some Scene {
    WindowGroup {
      if Self.arguments.contains("-cappuccino-fixture") {
        CappuccinoRootView(
          machines: FixtureBridge.machines(), makeClient: FixtureBridge.clientFactory)
      } else {
        CappuccinoRootView()
      }
    }
  }
}
