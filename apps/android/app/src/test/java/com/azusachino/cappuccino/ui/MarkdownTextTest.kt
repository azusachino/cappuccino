package com.azusachino.cappuccino.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.BlockQuote
import org.commonmark.node.Heading
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test

class MarkdownTextTest {
  private val parser = Parser.builder().build()

  @Test
  fun preservesInlineCodeEmphasisEscapesAndBreaks() {
    val paragraph = parser.parse("Use **bold `a*b`** and \\*literal\\*.\nNext  \nline.").firstChild
    val rendered = markdownInlineText(paragraph, Color.Gray)
    assertEquals("Use bold a*b and *literal*. Next\nline.", rendered.text)
    assertTrue(rendered.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    val code = rendered.spanStyles.single { it.item.fontFamily == FontFamily.Monospace }
    assertEquals("a*b", rendered.text.substring(code.start, code.end))
    assertEquals(Color.Gray, code.item.background)
  }

  @Test
  fun parsesHeadersAndTreatsHtmlLinksAndImagesAsPassiveText() {
    val document =
      parser.parse(
        "Title\n=====\n\n<script>alert(1)</script>\n\n[read](javascript:alert) ![alt](https://image.example/a.png)"
      )
    assertTrue(document.firstChild is Heading)
    assertEquals("Title", markdownInlineText(document.firstChild).text)
    assertEquals("<script>alert(1)</script>", plainMarkdownText(document.firstChild.next))
    assertEquals("read [Image: alt]", markdownInlineText(document.lastChild).text)
    assertTrue(markdownInlineText(document.lastChild).getStringAnnotations(0, 100).isEmpty())
  }

  @Test
  fun deeplyNestedFallbackKeepsTextWithoutRecursiveTraversal() {
    val root = BlockQuote()
    var parent = root
    repeat(1000) {
      val child = BlockQuote()
      parent.appendChild(child)
      parent = child
    }
    parent.appendChild(Text("Deep content stays readable"))
    assertEquals("Deep content stays readable", plainMarkdownText(root))
    assertEquals("Deep content stays readable", markdownInlineText(root).text)
  }
}
