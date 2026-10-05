import CappuccinoCore
import SwiftUI

struct ContentView: View {
  @State private var delivery = MessageDelivery.followUp
  @State private var draft = ""

  var body: some View {
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

      NavigationStack {
        ContentUnavailableView(
          "No machines added",
          systemImage: "desktopcomputer",
          description: Text("Private machine pairing is not implemented yet.")
        )
        .navigationTitle("Machines")
      }
      .tabItem { Label("Machines", systemImage: "desktopcomputer") }
    }
  }
}
