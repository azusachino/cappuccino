package com.azusachino.cappuccino.core

private val canonicalConversationIdPattern = Regex("(pi|claude|agy):[a-f0-9]{64}")

fun isCanonicalConversationId(value: String): Boolean =
  value.length in 67..71 && canonicalConversationIdPattern.matches(value)
