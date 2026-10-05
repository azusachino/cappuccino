import XCTest

final class CappuccinoUITests: XCTestCase {
  @MainActor
  func testDisconnectedShell() {
    let app = XCUIApplication()
    app.launch()

    XCTAssertTrue(app.staticTexts["No agents attached"].waitForExistence(timeout: 5))
    XCTAssertFalse(app.buttons["Send"].isEnabled)
    XCTAssertTrue(app.buttons["Nudge"].exists)
    XCTAssertTrue(app.buttons["Follow-up"].exists)
    capture("Chats", app: app)

    app.tabBars.buttons["Attention"].tap()
    XCTAssertTrue(app.staticTexts["No pending requests"].waitForExistence(timeout: 5))
    capture("Attention", app: app)

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.staticTexts["No machines added"].waitForExistence(timeout: 5))
    capture("Machines", app: app)
  }

  // Issue #6 journey: unpaired -> wrong token fails visibly -> correct token
  // pairs -> agent list renders the fixture rows (including the unnamed pane
  // and null-branch cases) -> delivery controls stay disabled. Uses the
  // scripted demo daemon so the journey is hermetic.
  @MainActor
  func testPairingAndAgentListJourney() {
    let app = XCUIApplication()
    app.launchArguments += ["-cappuccino-demo"]
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.staticTexts["No machines added"].waitForExistence(timeout: 5))

    // Unpaired state explains the boundary and offers the token field.
    XCTAssertTrue(app.buttons["Pair machine"].waitForExistence(timeout: 5))

    // Wrong token: visible failure, still unpaired.
    let tokenField = app.secureTextFields["pairing-token"]
    tokenField.tap()
    tokenField.typeText("wrong-token")
    app.buttons["Pair machine"].tap()
    XCTAssertTrue(app.staticTexts["Pairing token rejected by the daemon."].waitForExistence(timeout: 5))
    capture("Pairing rejected", app: app)

    // Correct token: paired, fixture agents render.
    tokenField.tap()
    tokenField.typeText("demo-ok")
    app.buttons["Pair machine"].tap()
    let failureText = app.staticTexts["pairing-error"].exists
      ? app.staticTexts["pairing-error"].label : "no pairing error shown"
    XCTAssertTrue(
      app.staticTexts["pi on harus-mini"].waitForExistence(timeout: 5),
      "pairing did not reach the agent list; pairing-error: \(failureText)")
    XCTAssertTrue(app.staticTexts["s-aurora"].exists)
    XCTAssertTrue(app.staticTexts["feat/collector-fix"].exists)
    // Unnamed pane: deterministic fallback identity, honest null branch.
    XCTAssertTrue(app.staticTexts["w1:p43"].exists)
    XCTAssertTrue(app.staticTexts["No branch"].exists)
    capture("Agent list", app: app)

    // Delivery stays unavailable end to end.
    app.tabBars.buttons["Chats"].tap()
    XCTAssertFalse(app.buttons["Send"].isEnabled)
    capture("Chats still disabled", app: app)
  }

  @MainActor
  func testUnreachableDaemonShowsFailureState() {
    let app = XCUIApplication()
    app.launchArguments += ["-cappuccino-unreachable"]
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    let tokenField = app.secureTextFields["pairing-token"]
    XCTAssertTrue(tokenField.waitForExistence(timeout: 5))
    tokenField.tap()
    tokenField.typeText("any-token")
    app.buttons["Pair machine"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(
        NSPredicate(format: "label CONTAINS 'unreachable'")
      ).firstMatch.waitForExistence(timeout: 10),
      "a failed pairing must surface a visible error"
    )
    capture("Daemon unreachable", app: app)
  }

  @MainActor
  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
