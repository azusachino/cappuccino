import Foundation

/// Carries bridge HTTP requests over iroh. A request to `cappiroh://<endpoint id>/<path>` is sent
/// as HTTP/1.1 on a `cappuccino/1` stream to that endpoint. `BridgeClient` runs unchanged on
/// `BridgeSession.shared`, which adds this protocol. Every other scheme takes the normal network.

public let irohURLScheme = "cappiroh"

/// Dial details for each paired iroh peer, keyed by endpoint id. Profiles register theirs when
/// they load, so a request can find its peer without the caller passing an address.
public final class IrohHub: @unchecked Sendable {
  public typealias Opener = @Sendable (IrohEndpointAddr, String) async throws -> any IrohStreaming

  public static let shared = IrohHub {
    try await IrohEndpointService.shared.openStream(to: $0, alpn: $1)
  }

  /// Largest response accepted from a peer. The bridge's JSON replies are far below this.
  static let maxResponseBytes = 16 * 1024 * 1024

  private let lock = NSLock()
  private var peers: [String: IrohEndpointAddr] = [:]
  private var opener: Opener

  public init(opener: @escaping Opener) {
    self.opener = opener
  }

  public func register(_ addr: IrohEndpointAddr) {
    lock.withLock { peers[addr.endpointID] = addr }
  }

  /// Replaces how streams are opened. Tests use this; the app keeps the default.
  public func setOpener(_ opener: @escaping Opener) {
    lock.withLock { self.opener = opener }
  }

  /// One request over a fresh stream. The stream is closed by the peer after the response.
  func fetch(_ request: URLRequest) async throws -> HTTPWireResponse {
    guard let url = request.url, let host = url.host else { throw URLError(.badURL) }
    let (addr, open) = try lock.withLock {
      guard let addr = peers[host] else { throw URLError(.cannotFindHost) }
      return (addr, opener)
    }
    let stream = try await open(addr, irohBridgeALPN)
    var target = url.path.isEmpty ? "/" : url.path
    if let query = url.query { target += "?\(query)" }
    let wire = encodeHTTPRequest(
      method: request.httpMethod ?? "GET", target: target, host: host,
      headers: request.allHTTPHeaderFields ?? [:], body: request.httpBody ?? Data())
    try await stream.write(wire)
    try await stream.finish()
    var reply = Data()
    while true {
      let chunk = try await stream.read(max: 64 * 1024)
      if chunk.isEmpty { break }
      reply.append(chunk)
      if reply.count > Self.maxResponseBytes { throw URLError(.dataLengthExceedsMaximum) }
    }
    do {
      return try parseHTTPResponse(reply)
    } catch {
      throw URLError(.badServerResponse)
    }
  }
}

/// The URL loading protocol for `cappiroh` URLs. It finishes each request on the hub's stream.
final class IrohURLProtocol: URLProtocol, @unchecked Sendable {
  private var loading: Task<Void, Never>?

  override class func canInit(with request: URLRequest) -> Bool {
    request.url?.scheme?.lowercased() == irohURLScheme
  }

  override class func canonicalRequest(for request: URLRequest) -> URLRequest {
    request
  }

  override func startLoading() {
    let request = self.request
    let finish = FinishLoading(proto: self)
    loading = Task {
      do {
        let wire = try await IrohHub.shared.fetch(request)
        guard
          let url = request.url,
          let response = HTTPURLResponse(
            url: url, statusCode: wire.status, httpVersion: "HTTP/1.1",
            headerFields: wire.headers)
        else { throw URLError(.badServerResponse) }
        finish.run { proto in
          proto.client?.urlProtocol(proto, didReceive: response, cacheStoragePolicy: .notAllowed)
          proto.client?.urlProtocol(proto, didLoad: wire.body)
          proto.client?.urlProtocolDidFinishLoading(proto)
        }
      } catch {
        finish.run { proto in proto.client?.urlProtocol(proto, didFailWithError: error) }
      }
    }
  }

  override func stopLoading() {
    loading?.cancel()
  }
}

/// Carries the protocol into the loading task. URLProtocol is not Sendable, and its client is only
/// called from the task that owns this request.
private struct FinishLoading: @unchecked Sendable {
  let proto: URLProtocol

  func run(_ body: @escaping (URLProtocol) -> Void) {
    body(proto)
  }
}

/// The session every bridge client uses: the iroh protocol first, then normal networking.
public enum BridgeSession {
  public static let shared: URLSession = {
    let configuration = URLSessionConfiguration.default
    configuration.protocolClasses = [IrohURLProtocol.self] + (configuration.protocolClasses ?? [])
    return URLSession(configuration: configuration)
  }()
}
