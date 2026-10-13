import XCTest

/// Hermetic journeys over the real shell. A fresh app has no saved machines.
final class CappuccinoUITests: XCTestCase {
  @MainActor
  func testFreshAppShowsNoMachinesAndAddMachineSheet() {
    let app = XCUIApplication()
    app.launch()

    XCTAssertTrue(app.staticTexts["No machines configured"].waitForExistence(timeout: 5))
    capture("Machines", app: app)

    app.buttons["Add machine"].tap()
    XCTAssertTrue(app.textFields["Bridge address"].waitForExistence(timeout: 5))
    XCTAssertTrue(app.buttons["Connect"].exists)
    capture("Add machine", app: app)
  }

  @MainActor
  func testSettingsShowsTheThemeChoice() {
    let app = XCUIApplication()
    app.launch()

    app.tabBars.buttons["Settings"].tap()
    XCTAssertTrue(
      app.staticTexts["Theme"].waitForExistence(timeout: 5) || app.otherElements["Theme"].exists)
    capture("Settings", app: app)
  }

  private func capture(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}
