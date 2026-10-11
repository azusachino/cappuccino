package com.azusachino.cappuccino.ui

import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxLanguage
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContentTest {
  @Test
  fun jsonIsPrettyPrintedWithoutChangingItsStructure() {
    val source = """{"command":"echo hello","nested":[true,null,3]}"""
    val content = formatToolContent(source)
    assertEquals("json", content.language)
    assertTrue(content.text.contains("\n"))
    assertEquals(Json.parseToJsonElement(source), Json.parseToJsonElement(content.text))
  }

  @Test
  fun existingJavascriptLexerProducesHighlightSpansForJsonLiterals() {
    val content = formatToolContent("""{"command":"echo hello","ready":true,"timeout":10}""")
    val spans =
      Highlights.Builder()
        .language(SyntaxLanguage.JAVASCRIPT)
        .code(content.text)
        .build()
        .getHighlights()
    assertTrue(spans.isNotEmpty())
    assertTrue(spans.all { it.location.start >= 0 && it.location.end <= content.text.length })
  }

  @Test
  fun toolSummaryShowsTheRealCommandOrPathWithoutCuttingItOff() {
    val command = "printf hello\n" + "argument ".repeat(60)
    val json = "{\"command\":" + kotlinx.serialization.json.JsonPrimitive(command).toString() + "}"
    assertEquals(command, toolSummary(json))
    assertEquals("src/main.kt", toolSummary("""{"path":"src/main.kt","offset":20}"""))
    assertEquals("echo plain", toolSummary("echo plain"))
    assertEquals("{broken", toolSummary("{broken"))
  }

  @Test
  fun remainingArgumentsDoNotRepeatTheCommandOrDropOtherFields() {
    val input = """{"command":"echo hello","timeout":10,"nested":{"flag":true}}"""
    val remaining =
      Json.parseToJsonElement(toolOtherArguments(input)!!) as kotlinx.serialization.json.JsonObject
    assertFalse(remaining.containsKey("command"))
    assertTrue(remaining.containsKey("timeout"))
    assertTrue(remaining.containsKey("nested"))
    assertEquals(null, toolOtherArguments("""{"command":"echo hello"}"""))
  }

  @Test
  fun failedResultPreviewIncludesTheActualEndOfLongOutput() {
    val output = "started\n" + "log line\n".repeat(100) + "fatal: final failure"
    val preview = toolErrorPreview(output)
    assertTrue(preview.startsWith("started"))
    assertTrue(preview.endsWith("fatal: final failure"))
    assertTrue(preview.lines().size <= 12)
    assertEquals("small error", toolErrorPreview("small error"))
  }

  @Test
  fun plainAndMalformedOutputRemainLiteral() {
    val source = "{not JSON}\n![private](https://example.invalid/image)"
    assertEquals(source, formatToolContent(source).text)
    assertEquals("text", formatToolContent(source).language)
    assertEquals("bash", formatToolContent("echo hello", "bash").language)
  }

  @Test
  fun embeddedFenceCannotEscapeToolCode() {
    val content =
      ToolContent("before\n  `````\n![private](https://example.invalid/image)\nafter", "text")
    val fenced = fencedToolContent(content)
    assertTrue(fenced.startsWith("``````text\n"))
    assertTrue(fenced.endsWith("\n``````"))
    assertTrue(fenced.contains(content.text))
  }

  @Test
  fun previewIsBoundedAndDoesNotSplitUnicode() {
    val lines = (1..20).joinToString("\n") { "line $it" }
    assertEquals(12, toolPreview(lines).lines().size)
    assertFalse(toolPreview(lines).contains("line 13"))
    val emoji = "\uD83D\uDE00".repeat(5000)
    val preview = toolPreview(emoji)
    assertEquals(4000, preview.codePointCount(0, preview.length))
    assertTrue(preview.endsWith("\uD83D\uDE00"))
    assertEquals("small", toolPreview("small"))
  }
}
