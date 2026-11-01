import Foundation

/// The stream operations the pairing exchange and the HTTP client need. `IrohStream` is the
/// production conformance; tests script their own.
public protocol IrohStreaming: Sendable {
  func write(_ bytes: Data) async throws
  func finish() async throws
  func read(max: UInt32) async throws -> Data
  func stopReading() async throws
  /// Marks the stream done for the endpoint's idle count. Test streams need not override it.
  func release() async
}

extension IrohStreaming {
  public func release() async {}
}

extension IrohStream: IrohStreaming {}

/// The bridge's reply to a redeemed token. Anything but `OK` fails, and the token never appears
/// in an error.
let pairingReplyLimit = 16

public enum IrohPairingRedeemError: Error, Equatable, Sendable {
  case rejected
  case unexpectedReply
  case replyTooLong
}

/// Presents the one-time token on the pair ALPN and returns once the bridge replies `OK\n`.
/// Opening the stream goes through `openStream`, so the caller supplies the endpoint.
public func redeemIrohPairing(
  _ pairing: IrohPairing,
  openStream: @Sendable (IrohEndpointAddr, String) async throws -> any IrohStreaming
) async throws {
  let stream = try await openStream(pairing.endpoint, irohPairALPN)
  try await stream.write(Data(pairing.token.utf8))
  try await stream.finish()
  let reply = try await readReply(stream)
  try? await stream.stopReading()
  switch String(data: reply, encoding: .utf8) {
  case "OK\n": return
  case "ERR\n": throw IrohPairingRedeemError.rejected
  default: throw IrohPairingRedeemError.unexpectedReply
  }
}

private func readReply(_ stream: any IrohStreaming) async throws -> Data {
  var reply = Data()
  while true {
    let chunk = try await stream.read(max: UInt32(pairingReplyLimit))
    if chunk.isEmpty { return reply }
    reply.append(chunk)
    if reply.count > pairingReplyLimit { throw IrohPairingRedeemError.replyTooLong }
  }
}

/// Pairs an iroh machine from its payload: parse it, redeem the one-time token, then verify the
/// machine over iroh. The returned profile is what the app saves. The token is not kept.
public func pairIrohMachine(
  payload: String, name: String, verifier: any BridgeMachineServing = BridgeClient()
) async throws -> BridgeProfile {
  let pairing = try parseIrohPairing(payload)
  try await redeemIrohPairing(pairing) { addr, alpn in
    try await IrohEndpointService.shared.openStream(to: addr, alpn: alpn)
  }
  IrohHub.shared.register(pairing.endpoint)
  let endpoint = URL(string: "\(irohURLScheme)://\(pairing.endpoint.endpointID)")!
  let verified = try await verifier.verifyMachine(baseURL: endpoint, expectedID: nil)
  // A blank label takes the machine's own name, not its endpoint id.
  let label = name.trimmingCharacters(in: .whitespacesAndNewlines)
  return try BridgeProfile(
    machineID: verified.machine.id, endpoint: endpoint,
    name: label.isEmpty ? (verified.name ?? "Mac") : label, iroh: pairing.endpoint,
    tailnetFallback: pairing.tailnetURL.flatMap(URL.init(string:)))
}
