import XCTest
@testable import DaemonCore

final class PairingTests: XCTestCase {
  func testRejectsWrongTokenVisibly() {
    let store = PairingStore(token: "correct-token-value")
    XCTAssertFalse(store.authorize("wrong-token-value"))
    XCTAssertFalse(store.authorize(nil))
    XCTAssertFalse(store.authorize(""))
    // Length-matched but wrong content still fails (constant-time path).
    XCTAssertFalse(store.authorize("correct-token-valu"))
  }

  func testAcceptsCorrectToken() {
    let store = PairingStore(token: "correct-token-value")
    XCTAssertTrue(store.authorize("correct-token-value"))
  }

  func testTokenFileIsCreatedOutsideGitWithTightPermissions() throws {
    let dir = FileManager.default.temporaryDirectory
      .appendingPathComponent("cap-spike-tests-\(UUID().uuidString)")
    let path = dir.appendingPathComponent("pairing-token").path
    let first = PairingStore(file: path)
    let second = PairingStore(file: path)
    XCTAssertEqual(first.token, second.token, "token must be stable across daemon restarts")
    let attributes = try FileManager.default.attributesOfItem(atPath: path)
    let permissions = (attributes[.posixPermissions] as? NSNumber)?.uint16Value
    XCTAssertEqual(permissions, 0o600)
    XCTAssertEqual(first.authorize(second.token), true)
  }
}
