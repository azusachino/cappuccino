import Testing

@testable import CappuccinoCore

struct SlashCommandTests {
  @Test func commandsAreToldApartFromTextAndPaths() {
    #expect(slashCommand("/model opus") == "/model")
    #expect(slashCommand("  /new") == "/new")
    #expect(slashCommand("/mcp:tool-name x") == "/mcp:tool-name")
    #expect(slashCommand("/Users/me/notes.md summarize") == nil)
    #expect(slashCommand("/ not a command") == nil)
    #expect(slashCommand("/1 two") == nil)
    #expect(slashCommand("please run /model") == nil)
  }

  @Test func costlyCommandsSayWhatTheyCost() {
    #expect(slashCommandWarning("/model").contains("prompt cache"))
    #expect(slashCommandWarning("/Clear").contains("fresh conversation"))
    #expect(slashCommandWarning("/foo").hasPrefix("/foo is an agent command"))
  }
}
