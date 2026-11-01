import Foundation
import Testing

@testable import CappuccinoCore

@MainActor struct ConnectionLogTests {
  @Test func keepsNewestEntriesWithinCapacity() {
    let log = ConnectionLog()
    for index in 1...(ConnectionLog.capacity + 5) {
      log.log("MACHINE", "event \(index)")
    }
    #expect(log.entries.count == ConnectionLog.capacity)
    #expect(log.entries.first?.message == "event 6")
    #expect(log.entries.last?.message == "event \(ConnectionLog.capacity + 5)")
  }

  @Test func idsIncreaseAcrossEvictionAndClearKeepsCounting() {
    let log = ConnectionLog()
    log.log("A", "one")
    log.log("A", "two", level: .warn)
    #expect(log.entries.map(\.id) == [1, 2])
    #expect(log.entries.last?.level == .warn)
    log.clear()
    #expect(log.entries.isEmpty)
    log.log("A", "three", level: .error)
    #expect(log.entries.map(\.id) == [3])
    #expect(log.entries.first?.level == .error)
  }

  @Test func stampsEntriesWithTheInjectedClock() {
    let fixed = Date(timeIntervalSince1970: 1_000)
    let log = ConnectionLog(now: { fixed })
    log.log("NETWORK", "offline")
    #expect(log.entries.first?.timestamp == fixed)
    #expect(log.entries.first?.category == "NETWORK")
  }
}
