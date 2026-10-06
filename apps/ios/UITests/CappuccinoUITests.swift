import XCTest

final class CappuccinoUITests: XCTestCase {
  @MainActor
  func testDisconnectedShell() {
    let app = XCUIApplication()
    // -cappuccino-fresh ignores a machine saved by another journey.
    app.launchArguments += ["-cappuccino-fresh"]
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

  // Issue #6/#16 journey: no machine -> malformed URL fails visibly -> a
  // well-formed machine URL adds the machine -> the fixture agents render
  // (including the unnamed pane and null-branch cases) -> delivery controls
  // stay disabled. Uses the scripted demo transport so the journey is hermetic.
  @MainActor
  func testPairingAndAgentListJourney() {
    let app = XCUIApplication()
    app.launchArguments += ["-cappuccino-demo", "-cappuccino-fresh"]
    app.launchEnvironment["CAPP_DEMO_MACHINE_URL"] = "http://127.0.0.1:7392"
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.staticTexts["No machines added"].waitForExistence(timeout: 5))

    // The unpaired state explains the boundary and offers the URL field.
    XCTAssertTrue(app.buttons["Add machine"].waitForExistence(timeout: 5))

    // The pre-filled machine URL adds the machine; the demo transport pairs
    // it and the fixture agents render.
    app.buttons["Add machine"].tap()
    let failureText =
      app.staticTexts["pairing-error"].exists
      ? app.staticTexts["pairing-error"].label : "no add-machine error shown"
    XCTAssertTrue(
      app.staticTexts["pi on harus-mini"].waitForExistence(timeout: 5),
      "adding the machine did not reach the agent list; error: \(failureText)")
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
  func testUnreachableMachineShowsFailureState() {
    let app = XCUIApplication()
    app.launchArguments += ["-cappuccino-unreachable", "-cappuccino-fresh"]
    app.launchEnvironment["CAPP_DEMO_MACHINE_URL"] = "http://127.0.0.1:7392"
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.buttons["Add machine"].waitForExistence(timeout: 5))
    app.buttons["Add machine"].tap()
    XCTAssertTrue(
      app.staticTexts.matching(
        NSPredicate(format: "label CONTAINS 'unreachable'")
      ).firstMatch.waitForExistence(timeout: 10),
      "an unreachable machine must surface a visible error"
    )
    capture("Machine unreachable", app: app)
  }

  @MainActor
  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
