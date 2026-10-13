import XCTest

/// Journeys over the real shell. `-cappuccino-fresh` starts without saved machines;
/// `-cappuccino-fixture` runs the canned bridge. The live journey only reads from the bridge.
final class CappuccinoUITests: XCTestCase {
  override func setUp() {
    continueAfterFailure = false
  }

  @MainActor
  func testFreshAppShowsNoMachinesAndAddMachineSheet() {
    let app = XCUIApplication()
    app.launchArguments = ["-cappuccino-fresh"]
    app.launch()

    XCTAssertTrue(app.staticTexts["No machines configured"].waitForExistence(timeout: 10))
    capture("Machines", app: app)

    app.buttons["Add machine"].tap()
    XCTAssertTrue(app.textFields["Bridge address"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["Connect"].exists)
    capture("Add machine", app: app)
  }

  @MainActor
  func testSettingsShowsTheThemeChoice() {
    let app = XCUIApplication()
    app.launchArguments = ["-cappuccino-fresh"]
    app.launch()

    app.tabBars.buttons["Settings"].tap()
    XCTAssertTrue(app.staticTexts["Theme"].waitForExistence(timeout: 5))
    capture("Settings", app: app)
  }

  @MainActor
  func testFixtureConversationShowsTheReplyAndIdleControls() {
    let app = fixtureApp()
    openFixtureAgent(app)

    XCTAssertTrue(app.staticTexts["The build passed."].waitForExistence(timeout: 10))
    XCTAssertTrue(app.buttons["Commands"].exists, "agent control is advertised")
    XCTAssertFalse(app.buttons["Stop"].exists, "Stop is offered only while working")
    capture("Conversation", app: app)
  }

  @MainActor
  func testNewSessionAsksBeforeStarting() {
    let app = fixtureApp()
    openFixtureAgent(app)

    app.buttons["Commands"].tap()
    app.buttons["New session"].tap()
    XCTAssertTrue(app.staticTexts["Start a new session?"].waitForExistence(timeout: 5))
    capture("New session confirmation", app: app)
  }

  /// Read-only against the Mac bridge: add it, see its agents, open one and read its status.
  /// Opt-in, since it needs a bridge on this host:
  /// `TEST_RUNNER_CAPPUCCINO_LIVE_BRIDGE=1 make ui-test DESTINATION=...`. It captures nothing:
  /// a real conversation never goes into test results or evidence.
  @MainActor
  func testLiveBridgeListsAgentsWithoutSendingAnything() throws {
    try XCTSkipUnless(
      ProcessInfo.processInfo.environment["CAPPUCCINO_LIVE_BRIDGE"] == "1",
      "live bridge journey is opt-in")
    let app = XCUIApplication()
    app.launchArguments = ["-cappuccino-fresh"]
    app.launch()

    app.buttons["Add machine"].tap()
    let address = app.textFields["Bridge address"]
    XCTAssertTrue(address.waitForExistence(timeout: 5))
    address.tap()
    address.typeText("http://127.0.0.1:7392")
    app.buttons["Connect"].tap()

    XCTAssertTrue(
      app.staticTexts["Connected"].waitForExistence(timeout: 20), "bridge did not connect")
    let agent = app.buttons.containing(
      NSPredicate(format: "label CONTAINS 'Idle' OR label CONTAINS 'Working'")
    ).firstMatch
    XCTAssertTrue(agent.waitForExistence(timeout: 10), "no agent listed by the live bridge")
    agent.tap()
    let status = app.staticTexts.containing(
      NSPredicate(format: "label CONTAINS 'Idle' OR label CONTAINS 'Working'")
    ).firstMatch
    XCTAssertTrue(status.waitForExistence(timeout: 10), "the conversation shows its status word")
    // The live stream opens: no stream error replaces it within a few polls.
    let streamError = app.staticTexts.containing(
      NSPredicate(format: "label CONTAINS 'stream URL' OR label CONTAINS 'Bridge protocol error'")
    ).firstMatch
    XCTAssertFalse(
      streamError.waitForExistence(timeout: 6),
      "the live stream failed to open: \(streamError.exists ? streamError.label : "")")
  }

  @MainActor
  private func fixtureApp() -> XCUIApplication {
    let app = XCUIApplication()
    app.launchArguments = ["-cappuccino-fresh", "-cappuccino-fixture"]
    app.launch()
    return app
  }

  @MainActor
  private func openFixtureAgent(_ app: XCUIApplication) {
    let agent = app.buttons.containing(NSPredicate(format: "label CONTAINS 'pi on fixture'"))
      .firstMatch
    XCTAssertTrue(agent.waitForExistence(timeout: 10))
    agent.tap()
  }

  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
