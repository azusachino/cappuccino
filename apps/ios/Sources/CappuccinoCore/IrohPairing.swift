import Foundation

/// Ports io/IrohPairing.kt's payload parser. The bridge serves its iroh endpoint on the bridge
/// ALPN and the one-time pairing exchange on the pair ALPN. Redeeming the token needs the iroh
/// transport, so it is not here.

public let irohBridgeALPN = "cappuccino/1"
public let irohPairALPN = "cappuccino-pair/1"

/// Where the bridge endpoint is: its id, its relay (nil until it has a home relay) and direct
/// addresses.
public struct IrohEndpointAddr: Equatable, Sendable {
  public let endpointID: String
  public let relayURL: String?
  public let directAddrs: [String]
}

/// A parsed pairing payload v1: the endpoint, the token to present, and an optional tailnet URL
/// for fallback.
public struct IrohPairing: Equatable, Sendable {
  public let endpoint: IrohEndpointAddr
  public let token: String
  public let tailnetURL: String?
}

public enum IrohPairingError: Error, Equatable, Sendable {
  case invalidPayload(String)
}

/// Parses `{"v":1,"endpoint_id","relay","addrs","token"}`. Any other version is rejected so an old
/// app never guesses at a newer payload's meaning. `tailnet_url` is read when present.
public func parseIrohPairing(_ text: String) throws -> IrohPairing {
  guard
    let object = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
  else { throw IrohPairingError.invalidPayload("Pairing payload is not a JSON object") }
  guard let version = object["v"] as? Int, version == 1 else {
    throw IrohPairingError.invalidPayload("Unsupported pairing version")
  }
  guard
    let endpointID = object["endpoint_id"] as? String, let token = object["token"] as? String,
    !endpointID.isEmpty, !token.isEmpty
  else { throw IrohPairingError.invalidPayload("Empty pairing field") }
  guard let addrs = object["addrs"] as? [Any] else {
    throw IrohPairingError.invalidPayload("Invalid addrs")
  }
  let directAddrs = try addrs.map { addr in
    guard let text = addr as? String else {
      throw IrohPairingError.invalidPayload("Invalid pairing addr")
    }
    return text
  }
  let tailnetURL = (object["tailnet_url"] as? String).flatMap { $0.isEmpty ? nil : $0 }
  return IrohPairing(
    endpoint: IrohEndpointAddr(
      endpointID: endpointID, relayURL: try relayURL(object), directAddrs: directAddrs),
    token: token, tailnetURL: tailnetURL)
}

/// The bridge sends `null` until its endpoint has a home relay. The key itself is required.
private func relayURL(_ object: [String: Any]) throws -> String? {
  guard object.keys.contains("relay") else {
    throw IrohPairingError.invalidPayload("Invalid relay")
  }
  if object["relay"] is NSNull { return nil }
  guard let relay = object["relay"] as? String else {
    throw IrohPairingError.invalidPayload("Invalid relay")
  }
  guard !relay.isEmpty else { throw IrohPairingError.invalidPayload("Empty pairing relay") }
  return relay
}
