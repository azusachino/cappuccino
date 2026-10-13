import CappuccinoCore
import CappuccinoUI
import SwiftUI

@main
struct CappuccinoApp: App {
  /// UI journeys pass `-cappuccino-fixture` to run against the canned bridge; production uses the real one.
  private static let usesFixture = ProcessInfo.processInfo.arguments.contains("-cappuccino-fixture")

  var body: some Scene {
    WindowGroup {
      if Self.usesFixture {
        CappuccinoRootView(
          machines: FixtureBridge.machines(), makeClient: FixtureBridge.clientFactory)
      } else {
        CappuccinoRootView()
      }
    }
  }
}
