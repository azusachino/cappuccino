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

  // Issue #7 journey: Machines -> open an agent -> transcript renders the
  // fixture rows (chat bubbles, code block, expandable tool rows) -> live
  // append arrives exactly once -> visible gap placeholder -> session reset
  // clears and reloads -> back stops the stream. Scripted demo transport.
  @MainActor
  func testTranscriptLiveJourney() {
    let app = XCUIApplication()
    app.launchArguments += ["-cappuccino-demo", "-cappuccino-fresh"]
    app.launchEnvironment["CAPP_DEMO_MACHINE_URL"] = "http://127.0.0.1:7392"
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.buttons["Add machine"].waitForExistence(timeout: 5))
    app.buttons["Add machine"].tap()
    XCTAssertTrue(app.staticTexts["pi on harus-mini"].waitForExistence(timeout: 5))

    // Open the agent's transcript.
    // The NavigationLink row renders as a button-like cell; tap the row by
    // its heading text (first match — the identifier propagates to the cell).
    app.staticTexts["pi on harus-mini"].firstMatch.tap()
    sleep(2)
    XCTAssertTrue(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'Fix the collector path and rerun lint.'"))
        .firstMatch.waitForExistence(timeout: 5),
      "the durable user entry must render")
    XCTAssertTrue(
      app.descendants(matching: .any)["transcript-branch"].exists, "active branch is shown")
    capture("Transcript durable entries", app: app)

    // Live append arrives exactly once (duplicate delivery suppressed).
    XCTAssertTrue(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'LIVE-APPEND-ROW'"))
        .firstMatch.waitForExistence(timeout: 10)
    )
    XCTAssertEqual(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'LIVE-APPEND-ROW'")).count, 1)
    capture("Transcript live append", app: app)

    // Seq jump: a visible gap placeholder precedes the post-gap entry.
    XCTAssertTrue(app.descendants(matching: .any)["gap-row"].waitForExistence(timeout: 10))
    XCTAssertTrue(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'POST-GAP-ROW'"))
        .firstMatch.waitForExistence(timeout: 5)
    )
    capture("Transcript gap placeholder", app: app)

    // Session replacement: reset clears and reloads — earlier history is
    // gone, only the fresh session's rows remain.
    print("DBG-DUMP:", app.debugDescription)
    let resetMatched = app.descendants(matching: .any)
      .matching(NSPredicate(format: "label CONTAINS 'RESET-ROW'"))
      .firstMatch.waitForExistence(timeout: 10)
    if !resetMatched {
      print("DBG-RESET-ABSENT:", app.debugDescription)
    }
    XCTAssertTrue(resetMatched)
    XCTAssertFalse(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'Fix the collector path'")).firstMatch.exists,
      "reset must clear the previous session's history")
    capture("Transcript after reset", app: app)

    // Back navigation tears the stream down deterministically.
    app.navigationBars.buttons.firstMatch.tap()
    XCTAssertTrue(
      app.buttons["Add machine"].waitForExistence(timeout: 5)
        || app.staticTexts["pi on harus-mini"].waitForExistence(timeout: 5))
    capture("Back on Machines", app: app)
  }

  // Long-history smoke: 1k generated entries render and scroll (virtualized).
  @MainActor
  func testTranscriptLongHistoryScrolls() {
    let app = XCUIApplication()
    app.launchArguments += [
      "-cappuccino-demo", "-cappuccino-fresh", "-cappuccino-demo-transcript-long",
    ]
    app.launchEnvironment["CAPP_DEMO_MACHINE_URL"] = "http://127.0.0.1:7392"
    app.launch()

    app.tabBars.buttons["Machines"].tap()
    XCTAssertTrue(app.buttons["Add machine"].waitForExistence(timeout: 5))
    app.buttons["Add machine"].tap()
    XCTAssertTrue(app.staticTexts["pi on harus-mini"].waitForExistence(timeout: 5))
    // The NavigationLink row renders as a button-like cell; tap the row by
    // its heading text (first match — the identifier propagates to the cell).
    app.staticTexts["pi on harus-mini"].firstMatch.tap()

    // Bulk entries from the long fixture render.
    XCTAssertTrue(
      app.descendants(matching: .any)
        .matching(NSPredicate(format: "label CONTAINS 'Continue with step 1.'"))
        .firstMatch.waitForExistence(timeout: 10)
    )
    for _ in 1...12 {
      app.swipeUp(velocity: .fast)
    }
    // Virtualization keeps rendering deep rows while scrolling fast.
    XCTAssertTrue(
      app.staticTexts.matching(NSPredicate(format: "label CONTAINS 'step'")).firstMatch
        .waitForExistence(timeout: 5)
    )
    capture("Transcript long history", app: app)
  }

  @MainActor
  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
