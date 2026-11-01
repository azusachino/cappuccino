import Foundation
import Testing

@testable import CappuccinoCore

@Suite struct HTTPOverStreamTests {
  @Test func encodesARequestWithHostAndCloseAndAnExactLengthBody() {
    let wire = encodeHTTPRequest(
      method: "POST", target: "/v1/prompt", host: "bridge", headers: ["Accept": "application/json"],
      body: Data("{}".utf8))
    let text = String(decoding: wire, as: UTF8.self)
    #expect(text.hasPrefix("POST /v1/prompt HTTP/1.1\r\nHost: bridge\r\nConnection: close\r\n"))
    #expect(text.contains("Accept: application/json\r\n"))
    #expect(text.contains("Content-Length: 2\r\n\r\n{}"))
  }

  @Test func aGetHasNoLengthHeader() {
    let text = String(
      decoding: encodeHTTPRequest(method: "GET", target: "/", host: "h"), as: UTF8.self)
    #expect(!text.contains("Content-Length"))
  }

  @Test func parsesAContentLengthBodyAndLowercasesHeaders() throws {
    let raw = Data(
      "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"a\":1}EXTRA"
        .utf8)
    let response = try parseHTTPResponse(raw)
    #expect(response.status == 200)
    #expect(response.headers["content-type"] == "application/json")
    #expect(String(decoding: response.body, as: UTF8.self) == "{\"a\":1}")
  }

  @Test func decodesChunkedBodiesWithExtensionsAndTrailingZeroChunk() throws {
    let raw = Data(
      "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4;ext=1\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        .utf8)
    #expect(String(decoding: try parseHTTPResponse(raw).body, as: UTF8.self) == "Wikipedia")
  }

  @Test func readsToEndOfStreamWhenNoFramingIsGiven() throws {
    let raw = Data("HTTP/1.1 404 Not Found\r\n\r\nmissing".utf8)
    let response = try parseHTTPResponse(raw)
    #expect(response.status == 404)
    #expect(String(decoding: response.body, as: UTF8.self) == "missing")
  }

  @Test func rejectsBrokenStatusHeadersAndTruncatedBodies() {
    #expect(throws: HTTPWireError.malformedStatusLine) {
      try parseHTTPResponse(Data("FTP 200\r\n\r\n".utf8))
    }
    #expect(throws: HTTPWireError.malformedHeaders) {
      try parseHTTPResponse(Data("HTTP/1.1 200 OK".utf8))
    }
    #expect(throws: HTTPWireError.truncatedBody) {
      try parseHTTPResponse(Data("HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nshort".utf8))
    }
    #expect(throws: HTTPWireError.malformedChunk) {
      try parseHTTPResponse(
        Data("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n".utf8))
    }
  }
}
