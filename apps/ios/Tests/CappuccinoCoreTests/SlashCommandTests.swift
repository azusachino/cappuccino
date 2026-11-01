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

  @Test func listedCommandsMatchAndLeaveRefusedOnesOut() {
    #expect(commonSlashCommands.map(\.command) == ["/new", "/compact", "/model", "/clear"])
    #expect(commonSlashCommands.allSatisfy { disallowedSlashCommandReason($0.command) == nil })
    #expect(matchingSlashCommands(" /c").map(\.command) == ["/compact", "/clear"])
    #expect(matchingSlashCommands("/model opus").map(\.command) == ["/model"])
    #expect(matchingSlashCommands("/status").isEmpty)
    #expect(matchingSlashCommands("hello /new").isEmpty)
  }

  @Test func recentPromptsAreNewestFirstWithoutRepeats() {
    var recent: [String] = []
    recent = rememberingPrompt("one", in: recent)
    recent = rememberingPrompt(" two ", in: recent)
    recent = rememberingPrompt("one", in: recent)
    recent = rememberingPrompt("   ", in: recent)
    #expect(recent == ["one", "two"])
    let many = (1...25).reduce(into: [String]()) { $0 = rememberingPrompt("p\($1)", in: $0) }
    #expect(many.count == 20)
    #expect(many.first == "p25")
  }
}
