import Foundation
import DaemonCore

// Minimal threaded TCP listener. One thread per connection is acceptable for a
// spike with a handful of CLI clients; the wire format stays provisional.

public final class TcpListener: @unchecked Sendable {
  let port: UInt16
  let state: DaemonState
  let herdr: HerdrClient
  var listenFd: Int32 = -1

  public init(port: UInt16, state: DaemonState, herdr: HerdrClient) {
    self.port = port
    self.state = state
    self.herdr = herdr
  }

  /// Binds 127.0.0.1 only: no public ingress for the spike.
  public func bind() throws {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else { throw DaemonError.internalError("socket() failed") }
    var yes: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = port.bigEndian
    addr.sin_addr = in_addr(s_addr: INADDR_LOOPBACK.bigEndian)
    let bound = withUnsafePointer(to: &addr) { ptr in
      ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
        Darwin.bind(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
      }
    }
    guard bound == 0 else { throw DaemonError.internalError("bind 127.0.0.1:\(port) failed") }
    guard listen(fd, 16) == 0 else { throw DaemonError.internalError("listen failed") }
    listenFd = fd
  }

  public func acceptLoop() {
    while true {
      let client = accept(listenFd, nil, nil)
      guard client >= 0 else { return }
      let handler = ConnectionHandler(fd: client, state: state, herdr: herdr)
      Thread { handler.run() }.start()
    }
  }
}

public final class ConnectionHandler: @unchecked Sendable {
  let fd: Int32
  let state: DaemonState
  let herdr: HerdrClient
  var stream: (sessionId: String, lastSeq: Int, generation: Int)?

  init(fd: Int32, state: DaemonState, herdr: HerdrClient) {
    self.fd = fd
    self.state = state
    self.herdr = herdr
  }

  func run() {
    defer { close(fd) }
    var buffer = Data()
    let chunk = UnsafeMutableRawPointer.allocate(
      byteCount: 65536, alignment: MemoryLayout<UInt8>.alignment)
    defer { chunk.deallocate() }
    while true {
      if let newline = buffer.firstIndex(of: 0x0A) {
        let lineData = buffer.subdata(in: buffer.startIndex..<newline)
        buffer.removeSubrange(buffer.startIndex...newline)
        guard !lineData.isEmpty else { continue }
        guard let line = String(data: lineData, encoding: .utf8) else {
          sendError(code: "bad_request", message: "line is not UTF-8")
          return
        }
        if !handle(line: line) { return }
      } else {
        let n = recv(fd, chunk, 65536, 0)
        guard n > 0 else { return }
        buffer.append(Data(bytes: chunk, count: n))
        if buffer.count > 1024 * 1024 {
          sendError(code: "bad_request", message: "request line exceeds 1 MiB")
          return
        }
      }
    }
  }

  /// Returns false when the connection must close.
  func handle(line: String) -> Bool {
    guard let message = try? JSONValue.decode(Data(line.utf8)),
      let op = message["op"]?.stringValue
    else {
      sendError(code: "bad_request", message: "malformed JSON line")
      return false
    }
    guard state.pairing.authorize(message["token"]?.stringValue) else {
      // Visible failure, then close. Never retry silently.
      sendError(code: "unauthorized", message: "pairing token rejected")
      return false
    }
    switch op {
    case "pair":
      send(["event": .string("paired"), "machine_id": .string(state.catalog.machineId), "protocol": .number(1)])
      return true
    case "ping":
      send(["event": .string("pong")])
      return true
    case "list":
      do {
        let agents = try state.catalog.listAgents()
        send([
          "event": .string("agents"),
          "machine_id": .string(state.catalog.machineId),
          "agents": .array(agents.map { $0.json }),
        ])
      } catch {
        sendError(code: "internal", message: "\(error)")
      }
      return true
    case "stream":
      guard let sessionId = message["session_id"]?.stringValue else {
        sendError(code: "bad_request", message: "stream requires session_id")
        return false
      }
      let afterSeq = message["after_seq"]?.intValue ?? -1
      do {
        let ring = try state.ensureStream(sessionId: sessionId)
        stream = (sessionId, afterSeq, ring.generation)
        send([
          "event": .string("stream_open"),
          "session_id": .string(sessionId),
          "generation": .number(Double(ring.generation)),
        ])
        flushStream()
        // Live appends: wake whenever the poll loop broadcasts new entries.
        Thread { [weak self] in
          while let handler = self {
            handler.state.awaitChange(timeout: 5)
            if !handler.flushStream() { return }
          }
        }.start()
      } catch let error as DaemonError {
        sendError(code: error.code, message: error.description)
      } catch {
        sendError(code: "internal", message: "\(error)")
      }
      return true
    default:
      sendError(code: "bad_request", message: "unknown op \(op)")
      return false
    }
  }

  /// Push any entries past the client cursor; called on connect and after each
  /// poll broadcast while a stream is active. Returns false once the
  /// connection is gone (its file descriptor closed).
  @discardableResult
  func flushStream() -> Bool {
    guard let cursor = stream, let ring = state.ringFor(cursor.sessionId) else { return true }
    if ring.generation != cursor.generation {
      self.stream?.generation = ring.generation
      self.stream?.lastSeq = 0
      send(["event": .string("stream_reset"), "generation": .number(Double(ring.generation))])
    }
    let (entries, _) = ring.replay(after: cursor.lastSeq)
    if let last = entries.last, last.seq >= 0 { self.stream?.lastSeq = last.seq }
    guard !entries.isEmpty else { return true }
    send(["event": .string("entries"), "entries": .array(entries.map { $0.json })])
    return true
  }

  func send(_ object: [String: JSONValue]) {
    let value = JSONValue.object(object)
    guard var line = try? value.encode() else { return }
    line.append(0x0A)
    _ = line.withUnsafeBytes { raw in
      Darwin.send(fd, raw.baseAddress, raw.count, 0)
    }
  }

  func sendError(code: String, message: String) {
    send(["event": .string("error"), "code": .string(code), "message": .string(message)])
  }
}
