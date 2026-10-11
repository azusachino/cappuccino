package com.azusachino.cappuccino.ui

import org.junit.Assert.*
import org.junit.Test

class MessageBodyTest {
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
