import Foundation

/// HTTP/1.1 over one iroh stream. The bridge pipes each `cappuccino/1` stream into its local HTTP
/// server, so the client writes a request and reads the response to end-of-stream. Requests ask
/// for `Connection: close`, so the response always ends the stream.

public struct HTTPWireResponse: Equatable, Sendable {
  public let status: Int
  /// Header names are lowercased.
  public let headers: [String: String]
  public let body: Data
}

public enum HTTPWireError: Error, Equatable, Sendable {
  case malformedStatusLine
  case malformedHeaders
  case malformedChunk
  case truncatedBody
}

/// Serializes one request. The body, when present, carries an exact Content-Length.
public func encodeHTTPRequest(
  method: String, target: String, host: String, headers: [String: String] = [:], body: Data = Data()
) -> Data {
  var lines = ["\(method) \(target) HTTP/1.1", "Host: \(host)", "Connection: close"]
  for (name, value) in headers.sorted(by: { $0.key < $1.key }) {
    lines.append("\(name): \(value)")
  }
  if !body.isEmpty || method == "POST" || method == "PUT" {
    lines.append("Content-Length: \(body.count)")
  }
  var out = Data((lines.joined(separator: "\r\n") + "\r\n\r\n").utf8)
  out.append(body)
  return out
}

/// Parses a complete response read to end-of-stream. Body framing follows Content-Length, then
/// chunked transfer coding, then end-of-stream.
public func parseHTTPResponse(_ data: Data) throws -> HTTPWireResponse {
  let separator = Data("\r\n\r\n".utf8)
  guard let split = data.range(of: separator) else { throw HTTPWireError.malformedHeaders }
  let head = String(decoding: data[..<split.lowerBound], as: UTF8.self)
  var body = Data(data[split.upperBound...])
  let lines = head.components(separatedBy: "\r\n")
  let parts = lines[0].split(separator: " ", maxSplits: 2)
  guard parts.count >= 2, parts[0].hasPrefix("HTTP/1."), let status = Int(parts[1]) else {
    throw HTTPWireError.malformedStatusLine
  }
  var headers: [String: String] = [:]
  for line in lines.dropFirst() where !line.isEmpty {
    guard let colon = line.firstIndex(of: ":") else { throw HTTPWireError.malformedHeaders }
    let name = line[..<colon].lowercased()
    headers[name] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
  }
  if headers["transfer-encoding"]?.lowercased() == "chunked" {
    body = try decodeChunked(body)
  } else if let length = headers["content-length"].flatMap(Int.init) {
    guard body.count >= length else { throw HTTPWireError.truncatedBody }
    body = body.prefix(length)
  }
  return HTTPWireResponse(status: status, headers: headers, body: body)
}

private func decodeChunked(_ data: Data) throws -> Data {
  var out = Data()
  var rest = data[...]
  let crlf = Data("\r\n".utf8)
  while true {
    guard let lineEnd = rest.range(of: crlf) else { throw HTTPWireError.malformedChunk }
    let sizeText = String(decoding: rest[..<lineEnd.lowerBound], as: UTF8.self)
    guard let size = Int(sizeText.split(separator: ";").first ?? "", radix: 16) else {
      throw HTTPWireError.malformedChunk
    }
    rest = rest[lineEnd.upperBound...]
    if size == 0 { return out }
    guard rest.count >= size + 2 else { throw HTTPWireError.truncatedBody }
    out.append(rest.prefix(size))
    rest = rest.dropFirst(size + 2)
  }
}
