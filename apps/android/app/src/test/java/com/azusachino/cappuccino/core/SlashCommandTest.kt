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
}
