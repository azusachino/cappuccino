import Foundation

/// Adapter for the frozen reference daemon (slice A): it alone still requires
/// its pairing token, sourced from the Keychain store. The production bridge
/// has no auth; this adapter exists so the reference transport stays
/// selectable without dragging token storage into the bridge path.
public struct ReferenceDaemonAdapter: DaemonServing {
  public let tokens: TokenStoring

  public init(tokens: TokenStoring) {
    self.tokens = tokens
  }

  public func pair(baseURL: URL) async throws -> PairedMachine {
    try await DaemonClient(clientURL: baseURL).pair(token: try requiredToken())
  }

  public func listAgents(baseURL: URL) async throws -> [AgentRow] {
    try await DaemonClient(clientURL: baseURL).listAgents(token: try requiredToken())
  }

  private func requiredToken() throws -> String {
    guard let token = tokens.loadToken(), !token.isEmpty else {
      throw DaemonClientError.unauthorized
    }
    return token
  }
}

extension DaemonClient {
  /// Builds a client for an arbitrary machine URL (host + port).
  init(clientURL: URL, timeout: TimeInterval = 5) {
    let components = URLComponents(url: clientURL, resolvingAgainstBaseURL: false)
    let host = components?.host ?? "127.0.0.1"
    let port = UInt16(components?.port ?? 7391)
    self.init(host: host, port: port, timeout: timeout)
  }
}
