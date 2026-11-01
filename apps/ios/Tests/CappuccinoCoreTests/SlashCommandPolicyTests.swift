import Testing

@testable import CappuccinoCore

@Suite struct SlashCommandPolicyTests {
  @Test func exitAndQuitAreDisallowedOnAnyCase() {
    #expect(disallowedSlashCommandReason("/exit") != nil)
    #expect(disallowedSlashCommandReason("/QUIT") != nil)
  }

  @Test func statusIsDisallowedBecausePhonesLackEsc() {
    #expect(disallowedSlashCommandReason("/status")?.contains("Esc") == true)
  }

  @Test func ordinaryCommandsAreAllowed() {
    #expect(disallowedSlashCommandReason("/model") == nil)
    #expect(disallowedSlashCommandReason("/new") == nil)
  }

  @Test func newWaitsWhileTheAgentIsWorkingOnly() {
    #expect(busyAgentCommandReason("/new", agentWorking: true) != nil)
    #expect(busyAgentCommandReason("/new", agentWorking: false) == nil)
    #expect(busyAgentCommandReason("/model", agentWorking: true) == nil)
  }
}
