package com.azusachino.cappuccino.ui

import org.junit.Assert.*
import org.junit.Test

class MessageBodyTest {
  @Test
  fun transcriptKeepsOrdinaryRepliesInlineWithoutSixLineTruncation() {
    val text = (1..100).joinToString("\n") { "Reply line $it" }
    assertEquals(text, transcriptInlineText(text))
  }

  @Test
  fun transcriptBudgetPreservesUnicodeAndExactSourcePrefix() {
    val text = "😀".repeat(16_001)
    val bounded = transcriptInlineText(text)
    assertEquals(16_000, bounded.codePointCount(0, bounded.length))
    assertTrue(text.startsWith(bounded))
    assertEquals("", transcriptInlineText(""))
  }

  @Test
  fun shortMessageIsUntouchedAndLongPreviewIsBoundedWithoutBreakingUnicode() {
    assertEquals("Short reply", messagePreview("Short reply"))
    val text = "😀".repeat(900)
    val preview = messagePreview(text)
    assertEquals(600, preview.codePointCount(0, preview.length))
    assertTrue(text.startsWith(preview))
    assertTrue(messagePreview("line\n".repeat(30)).lines().size <= 6)
  }
}
