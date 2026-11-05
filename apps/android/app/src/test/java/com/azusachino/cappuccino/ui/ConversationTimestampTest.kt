package com.azusachino.cappuccino.ui

import java.time.ZoneId
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTimestampTest {
  @Test
  fun rendersDeviceZoneAndExactFormatWithoutCachingUtc() {
    val original = TimeZone.getDefault()
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
      assertEquals("2026-10-08 01:02:03", formatConversationTimestamp("2026-10-07T16:02:03.456Z"))
      TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
      assertEquals("2026-10-07 09:02:03", formatConversationTimestamp("2026-10-07T16:02:03Z"))
      assertEquals("2026-01-07 08:02:03", formatConversationTimestamp("2026-01-07T16:02:03Z"))
    } finally {
      TimeZone.setDefault(original)
    }
  }

  @Test
  fun handlesOffsetsDateRolloverAndInvalidInput() {
    assertEquals(
      "2026-10-08 01:02:03",
      formatConversationTimestamp("2026-10-07T18:02:03+02:00", ZoneId.of("Asia/Tokyo")),
    )
    assertEquals(
      "01:02",
      formatShortConversationTime("2026-10-07T18:02:03+02:00", ZoneId.of("Asia/Tokyo")),
    )
    val now = java.time.Instant.parse("2026-10-08T12:00:00Z")
    assertEquals(
      "01:02",
      formatConversationDateTime("2026-10-07T16:02:00Z", ZoneId.of("Asia/Tokyo"), now),
    )
    assertEquals(
      "10-06 14:00",
      formatConversationDateTime("2026-10-06T05:00:00Z", ZoneId.of("Asia/Tokyo"), now),
    )
    assertEquals(
      "2025-10-06 14:00",
      formatConversationDateTime("2025-10-06T05:00:00Z", ZoneId.of("Asia/Tokyo"), now),
    )
    assertNull(formatShortConversationTime("not a timestamp"))
    assertNull(formatConversationDateTime("not a timestamp"))
    assertNull(formatConversationTimestamp("+1000000000-12-31T23:59:59.999999999Z"))
    assertNull(formatConversationTimestamp("not a timestamp"))
    assertNull(formatConversationTimestamp("2026-10-07T16:02:03"))
  }
}
