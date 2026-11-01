import Testing

@testable import CappuccinoCore

@Suite struct IrohPairingTests {
  private let valid = """
    {"v":1,"endpoint_id":"abc123","relay":"https://relay.example/","addrs":["10.0.0.2:4433"],\
    "token":"one-time-token","tailnet_url":"http://mini.tailnet.example:7392"}
    """

  @Test func parsesEveryFieldOfAValidPayload() throws {
    let pairing = try parseIrohPairing(valid)
    #expect(pairing.endpoint.endpointID == "abc123")
    #expect(pairing.endpoint.relayURL == "https://relay.example/")
    #expect(pairing.endpoint.directAddrs == ["10.0.0.2:4433"])
    #expect(pairing.token == "one-time-token")
    #expect(pairing.tailnetURL == "http://mini.tailnet.example:7392")
  }

  @Test func aNullRelayAndAMissingTailnetAreAccepted() throws {
    let text = #"{"v":1,"endpoint_id":"abc","relay":null,"addrs":[],"token":"t"}"#
    let pairing = try parseIrohPairing(text)
    #expect(pairing.endpoint.relayURL == nil)
    #expect(pairing.tailnetURL == nil)
  }

  @Test func rejectsAnotherVersionSoAnOldAppNeverGuesses() {
    let text = #"{"v":2,"endpoint_id":"abc","relay":null,"addrs":[],"token":"t"}"#
    #expect(throws: IrohPairingError.invalidPayload("Unsupported pairing version")) {
      try parseIrohPairing(text)
    }
    #expect(throws: IrohPairingError.self) {
      try parseIrohPairing(#"{"v":"1","endpoint_id":"abc","relay":null,"addrs":[],"token":"t"}"#)
    }
  }

  @Test func rejectsAMissingRelayKeyEvenWhenTheValueWouldBeNull() {
    let text = #"{"v":1,"endpoint_id":"abc","addrs":[],"token":"t"}"#
    #expect(throws: IrohPairingError.self) { try parseIrohPairing(text) }
  }

  @Test func rejectsEmptyIdentityFieldsAndNonTextAddrs() {
    #expect(throws: IrohPairingError.self) {
      try parseIrohPairing(#"{"v":1,"endpoint_id":"","relay":null,"addrs":[],"token":"t"}"#)
    }
    #expect(throws: IrohPairingError.self) {
      try parseIrohPairing(#"{"v":1,"endpoint_id":"a","relay":null,"addrs":[],"token":""}"#)
    }
    #expect(throws: IrohPairingError.self) {
      try parseIrohPairing(#"{"v":1,"endpoint_id":"a","relay":null,"addrs":[7],"token":"t"}"#)
    }
  }

  @Test func rejectsNonJSONAndNonObjectPayloads() {
    #expect(throws: IrohPairingError.self) { try parseIrohPairing("not json") }
    #expect(throws: IrohPairingError.self) { try parseIrohPairing("[1,2]") }
  }

  @Test func aNonStringTailnetURLIsIgnoredAndAnEmptyOneIsNil() throws {
    let number = #"{"v":1,"endpoint_id":"a","relay":null,"addrs":[],"token":"t","tailnet_url":3}"#
    #expect(try parseIrohPairing(number).tailnetURL == nil)
    let empty = #"{"v":1,"endpoint_id":"a","relay":null,"addrs":[],"token":"t","tailnet_url":""}"#
    #expect(try parseIrohPairing(empty).tailnetURL == nil)
  }
}
