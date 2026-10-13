import Foundation

/// A saved endpoint is bound to a verified machine UUID, not to the hostname or agent locator.
public struct BridgeProfile: Identifiable, Codable, Hashable, Sendable {
  public let id: UUID
  public let machineID: UUID
  public let endpoint: URL
  public var name: String

  public init(id: UUID = UUID(), machineID: UUID, endpoint: URL, name: String) throws {
    guard let components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false),
      let host = components.host, !host.isEmpty,
      components.user == nil, components.password == nil,
      components.query == nil, components.fragment == nil,
      components.scheme?.lowercased() == "https"
        || (components.scheme?.lowercased() == "http"
          && ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host.lowercased()))
    else {
      throw BridgeError.protocolError(
        "Use HTTPS or a local loopback URL without credentials or query parameters")
    }
    self.id = id
    self.machineID = machineID
    self.endpoint = endpoint
    let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
    self.name = label.isEmpty ? host : label
  }

  private enum CodingKeys: String, CodingKey { case id, machineID, endpoint, name }

  public init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    try self.init(
      id: values.decode(UUID.self, forKey: .id),
      machineID: values.decode(UUID.self, forKey: .machineID),
      endpoint: values.decode(URL.self, forKey: .endpoint),
      name: values.decode(String.self, forKey: .name))
  }
}

/// Stores only profiles. Drafts, reading preferences and legacy endpoint data are separate.
public struct BridgeProfileStore {
  private let defaults: UserDefaults
  private let key = "bridge.machineProfiles.v1"

  public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

  public func read() throws -> [BridgeProfile] {
    guard let data = defaults.data(forKey: key) else { return [] }
    let profiles = try JSONDecoder().decode([BridgeProfile].self, from: data)
    try validate(profiles)
    return profiles
  }

  public func save(_ profiles: [BridgeProfile]) throws {
    try validate(profiles)
    defaults.set(try JSONEncoder().encode(profiles), forKey: key)
  }

  private func validate(_ profiles: [BridgeProfile]) throws {
    guard Set(profiles.map(\.id)).count == profiles.count,
      Set(profiles.map(\.machineID)).count == profiles.count
    else {
      throw BridgeError.protocolError("Duplicate saved profile or machine identity")
    }
  }
}
