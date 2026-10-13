package com.azusachino.cappuccino.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalConversationIdTest {
  private val digest = "a".repeat(64)

  @Test
  fun acceptsEachSupportedAgentPrefix() {
    listOf("pi", "claude", "agy").forEach { assertTrue(isCanonicalConversationId("$it:$digest")) }
  }

  @Test
  fun rejectsUnknownAgentsShortDigestsAndUppercase() {
    assertFalse(isCanonicalConversationId("codex:$digest"))
    assertFalse(isCanonicalConversationId("claude:${"a".repeat(63)}"))
    assertFalse(isCanonicalConversationId("claude:${"A".repeat(64)}"))
    assertFalse(isCanonicalConversationId("claude:$digest:extra"))
  }
}
