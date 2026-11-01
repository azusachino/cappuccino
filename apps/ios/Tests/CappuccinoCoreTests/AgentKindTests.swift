import Testing

@testable import CappuccinoCore

struct AgentKindTests {
  @Test func providersMapToTheirKind() {
    #expect(AgentKind(provider: "pi") == .pi)
    #expect(AgentKind(provider: "Claude") == .claude)
    #expect(AgentKind(provider: "agy") == .agy)
    #expect(AgentKind(provider: "antigravity-cli") == .agy)
    #expect(AgentKind(provider: "codex") == .other)
    #expect(AgentKind(provider: nil) == .other)
  }
}
