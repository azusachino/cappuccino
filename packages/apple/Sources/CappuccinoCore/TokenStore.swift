import Foundation
import Security

// Pairing token storage. The token is a secret: it lives only in the Keychain
// (device-local, after first unlock) or in a test double — never in
// UserDefaults, files, logs or screenshots (behavior spec v0; AGENTS.md).

public protocol TokenStoring: Sendable {
  func saveToken(_ token: String) throws
  func loadToken() -> String?
  func deleteToken()
}

public enum TokenStoreError: Error, Equatable {
  case unhandled(OSStatus)
}

/// Keychain-backed store used by the app.
public struct KeychainTokenStore: TokenStoring {
  public let service: String
  public let account: String

  public init(service: String = "com.azusachino.cappuccino.daemon", account: String = "pairing-token") {
    self.service = service
    self.account = account
  }

  private var baseQuery: [String: Any] {
    [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
    ]
  }

  public func saveToken(_ token: String) throws {
    var query = baseQuery
    SecItemDelete(query as CFDictionary)
    query[kSecValueData as String] = Data(token.utf8)
    query[kSecAttrAccessible as String] =
      kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    let status = SecItemAdd(query as CFDictionary, nil)
    guard status == errSecSuccess else {
      throw TokenStoreError.unhandled(status)
    }
  }

  public func loadToken() -> String? {
    var query = baseQuery
    query[kSecReturnData as String] = true
    query[kSecMatchLimit as String] = kSecMatchLimitOne
    var result: AnyObject?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else { return nil }
    return String(data: data, encoding: .utf8)
  }

  public func deleteToken() {
    SecItemDelete(baseQuery as CFDictionary)
  }
}

/// Hermetic store for previews and tests.
public final class InMemoryTokenStore: TokenStoring, @unchecked Sendable {
  private let lock = NSLock()
  private var token: String?

  public init() {}

  public func saveToken(_ token: String) throws {
    lock.lock()
    defer { lock.unlock() }
    self.token = token
  }

  public func loadToken() -> String? {
    lock.lock()
    defer { lock.unlock() }
    return token
  }

  public func deleteToken() {
    lock.lock()
    defer { lock.unlock() }
    token = nil
  }
}
