import Foundation

// Manual one-time pairing token. The token is generated on first daemon start,
// stored with 0600 permissions outside the repository, and never logged.

public final class PairingStore: @unchecked Sendable {
  public let token: String
  private static let dir =
    NSString(string: "~/Library/Application Support/cappuccino-spike").expandingTildeInPath
  private static let file = "\(dir)/pairing-token"

  /// Token source order: CAPP_SPIKE_TOKEN env, token file, freshly generated
  /// file. Tests pass an explicit path.
  public convenience init() {
    if let env = ProcessInfo.processInfo.environment["CAPP_SPIKE_TOKEN"], !env.isEmpty {
      self.init(token: env)
      return
    }
    let path = PairingStore.file
    if let existing = try? String(contentsOfFile: path, encoding: .utf8)
      .trimmingCharacters(in: .whitespacesAndNewlines), !existing.isEmpty
    {
      self.init(token: existing)
      return
    }
    self.init(file: path)
  }

  public init(token: String) {
    self.token = token
  }

  /// Creates (or reuses) a token file outside git.
  public init(file: String) {
    let fm = FileManager.default
    if let existing = try? String(contentsOfFile: file, encoding: .utf8)
      .trimmingCharacters(in: .whitespacesAndNewlines), !existing.isEmpty
    {
      token = existing
      return
    }
    var bytes = [UInt8](repeating: 0, count: 32)
    _ = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
    let fresh = bytes.map { String(format: "%02x", $0) }.joined()
    try? fm.createDirectory(
      atPath: (file as NSString).deletingLastPathComponent, withIntermediateDirectories: true)
    fm.createFile(atPath: file, contents: Data(fresh.utf8), attributes: [.posixPermissions: 0o600])
    token = fresh
  }

  public func authorize(_ presented: String?) -> Bool {
    guard let presented else { return false }
    // Constant-time comparison; the token is the only shared secret.
    guard presented.count == token.count else { return false }
    var diff: UInt8 = 0
    for (a, b) in zip(presented.utf8, token.utf8) { diff |= a ^ b }
    return diff == 0
  }
}
