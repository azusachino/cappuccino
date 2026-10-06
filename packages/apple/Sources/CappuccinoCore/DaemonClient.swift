import Foundation
import Network

// Transport seam: UI and higher-level logic depend only on `DaemonServing`,
// and the wire client depends only on `LineConnecting`/`LineConnection`. The
// TCP implementation below is the reference transport; the 2026-10-06
// architecture pivot (SSH-exec driving the existing herdr CLI) lands as
// another `LineConnecting` without touching any UI or parsing logic.
//
// One connection = one request: the daemon answers each op with a small burst
// of events and closes short-lived client connections anyway. Long-lived
// transcript streaming is a later slice.

public protocol LineConnection: Sendable {
  func send(_ payload: Data) async throws
  /// Reads one newline-terminated line (newline stripped). Returns nil on end.
  func receiveLine() async throws -> Data?
  func close()
}

public protocol LineConnecting: Sendable {
  func connect(host: String, port: UInt16, timeout: TimeInterval) async throws -> LineConnection
}

public struct TCPLineConnectionFactory: LineConnecting {
  public init() {}

  public func connect(host: String, port: UInt16, timeout: TimeInterval) async throws
    -> LineConnection
  {
    try await TCPLineConnection.connect(host: host, port: port, timeout: timeout)
  }
}

/// Resumes a continuation exactly once; the ready callback and the timeout
/// race each other by design.
final class OnceResumer: @unchecked Sendable {
  private let lock = NSLock()
  private var resumed = false
  private var continuation: CheckedContinuation<Void, Error>?

  func bind(_ continuation: CheckedContinuation<Void, Error>) {
    lock.lock()
    defer { lock.unlock() }
    self.continuation = continuation
  }

  func resume(_ result: Result<Void, Error>) {
    lock.lock()
    guard !resumed else {
      lock.unlock()
      return
    }
    resumed = true
    let continuation = self.continuation
    self.continuation = nil
    lock.unlock()
    continuation?.resume(with: result)
  }
}

/// NWConnection-based newline-delimited JSON connection.
public final class TCPLineConnection: LineConnection, @unchecked Sendable {
  private let connection: NWConnection
  private let queue: DispatchQueue
  private var buffer = Data()
  private let stateLock = NSLock()
  private var closed = false

  private init(connection: NWConnection, queue: DispatchQueue) {
    self.connection = connection
    self.queue = queue
  }

  static func connect(host: String, port: UInt16, timeout: TimeInterval) async throws
    -> TCPLineConnection
  {
    let connection = NWConnection(
      host: NWEndpoint.Host(host),
      port: NWEndpoint.Port(rawValue: port) ?? 0,
      using: .tcp)
    let queue = DispatchQueue(label: "com.azusachino.cappuccino.daemon")
    let session = TCPLineConnection(connection: connection, queue: queue)
    let waiter = OnceResumer()
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      waiter.bind(continuation)
      connection.stateUpdateHandler = { state in
        switch state {
        case .ready:
          waiter.resume(.success(()))
        case .failed(let error):
          waiter.resume(.failure(DaemonClientError.unreachable(error.localizedDescription)))
        case .cancelled:
          waiter.resume(.failure(DaemonClientError.unreachable("connection cancelled")))
        default:
          break
        }
      }
      connection.start(queue: queue)
      DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { [weak connection] in
        waiter.resume(.failure(DaemonClientError.unreachable("timed out after \(Int(timeout))s")))
        connection?.cancel()
      }
    }
    return session
  }

  public func send(_ payload: Data) async throws {
    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
      connection.send(
        content: payload,
        completion: .contentProcessed { error in
          if let error {
            continuation.resume(
              throwing: DaemonClientError.unreachable("send failed: \(error.localizedDescription)"))
          } else {
            continuation.resume()
          }
        })
    }
  }

  public func receiveLine() async throws -> Data? {
    while true {
      if let newline = buffer.firstIndex(of: 0x0A) {
        let line = buffer.subdata(in: buffer.startIndex..<newline)
        buffer.removeSubrange(buffer.startIndex...newline)
        return line
      }
      let chunk = try await receiveChunk()
      guard let chunk else { return nil }
      buffer.append(chunk)
    }
  }

  private func receiveChunk() async throws -> Data? {
    try await withCheckedThrowingContinuation {
      (continuation: CheckedContinuation<Data?, Error>) in
      connection.receive(minimumIncompleteLength: 1, maximumLength: 64 * 1024) {
        content, _, isComplete, error in
        if let error {
          continuation.resume(
            throwing: DaemonClientError.unreachable("receive failed: \(error.localizedDescription)")
          )
        } else if let content {
          continuation.resume(returning: Data(content))
        } else if isComplete {
          continuation.resume(returning: nil)
        } else {
          continuation.resume(returning: nil)
        }
      }
    }
  }

  public func close() {
    stateLock.lock()
    guard !closed else {
      stateLock.unlock()
      return
    }
    closed = true
    stateLock.unlock()
    connection.cancel()
  }
}

/// Async client for the reference daemon's wire v0. The reference daemon
/// keeps its slice-A token (frozen history); the production bridge ignores
/// tokens entirely, which is why the token travels with the request.
public struct DaemonClient: Sendable {
  public let connector: LineConnecting
  public let host: String
  public let port: UInt16
  public let timeout: TimeInterval

  public init(
    connector: LineConnecting = TCPLineConnectionFactory(),
    host: String = "127.0.0.1",
    port: UInt16 = 7391,
    timeout: TimeInterval = 5
  ) {
    self.connector = connector
    self.host = host
    self.port = port
    self.timeout = timeout
  }

  /// Sends one request and returns the first daemon event that answers it,
  /// skipping unrelated events (e.g. a stray pong).
  private func request(_ object: [String: Any]) async throws -> JSONEvent {
    let connection = try await connector.connect(host: host, port: port, timeout: timeout)
    defer { connection.close() }
    try await connection.send(try DaemonWire.encodeRequest(object))
    while true {
      guard let line = try await connection.receiveLine() else {
        throw DaemonClientError.unreachable("daemon closed before answering")
      }
      do {
        let event = try DaemonWire.parseEvent(line)
        if case .agents = event { return event }
        if case .paired = event { return event }
      } catch let error as DaemonClientError {
        throw error
      } catch {
        // Unrelated events are skipped; protocol errors above are fatal.
      }
    }
  }

  public func pair(token: String) async throws -> PairedMachine {
    let event = try await request(["op": "pair", "token": token])
    guard case .paired(let machine) = event else {
      throw DaemonClientError.protocolError("pair answered with a non-pair event")
    }
    return machine
  }

  public func listAgents(token: String) async throws -> [AgentRow] {
    let event = try await request(["op": "list", "token": token])
    guard case .agents(_, let rows) = event else {
      throw DaemonClientError.protocolError("list answered with a non-list event")
    }
    return rows
  }
}
