import Foundation
import DaemonCore

// capctl: CLI test client for the spike wire format.
//
//   capctl pair   --port 7391 --token TOKEN
//   capctl list   --port 7391 --token TOKEN
//   capctl stream --port 7391 --token TOKEN --session NAME [--after N] [--duration S]

func fail(_ message: String) -> Never {
  FileHandle.standardError.write(Data("capctl: \(message)\n".utf8))
  exit(1)
}

func connect(port: UInt16) -> Int32 {
  let fd = socket(AF_INET, SOCK_STREAM, 0)
  guard fd >= 0 else { fail("socket() failed") }
  var addr = sockaddr_in()
  addr.sin_family = sa_family_t(AF_INET)
  addr.sin_port = port.bigEndian
  addr.sin_addr = in_addr(s_addr: INADDR_LOOPBACK.bigEndian)
  let connected = withUnsafePointer(to: &addr) { ptr in
    ptr.withMemoryRebound(to: sockaddr.self, capacity: 1) { sa in
      connect(fd, sa, socklen_t(MemoryLayout<sockaddr_in>.size))
    }
  }
  guard connected == 0 else { fail("connect 127.0.0.1:\(port) failed: errno \(errno)") }
  return fd
}

func sendLine(fd: Int32, _ object: [String: JSONValue]) {
  let value = JSONValue.object(object)
  guard var line = try? value.encode() else { fail("encode failed") }
  line.append(0x0A)
  let sent = line.withUnsafeBytes { raw in
    send(fd, raw.baseAddress, raw.count, 0)
  }
  guard sent == line.count else { fail("send failed") }
}

/// Reads one NDJSON event, with a hard deadline.
func readEvent(fd: Int32, buffer: inout [UInt8], timeout: TimeInterval) -> JSONValue? {
  var tv = timeval(tv_sec: Int(timeout), tv_usec: 0)
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size))
  while let newline = buffer.firstIndex(of: 0x0A) {
    let line = Data(buffer[0..<newline])
    buffer.removeSubrange(0...newline)
    if let event = try? JSONValue.decode(line) { return event }
  }
  var chunk = [UInt8](repeating: 0, count: 64 * 1024)
  let n = buffer.withUnsafeMutableBytes { _ in recv(fd, &chunk, chunk.count, 0) }
  guard n > 0 else { return nil }
  buffer.append(contentsOf: chunk[0..<n])
  if let newline = buffer.firstIndex(of: 0x0A) {
    let line = Data(buffer[0..<newline])
    buffer.removeSubrange(0...newline)
    return try? JSONValue.decode(line)
  }
  return readEvent(fd: fd, buffer: &buffer, timeout: timeout)
}

func printEvent(_ event: JSONValue) {
  print(event.rendered)
  fflush(stdout)
}

var port: UInt16 = 7391
var token: String = ""
var command = ""
var session = ""
var after = -1
var duration = 20.0
var args = Array(ProcessInfo.processInfo.arguments.dropFirst())
while !args.isEmpty {
  let flag = args.removeFirst()
  func value() -> String { args.isEmpty ? "" : args.removeFirst() }
  switch flag {
  case "pair", "list", "stream", "ping": command = flag
  case "--port": port = UInt16(value()) ?? 7391
  case "--token": token = value()
  case "--session": session = value()
  case "--after": after = Int(value()) ?? -1
  case "--duration": duration = Double(value()) ?? 20.0
  default: fail("unknown flag \(flag)")
  }
}

let fd = connect(port: port)
var buffer: [UInt8] = []

var deadline = Date().addingTimeInterval(duration)
switch command {
case "stream":
  guard !session.isEmpty else { fail("stream requires --session") }
  sendLine(fd: fd, ["op": .string("stream"), "token": .string(token), "session_id": .string(session), "after_seq": .number(Double(after))])
  while Date() < deadline {
    if let event = readEvent(fd: fd, buffer: &buffer, timeout: 1) {
      printEvent(event)
      if event["event"]?.stringValue == "error" { exit(1) }
    }
  }
case "pair", "list", "ping":
  sendLine(fd: fd, ["op": .string(command), "token": .string(token)])
  if let event = readEvent(fd: fd, buffer: &buffer, timeout: 5) {
    printEvent(event)
    if event["event"]?.stringValue == "error" { exit(1) }
  } else {
    fail("no response within 5 s")
  }
default:
  fail("command required: pair | list | stream | ping")
}
