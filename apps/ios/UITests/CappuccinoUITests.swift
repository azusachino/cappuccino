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

  @MainActor
  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
