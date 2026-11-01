import Testing

@testable import CappuccinoCore

@Suite struct StreamIdleTrackerTests {
  @Test func theEndpointIsIdleOnlyAfterEveryStreamHasClosed() {
    var tracker = StreamIdleTracker()
    #expect(tracker.isIdle)
    tracker.opened()
    tracker.opened()
    let firstClose = tracker.released()
    #expect(firstClose == false, "one stream is still open")
    #expect(tracker.isIdle == false)
    let lastClose = tracker.released()
    #expect(lastClose == true, "the last close starts the idle period")
    #expect(tracker.isIdle)
  }

  @Test func aNewStreamCancelsTheIdlePeriodByCountingAgain() {
    var tracker = StreamIdleTracker()
    tracker.opened()
    let close = tracker.released()
    #expect(close == true)
    tracker.opened()
    #expect(tracker.isIdle == false)
  }

  @Test func aStrayReleaseNeverGoesNegative() {
    var tracker = StreamIdleTracker()
    let close = tracker.released()
    #expect(close == true)
    #expect(tracker.open == 0)
  }
}
