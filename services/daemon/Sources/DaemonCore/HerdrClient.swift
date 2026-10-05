import Foundation

// One-shot newline-delimited JSON request/response client for the Herdr Unix
// socket. Every request opens a short-lived connection; long-lived transcript
// streams are built by polling `pane.read` instead of `events.subscribe`, which
// keeps slice A read-only and makes reconnection explicit.

public struct HerdrClient: Sendable {
  public let socketPath: String

  public init(socketPath: String? = nil) {
    if let explicit = socketPath ?? ProcessInfo.processInfo.environment["HERDR_SOCKET_PATH"] {
      self.socketPath = explicit
    } else {
      self.socketPath = NSString(string: "~/.config/herdr/herdr.sock").expandingTildeInPath
    }
  }

  public enum HerdrError: Error, CustomStringConvertible {
    case connectionFailed(String)
    case protocolError(String)
    case serverError(code: String, message: String)

    public var description: String {
      switch self {
      case .connectionFailed(let p): return "herdr: cannot connect to \(p)"
      case .protocolError(let m): return "herdr: protocol error: \(m)"
      case .serverError(let c, let m): return "herdr: \(c): \(m)"
      }
    }
  }

  /// Sends one request and returns the `result` object of the response.
  public func request(method: String, params: [String: JSONValue], timeout: TimeInterval = 10)
    throws -> JSONValue
  {
    let fd = try Self.connectSocket(path: socketPath)
    defer { close(fd) }
    Self.applyReadTimeout(fd: fd, timeout: timeout)
    try Self.writeRequest(fd: fd, method: method, params: params)
    let line = try Self.readLine(fd: fd, maxBytes: 16_777_216)
    return try Self.parseResult(line: line)
  }

  static func applyReadTimeout(fd: Int32, timeout: TimeInterval) {
    let seconds = Int(timeout)
    var tv = timeval(tv_sec: seconds, tv_usec: 0)
    _ = setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
  }

  static func connectSocket(path: String) throws -> Int32 {
    let fd = socket(AF_UNIX, SOCK_STREAM, 0)
    guard fd >= 0 else {
      throw HerdrError.connectionFailed(path)
    }
    var pathBytes = Array(path.utf8CString)
    pathBytes.removeLast()
    if pathBytes.count >= 104 {
      close(fd)
      throw HerdrError.connectionFailed("socket path too long: \(path)")
    }
    // sockaddr_un wire layout on darwin: [sun_len, sun_family, sun_path...]
    var bytes = [UInt8](repeating: 0, count: MemoryLayout<sockaddr_un>.size)
    bytes[0] = UInt8(MemoryLayout<sockaddr_un>.size)
    bytes[1] = UInt8(AF_UNIX)
    for (index, byte) in pathBytes.enumerated() {
      bytes[2 + index] = UInt8(bitPattern: byte)
    }
    var connected = Int32(-1)
    bytes.withUnsafeBufferPointer { buffer in
      let sa = UnsafeRawPointer(buffer.baseAddress!).assumingMemoryBound(to: sockaddr.self)
      connected = Darwin.connect(fd, sa, socklen_t(bytes.count))
    }
    if connected != 0 {
      close(fd)
      throw HerdrError.connectionFailed(path)
    }
    return fd
  }

  static func writeRequest(fd: Int32, method: String, params: [String: JSONValue]) throws {
    let request = JSONValue.object([
      "id": .string("cap-\(UUID().uuidString)"),
      "method": .string(method),
      "params": .object(params),
    ])
    var payload = try request.encode()
    payload.append(0x0A)
    var sent = Int(-1)
    payload.withUnsafeBytes { raw in
      sent = Darwin.send(fd, raw.baseAddress, raw.count, 0)
    }
    if sent != payload.count {
      throw HerdrError.protocolError("short write to herdr socket")
    }
  }

  static func readLine(fd: Int32, maxBytes: Int) throws -> Data {
    var line = Data()
    var chunk = [UInt8](repeating: 0, count: 65536)
    var pending = Data()
    while true {
      let n = read(fd, &chunk, chunk.count)
      guard n > 0 else {
        throw HerdrError.protocolError("connection closed mid-response")
      }
      let slice = chunk[0..<n]
      if let newline = slice.firstIndex(of: 0x0A) {
        pending.append(contentsOf: slice[0..<newline])
        line.append(pending)
        if line.count > maxBytes {
          throw HerdrError.protocolError("response line too long")
        }
        return line
      }
      pending.append(contentsOf: slice)
    }
  }

  static func parseResult(line: Data) throws -> JSONValue {
    let response = try JSONValue.decode(line)
    if let error = response["error"], let code = error["code"]?.stringValue {
      let message = error["message"]?.stringValue ?? ""
      throw HerdrError.serverError(code: code, message: message)
    }
    guard let result = response["result"] else {
      throw HerdrError.protocolError("response missing result")
    }
    return result
  }
}
