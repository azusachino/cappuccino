import Foundation
import Testing

@testable import CappuccinoCore

@Suite(.serialized) struct IrohRequestBodyTests {
  @Test func bodyIsReadFromHTTPBody() {
    var request = URLRequest(url: URL(string: "cappiroh://peer/x")!)
    request.httpBody = Data("{\"a\":1}".utf8)
    #expect(requestBody(request) == Data("{\"a\":1}".utf8))
  }

  @Test func bodyIsReadFromHTTPBodyStreamWhenURLSessionUsesIt() {
    var request = URLRequest(url: URL(string: "cappiroh://peer/x")!)
    request.httpBodyStream = InputStream(data: Data("{\"b\":2}".utf8))
    #expect(requestBody(request) == Data("{\"b\":2}".utf8))
  }
}
