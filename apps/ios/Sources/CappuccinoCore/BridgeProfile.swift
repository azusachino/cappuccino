import Foundation

/// A saved endpoint is bound to a verified machine UUID, not to the hostname or agent locator.
public struct BridgeProfile: Identifiable, Codable, Hashable, Sendable {
  public let id: UUID
  public let machineID: UUID
  public let endpoint: URL
  public var name: String
  /// Dial details for an iroh machine; nil for a machine reached over HTTP(S).
  public let iroh: IrohEndpointAddr?
  /// Tried after the iroh path fails during discovery. Nil for a machine with no tailnet address.
  public let tailnetFallback: URL?

  public init(
    id: UUID = UUID(), machineID: UUID, endpoint: URL, name: String,
    iroh: IrohEndpointAddr? = nil, tailnetFallback: URL? = nil
  ) throws {
    guard let components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false),
      let host = components.host, !host.isEmpty,
      components.user == nil, components.password == nil,
      components.query == nil, components.fragment == nil,
      Self.isAllowedScheme(components, host: host, iroh: iroh)
    else {
      throw BridgeError.protocolError(
        "Use HTTPS or a local loopback URL without credentials or query parameters")
    }
    if let tailnetFallback, !Self.isValidFallback(tailnetFallback) {
      throw BridgeError.protocolError("Tailnet fallback must be HTTPS or a local loopback URL")
    }
    self.id = id
    self.machineID = machineID
    self.endpoint = endpoint
    self.iroh = iroh
    self.tailnetFallback = tailnetFallback
    let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
    self.name = label.isEmpty ? host : label
  }

  /// An iroh endpoint URL names its peer in the host and must match the paired address.
  private static func isAllowedScheme(
    _ components: URLComponents, host: String, iroh: IrohEndpointAddr?
  ) -> Bool {
    let scheme = components.scheme?.lowercased()
    if scheme == irohURLScheme { return iroh?.endpointID == host }
    return scheme == "https"
      || (scheme == "http"
        && ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host.lowercased()))
  }

  private enum CodingKeys: String, CodingKey {
    case id, machineID, endpoint, name, iroh, tailnetFallback
  }

  public init(from decoder: Decoder) throws {
    let values = try decoder.container(keyedBy: CodingKeys.self)
    try self.init(
      id: values.decode(UUID.self, forKey: .id),
      machineID: values.decode(UUID.self, forKey: .machineID),
      endpoint: values.decode(URL.self, forKey: .endpoint),
      name: values.decode(String.self, forKey: .name),
      iroh: values.decodeIfPresent(IrohEndpointAddr.self, forKey: .iroh),
      tailnetFallback: values.decodeIfPresent(URL.self, forKey: .tailnetFallback))
  }

  /// A fallback is an HTTP(S) or loopback URL, never another iroh URL.
  private static func isValidFallback(_ url: URL) -> Bool {
    guard let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
      let host = components.host, !host.isEmpty,
      components.user == nil, components.password == nil,
      components.query == nil, components.fragment == nil
    else { return false }
    return isAllowedScheme(components, host: host, iroh: nil)
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
