import CappuccinoCore
import SwiftUI

public struct ContentView: View {
  @State private var delivery = MessageDelivery.followUp
  @State private var draft = ""

  /// UI-test hooks: scripted demo daemon or a guaranteed-refused port so the
  /// journeys are hermetic; production runs talk to the real slice-A daemon.
  private static func daemonForProcess() -> DaemonServing {
    let arguments = ProcessInfo.processInfo.arguments
    if arguments.contains("-cappuccino-demo") {
      return DemoDaemonClient()
    }
    if arguments.contains("-cappuccino-unreachable") {
      return DaemonClient(host: "127.0.0.1", port: 1, timeout: 2)
    }
    return DaemonClient()
  }

  private static func tokensForProcess() -> TokenStoring {
    // The scripted UI-test journeys run in an unsigned simulator app where
    // SecItemAdd is unavailable; production pairs store in the Keychain.
    if ProcessInfo.processInfo.arguments.contains("-cappuccino-demo") {
      return InMemoryTokenStore()
    }
    return KeychainTokenStore()
  }

  public init() {}

  public var body: some View {
    TabView {
      NavigationStack {
        ContentUnavailableView(
          "No agents attached",
          systemImage: "bubble.left.and.bubble.right",
          description: Text("Remote attachment is not implemented in this skeleton.")
        )
        .navigationTitle("Chats")
        .safeAreaInset(edge: .bottom) {
          VStack(alignment: .leading) {
            Picker("Message timing", selection: $delivery) {
              Text("Nudge").tag(MessageDelivery.nudge)
              Text("Follow-up").tag(MessageDelivery.followUp)
            }
            .pickerStyle(.segmented)

            HStack {
              TextField("Attach an agent to reply", text: $draft)
                .accessibilityLabel("Message")
                .disabled(true)
              Button("Send", systemImage: "paperplane") {}
                .disabled(true)
            }
          }
          .padding()
        }
      }
      .tabItem { Label("Chats", systemImage: "bubble.left.and.bubble.right") }

      NavigationStack {
        ContentUnavailableView(
          "No pending requests",
          systemImage: "tray",
          description: Text("Approvals and completion events require the remote bridge.")
        )
        .navigationTitle("Attention")
      }
      .tabItem { Label("Attention", systemImage: "tray") }

      MachinesView(
        model: MachinesModel(daemon: Self.daemonForProcess(), tokens: Self.tokensForProcess())
      )
      .tabItem { Label("Machines", systemImage: "desktopcomputer") }
    }
  }
}
