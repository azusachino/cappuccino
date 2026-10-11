import Foundation
import Testing

@testable import CappuccinoCore

struct BridgeProfileTests {
  @Test func independentProfilesRoundTripWithoutTouchingOtherPreferences() throws {
    let suite = "cappuccino-profiles-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    defaults.set("keep draft", forKey: "draft")
    defaults.set(false, forKey: "follow")
    let profiles = try [
      BridgeProfile(machineID: UUID(), endpoint: URL(string: "https://mac.example")!, name: "Mac"),
      BridgeProfile(
        machineID: UUID(), endpoint: URL(string: "https://mini.example")!, name: "Mini"),
    ]
    let store = BridgeProfileStore(defaults: defaults)
    try store.save(profiles)
    #expect(try store.read() == profiles)
    try store.save([profiles[1]])
    #expect(try store.read() == [profiles[1]])
    #expect(defaults.string(forKey: "draft") == "keep draft")
    #expect(defaults.bool(forKey: "follow") == false)
    #expect(
      AgentIdentity(machineID: profiles[0].machineID, sessionID: "same-locator")
        != AgentIdentity(machineID: profiles[1].machineID, sessionID: "same-locator"))
  }

  @Test func rejectsUnsafeEndpointsAndInvalidSavedProfiles() throws {
    for endpoint in [
      "http://remote.example", "https://user:password@mini.example",
      "https://mini.example?token=value", "https://mini.example#fragment", "file:///tmp/bridge",
    ] {
      #expect(throws: DaemonClientError.self) {
        try BridgeProfile(machineID: UUID(), endpoint: URL(string: endpoint)!, name: "Mini")
      }
    }
    let valid = try BridgeProfile(
      machineID: UUID(), endpoint: URL(string: "http://127.0.0.1:7392")!, name: " ")
    #expect(valid.name == "127.0.0.1")
    let encoder = JSONEncoder()
    encoder.outputFormatting = .withoutEscapingSlashes
    let data = try encoder.encode(valid)
    let invalid = String(decoding: data, as: UTF8.self).replacingOccurrences(
      of: "http://127.0.0.1:7392", with: "http://remote.example")
    #expect(throws: DaemonClientError.self) {
      try JSONDecoder().decode(BridgeProfile.self, from: Data(invalid.utf8))
    }
  }

  @Test func duplicateMachineCannotCreateAmbiguousProfiles() throws {
    let suite = "cappuccino-duplicate-\(UUID().uuidString)"
    let defaults = UserDefaults(suiteName: suite)!
    defer { defaults.removePersistentDomain(forName: suite) }
    let machine = UUID()
    let first = try BridgeProfile(
      machineID: machine, endpoint: URL(string: "https://first.example")!, name: "First")
    let second = try BridgeProfile(
      machineID: machine, endpoint: URL(string: "https://second.example")!, name: "Second")
    #expect(throws: DaemonClientError.self) {
      try BridgeProfileStore(defaults: defaults).save([first, second])
    }
    #expect(try BridgeProfileStore(defaults: defaults).read().isEmpty)
  }
}
