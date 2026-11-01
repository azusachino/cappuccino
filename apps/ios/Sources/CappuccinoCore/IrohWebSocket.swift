import CryptoKit
import Foundation

/// The bridge's live event stream over an iroh stream. The bridge pipes each stream to its local
/// HTTP server, which upgrades it like any WebSocket. Used for `cappiroh://` stream URLs.

let webSocketGUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
private let handshakeLimit = 16 * 1024

public enum WebSocketWireError: Error, Equatable, Sendable {
  case handshakeRejected(Int)
  case badAccept
  case malformedHandshake
  case maskedServerFrame
  case unsupportedFrame(UInt8)
  case invalidText
}

/// The accept value for a client key (RFC 6455 section 4.2.2).
public func webSocketAccept(forKey key: String) -> String {
  Data(Insecure.SHA1.hash(data: Data((key + webSocketGUID).utf8))).base64EncodedString()
}

/// One client frame. Clients must mask every frame, so the payload is XORed with `maskKey`.
public func encodeClientFrame(opcode: UInt8, payload: Data, maskKey: [UInt8]) -> Data {
  var frame: [UInt8] = [0x80 | opcode]
  let length = payload.count
  if length < 126 {
    frame.append(0x80 | UInt8(length))
  } else if length <= 0xFFFF {
    frame.append(0x80 | 126)
    frame += [UInt8(length >> 8), UInt8(length & 0xFF)]
  } else {
    frame.append(0x80 | 127)
    frame += (0..<8).reversed().map { UInt8((length >> (8 * $0)) & 0xFF) }
  }
  frame += maskKey
  frame += payload.enumerated().map { $0.element ^ maskKey[$0.offset % 4] }
  return Data(frame)
}

public struct ServerFrame: Equatable, Sendable {
  public let fin: Bool
  public let opcode: UInt8
  public let payload: Data
}

/// Reads one unmasked server frame from the front of `buffer`, or nil while bytes are missing.
/// A masked server frame is a protocol violation.
public func decodeServerFrame(_ buffer: inout [UInt8]) throws -> ServerFrame? {
  guard buffer.count >= 2 else { return nil }
  if buffer[1] & 0x80 != 0 { throw WebSocketWireError.maskedServerFrame }
  var length = Int(buffer[1] & 0x7F)
  var header = 2
  if length == 126 {
    guard buffer.count >= 4 else { return nil }
    length = Int(buffer[2]) << 8 | Int(buffer[3])
    header = 4
  } else if length == 127 {
    guard buffer.count >= 10 else { return nil }
    length = buffer[2..<10].reduce(0) { $0 << 8 | Int($1) }
    header = 10
  }
  guard buffer.count >= header + length else { return nil }
  let frame = ServerFrame(
    fin: buffer[0] & 0x80 != 0, opcode: buffer[0] & 0x0F,
    payload: Data(buffer[header..<header + length]))
  buffer.removeFirst(header + length)
  return frame
}

/// Opens the upgraded WebSocket over one iroh stream and returns it as a `BridgeWebSocket`.
public struct IrohWebSocketConnector: BridgeWebSocketConnecting {
  public typealias Open = @Sendable (URL) async throws -> any IrohStreaming
  public typealias Key = @Sendable () -> String

  private let open: Open
  private let key: Key

  public init(
    open: @escaping Open = { try await IrohHub.shared.openStream(for: $0) },
    key: @escaping Key = { randomWebSocketKey() }
  ) {
    self.open = open
    self.key = key
  }

