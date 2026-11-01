import Foundation
import IrohLib
import Security

/// Ports io/IrohEndpointTransport.kt on the iroh-ffi 1.2.0 binding. One endpoint per app process.
/// It starts on first use and keeps one cached connection per endpoint and ALPN. A cached
/// connection that fails on open is dropped and dialed once more.

public enum IrohEndpointError: Error, Equatable, Sendable {
  case invalidEndpointID(String)
  case invalidSecretKey
}

/// One bi-directional iroh stream. Its send and receive halves are fetched once and reused.
public struct IrohStream: Sendable {
  private let send: SendStream
  private let recv: RecvStream
  private let onRelease: @Sendable () -> Void

  init(_ stream: BiStream, onRelease: @escaping @Sendable () -> Void) {
    send = stream.send()
    recv = stream.recv()
    self.onRelease = onRelease
  }

  /// Marks this stream as done, so the endpoint can count it toward its idle close.
  public func release() async {
    onRelease()
  }

  public func write(_ bytes: Data) async throws {
    try await send.writeAll(buf: bytes)
  }

  /// Ends our send side. The peer reads end-of-stream after the bytes already written.
  public func finish() async throws {
    try await send.finish()
  }

  /// The next chunk of at most `max` bytes. Empty data means the peer finished its send side.
  public func read(max: UInt32) async throws -> Data {
    try await recv.read(sizeLimit: max)
  }

  /// Tells the peer we no longer read this stream.
  public func stopReading() async throws {
    try await recv.stop(errorCode: 0)
  }
}

/// The process's iroh endpoint. Dials use the n0 preset, as Android does.
public actor IrohEndpointService {
  public static let shared = IrohEndpointService(loadSecretKey: IrohSecretKeyStore.loadOrCreate)

  private let loadSecretKey: @Sendable () throws -> Data
  private let idleCloseSeconds: Double
  private var endpoint: Endpoint?
  private var connections: [String: Connection] = [:]
  private var streams = StreamIdleTracker()
  private var idleClose: Task<Void, Never>?

  /// `idleCloseSeconds` is how long the endpoint stays up with no open stream (Android uses 60).
  public init(
    loadSecretKey: @escaping @Sendable () throws -> Data, idleCloseSeconds: Double = 60
  ) {
    self.loadSecretKey = loadSecretKey
    self.idleCloseSeconds = idleCloseSeconds
  }

  /// Opens a bi-stream to `addr` on `alpn`, reusing a live cached connection.
  public func openStream(to addr: IrohEndpointAddr, alpn: String) async throws -> IrohStream {
    do {
      return try await openBi(addr, alpn)
    } catch {
      // A dead cached connection fails on open. Drop it and dial once more.
      drop(key(addr, alpn))
      return try await openBi(addr, alpn)
    }
  }

  /// Closes every cached connection but keeps the endpoint. A redial after a network change
  /// calls this first, because a cached connection can stay pinned to a dead path.
  public func dropConnections() {
    for key in Array(connections.keys) { drop(key) }
  }

  /// Closes the endpoint and every connection on it.
  public func stop() async {
    dropConnections()
    if let endpoint {
      try? await endpoint.close()
    }
    endpoint = nil
  }

  private func openBi(_ addr: IrohEndpointAddr, _ alpn: String) async throws -> IrohStream {
    let key = key(addr, alpn)
    let connection: Connection
    if let cached = connections[key], cached.closeReason() == nil {
      connection = cached
    } else {
      drop(key)
      let endpoint = try await started()
      connection = try await endpoint.connect(
        addr: try Self.binding(addr), alpn: Data(alpn.utf8))
      connections[key] = connection
    }
    let stream = try await connection.openBi()
    streamOpened()
    return IrohStream(stream) { [self] in
      Task { await self.streamReleased() }
    }
  }

  private func streamOpened() {
    streams.opened()
    idleClose?.cancel()
    idleClose = nil
  }

  /// When the last open stream closes, the endpoint closes after the quiet period unless a new
  /// stream opens first.
  private func streamReleased() {
    guard streams.released() else { return }
    let seconds = idleCloseSeconds
    idleClose = Task {
      try? await Task.sleep(for: .seconds(seconds))
      guard !Task.isCancelled else { return }
      await self.stopIfStillIdle()
    }
  }

  private func stopIfStillIdle() async {
    guard streams.isIdle else { return }
    await stop()
  }

  private func started() async throws -> Endpoint {
    if let endpoint { return endpoint }
    let secret = try loadSecretKey()
    guard secret.count == 32 else { throw IrohEndpointError.invalidSecretKey }
    let bound = try await Endpoint.bind(
      options: EndpointOptions(
        preset: presetN0(), bindAddr: nil, secretKey: secret, alpns: []))
    endpoint = bound
    return bound
  }

  private func drop(_ key: String) {
    guard let connection = connections.removeValue(forKey: key) else { return }
    try? connection.close(errorCode: 0, reason: Data())
  }

  /// The binding's address type for a pairing address. An id that does not parse is rejected
  /// before any network use.
  static func binding(_ addr: IrohEndpointAddr) throws -> EndpointAddr {
    let id: EndpointId
    do {
      id = try EndpointId.fromString(s: addr.endpointID)
    } catch {
      throw IrohEndpointError.invalidEndpointID(addr.endpointID)
    }
    return EndpointAddr(id: id, relayUrl: addr.relayURL, addresses: addr.directAddrs)
  }

  private func key(_ addr: IrohEndpointAddr, _ alpn: String) -> String {
    "\(addr.endpointID)|\(alpn)"
  }
}

/// The app's iroh secret key, kept in the Keychain. It never leaves this device.
public enum IrohSecretKeyStore {
  static let service = "app.cappuccino.iroh"
  static let account = "endpoint-secret-key"

  public static func loadOrCreate() throws -> Data {
    if let existing = try read() { return existing }
    let fresh = SecretKey.generate().toBytes()
    try write(fresh)
    return fresh
  }

  private static func read() throws -> Data? {
    let query: [String: Any] = [
      kSecClassGenericPassword as String: true,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var item: CFTypeRef?
    let status = SecItemCopyMatching(query as CFDictionary, &item)
    if status == errSecItemNotFound { return nil }
    guard status == errSecSuccess, let data = item as? Data, data.count == 32 else {
      throw IrohEndpointError.invalidSecretKey
    }
    return data
  }

  private static func write(_ secret: Data) throws {
    let attributes: [String: Any] = [
      kSecClassGenericPassword as String: true,
      kSecAttrService as String: service,
      kSecAttrAccount as String: account,
      kSecValueData as String: secret,
      kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
    ]
    let status = SecItemAdd(attributes as CFDictionary, nil)
    guard status == errSecSuccess else { throw IrohEndpointError.invalidSecretKey }
  }
}

/// Counts open streams, so the endpoint can close after a quiet period with none open.
struct StreamIdleTracker: Equatable {
  private(set) var open = 0

  var isIdle: Bool { open == 0 }

  mutating func opened() {
    open += 1
  }

  /// True when the last open stream has just closed.
  mutating func released() -> Bool {
    open = max(open - 1, 0)
    return open == 0
  }
}
