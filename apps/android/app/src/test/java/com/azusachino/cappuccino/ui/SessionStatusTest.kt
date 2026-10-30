package com.azusachino.cappuccino.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStatusTest {
  @Test
  fun modelIdsReadAsPeopleSayThem() {
    assertEquals("opus 5.5", shortModel("claude-opus-5-5"))
    assertEquals("haiku 5.5", shortModel("claude-haiku-5-5"))
    assertEquals("glm-5.3-flash", shortModel("glm-5.3-flash"))
  }

  @Test
  fun tokenCountsAreShort() {
    assertEquals("950", formatTokens(950))
    assertEquals("76K", formatTokens(76_000))
    assertEquals("200K", formatTokens(200_000))
    assertEquals("1M", formatTokens(1_000_000))
    assertEquals("1.2M", formatTokens(1_234_567))
  }

  @Test
  fun defaultContextWindowsAreInferredAccurately() {
    assertEquals(200_000L, defaultContextWindow("claude-opus-5-5"))
    assertEquals(200_000L, defaultContextWindow(null))
    assertEquals(1_000_000L, defaultContextWindow("gemini-2.5-pro"))
    assertEquals(1_000_000L, defaultContextWindow(null, "agy"))
    assertEquals(1_000_000L, defaultContextWindow(null, "antigravity-cli"))
    assertEquals(64_000L, defaultContextWindow("deepseek-chat"))
    assertEquals(128_000L, defaultContextWindow("gpt-4o"))
    assertEquals(500_000L, defaultContextWindow("gpt-6.1-terra"))
    assertEquals(500_000L, defaultContextWindow("gpt-5.6-terra"))
    assertEquals(500_000L, defaultContextWindow("terra"))
    assertEquals(1_000_000L, defaultContextWindow("glm-5.3-flash"))
    assertEquals(128_000L, defaultContextWindow("qwen-2.5-coder"))
  }
}
