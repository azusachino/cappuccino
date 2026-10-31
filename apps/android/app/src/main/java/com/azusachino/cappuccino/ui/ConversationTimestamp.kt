package com.azusachino.cappuccino.ui

import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val conversationTimestampFormat =
  DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

private val conversationShortTimeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

fun formatConversationTimestamp(timestamp: String, zone: ZoneId = ZoneId.systemDefault()): String? =
  try {
    conversationTimestampFormat.format(Instant.parse(timestamp).atZone(zone))
  } catch (_: DateTimeException) {
    null
  }

fun formatShortConversationTime(timestamp: String, zone: ZoneId = ZoneId.systemDefault()): String? =
  try {
    conversationShortTimeFormat.format(Instant.parse(timestamp).atZone(zone))
  } catch (_: Exception) {
    null
  }
