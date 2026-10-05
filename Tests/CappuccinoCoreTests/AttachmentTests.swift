import CappuccinoCore
import Foundation
import Testing

@Test func identicalSessionIDsOnDifferentMachinesDoNotAlias() {
  let first = AgentIdentity(machineID: UUID(), sessionID: "existing-session")
  let second = AgentIdentity(machineID: UUID(), sessionID: "existing-session")

  #expect(first != second)
  #expect(Set([first, second]).count == 2)
}

@Test func identitySurvivesLocalPersistence() throws {
  let identity = AgentIdentity(machineID: UUID(), sessionID: "existing-session")
  let data = try JSONEncoder().encode(identity)

  #expect(try JSONDecoder().decode(AgentIdentity.self, from: data) == identity)
}

@Test func messageTimingIsExplicit() {
  #expect(MessageDelivery.allCases == [.nudge, .followUp])
  #expect(MessageDelivery.nudge != .followUp)
}
