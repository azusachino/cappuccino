package com.azusachino.cappuccino.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandTest {
  @Test
  fun commandsAreToldApartFromTextAndPaths() {
    assertEquals("/model", slashCommand("/model opus"))
    assertEquals("/new", slashCommand("  /new"))
    assertEquals("/mcp:tool-name", slashCommand("/mcp:tool-name x"))
    assertNull(slashCommand("/Users/me/notes.md summarize"))
    assertNull(slashCommand("/ not a command"))
    assertNull(slashCommand("/1 two"))
    assertNull(slashCommand("please run /model"))
  }

  @Test
  fun costlyCommandsSayWhatTheyCost() {
    assertTrue(slashCommandWarning("/model").contains("prompt cache"))
    assertTrue(slashCommandWarning("/Clear").contains("fresh conversation"))
    assertTrue(slashCommandWarning("/foo").startsWith("/foo is an agent command"))
  }

  @Test
  fun matchingCommandsFilterPrefixAccurately() {
    val all = matchingSlashCommands("/")
    assertEquals(COMMON_SLASH_COMMANDS.size, all.size)
    val comp = matchingSlashCommands("/co")
    assertEquals(1, comp.size)
    assertEquals("/compact", comp[0].command)
    val empty = matchingSlashCommands("/nonexistent")
    assertTrue(empty.isEmpty())
    val notSlash = matchingSlashCommands("hello")
    assertTrue(notSlash.isEmpty())
  }
}
