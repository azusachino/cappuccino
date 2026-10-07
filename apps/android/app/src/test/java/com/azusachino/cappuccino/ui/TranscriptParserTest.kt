package com.azusachino.cappuccino.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptParserTest {
  @Test
  fun parsesUserPromptEcho() {
    val msg = TranscriptParser.parseLine("> please write a test")
    assertEquals(TranscriptRole.USER, msg.role)
    assertEquals("please write a test", msg.text)

    val prompt = TranscriptParser.parseLine("❯ another prompt")
    assertEquals(TranscriptRole.USER, prompt.role)
    assertEquals("another prompt", prompt.text)
  }

  @Test
  fun parsesToolCall() {
    val msg = TranscriptParser.parseLine("● Read(services/bridge/src/main.rs)")
    assertEquals(TranscriptRole.TOOL, msg.role)
    assertEquals("Read", msg.toolName)
    assertEquals("services/bridge/src/main.rs", msg.toolArg)

    val bash = TranscriptParser.parseLine("○ Bash(cargo test)")
    assertEquals(TranscriptRole.TOOL, bash.role)
    assertEquals("Bash", bash.toolName)
    assertEquals("cargo test", bash.toolArg)
  }

  @Test
  fun parsesStatusChrome() {
    val msg = TranscriptParser.parseLine("working · Gemini 3.8 Flash · 12k tokens")
    assertEquals(TranscriptRole.STATUS, msg.role)

    val running = TranscriptParser.parseLine("⣾ Running command...")
    assertEquals(TranscriptRole.STATUS, running.role)
  }

  @Test
  fun parsesFullTranscript() {
    val input =
      """
      ────────────────────────────────────
      > list the files
      ● Bash(ls -la)
      total 24
      drwxr-xr-x 2 user staff 64
      working · Gemini 3.8
      Here are the files you requested.
      """
        .trimIndent()

    val parsed = TranscriptParser.parse(input)
    val roles = parsed.map { it.role }
    assertEquals(
      listOf(
        TranscriptRole.USER,
        TranscriptRole.TOOL,
        TranscriptRole.AGENT,
        TranscriptRole.STATUS,
        TranscriptRole.AGENT,
      ),
      roles,
    )
  }
}
