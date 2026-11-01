import Foundation
import Testing

@testable import CappuccinoCore

@Suite struct IrohEndpointTests {
  private let addr = IrohEndpointAddr(
    endpointID: "not-an-endpoint-id", relayURL: nil, directAddrs: [])

  @Test func aMalformedEndpointIDIsRejectedBeforeAnyDial() {
    #expect(throws: IrohEndpointError.invalidEndpointID("not-an-endpoint-id")) {
      try IrohEndpointService.binding(addr)
    }
  }

  @Test func aSecretKeyOfTheWrongSizeIsRejectedBeforeBinding() async {
    let service = IrohEndpointService(loadSecretKey: { Data(repeating: 1, count: 31) })
    await #expect(throws: IrohEndpointError.invalidSecretKey) {
      _ = try await service.openStream(to: addr, alpn: irohBridgeALPN)
    }
  }
}