  public func connect(url: URL) async throws -> BridgeWebSocket {
    let stream = try await open(url)
    let clientKey = key()
    var target = url.path
    if let query = url.query { target += "?\(query)" }
    let request = [
      "GET \(target) HTTP/1.1", "Host: \(url.host ?? "")", "Upgrade: websocket",
      "Connection: Upgrade", "Sec-WebSocket-Key: \(clientKey)", "Sec-WebSocket-Version: 13",
      "", "",
    ].joined(separator: "\r\n")
    try await stream.write(Data(request.utf8))

    var buffer: [UInt8] = []
    let separator = Array("\r\n\r\n".utf8)
    while buffer.firstRange(of: separator) == nil {
      guard buffer.count < handshakeLimit else { throw WebSocketWireError.malformedHandshake }
      let chunk = try await stream.read(max: 4096)
      if chunk.isEmpty { throw WebSocketWireError.malformedHandshake }
      buffer += chunk
    }
    guard let end = buffer.firstRange(of: separator) else {
      throw WebSocketWireError.malformedHandshake
    }
    let head = String(decoding: buffer[..<end.lowerBound], as: UTF8.self)
    let lines = head.components(separatedBy: "\r\n")
    let status = lines.first?.split(separator: " ").dropFirst().first.flatMap { Int($0) }
    guard let status else { throw WebSocketWireError.malformedHandshake }
    guard status == 101 else { throw WebSocketWireError.handshakeRejected(status) }
    let accept = lines.dropFirst().first {
      $0.lowercased().hasPrefix("sec-websocket-accept:")
    }
    let value = accept?.dropFirst("sec-websocket-accept:".count)
      .trimmingCharacters(in: .whitespaces)
    guard value == webSocketAccept(forKey: clientKey) else { throw WebSocketWireError.badAccept }
    return IrohWebSocketConnection(stream: stream, buffered: Array(buffer[end.upperBound...]))
  }
}

/// A random 16-byte client key, base64 encoded, as the handshake requires.
public func randomWebSocketKey() -> String {
  Data((0..<16).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
}

extension Array where Element == UInt8 {
  fileprivate func firstRange(of separator: [UInt8]) -> Range<Int>? {
    guard count >= separator.count else { return nil }
    for start in 0...(count - separator.count)
    where Array(self[start..<start + separator.count]) == separator {
      return start..<start + separator.count
    }
    return nil
  }
}

/// The open WebSocket. Reads happen one at a time, from the caller's `receiveText` loop.
public final class IrohWebSocketConnection: BridgeWebSocket, @unchecked Sendable {
  private let stream: any IrohStreaming
  private var buffer: [UInt8]
  private let lock = NSLock()
  private var closed = false

  init(stream: any IrohStreaming, buffered: [UInt8]) {
    self.stream = stream
    self.buffer = buffered
  }

  public func receiveText() async throws -> String? {
    var message: [UInt8] = []
    var inMessage = false
    while true {
      guard let frame = try await nextFrame() else { return nil }
      switch frame.opcode {
      case 0x1, 0x0:
        if frame.opcode == 0x1 {
          guard !inMessage else { throw WebSocketWireError.unsupportedFrame(0x1) }
          inMessage = true
        } else {
          guard inMessage else { throw WebSocketWireError.unsupportedFrame(0x0) }
        }
        message += frame.payload
        if frame.fin {
          guard let text = String(bytes: message, encoding: .utf8) else {
            throw WebSocketWireError.invalidText
          }
          return text
        }
      case 0x8:
        await close()
        return nil
      case 0x9:
        try await send(opcode: 0xA, payload: frame.payload)
      case 0xA:
        continue
      default:
        throw WebSocketWireError.unsupportedFrame(frame.opcode)
      }
    }
  }

  public func close() async {
    guard markClosed() else { return }
    try? await send(opcode: 0x8, payload: Data())
    try? await stream.stopReading()
    await stream.release()
  }

  public func closeImmediately() {
    guard markClosed() else { return }
    let stream = self.stream
    Task { await stream.release() }
  }

  /// Marks the socket closed and returns true only for the first caller.
  private func markClosed() -> Bool {
    lock.withLock {
      guard !closed else { return false }
      closed = true
      return true
    }
  }

  private var isClosed: Bool { lock.withLock { closed } }

  private func nextFrame() async throws -> ServerFrame? {
    while !isClosed {
      if let frame = try decodeServerFrame(&buffer) { return frame }
      let chunk = try await stream.read(max: 64 * 1024)
      if chunk.isEmpty { return nil }
      buffer += chunk
    }
    return nil
  }

  private func send(opcode: UInt8, payload: Data) async throws {
    let mask = (0..<4).map { _ in UInt8.random(in: 0...255) }
    try await stream.write(encodeClientFrame(opcode: opcode, payload: payload, maskKey: mask))
  }
}
