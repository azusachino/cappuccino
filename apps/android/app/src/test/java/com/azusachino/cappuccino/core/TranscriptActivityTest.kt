package com.azusachino.cappuccino.core

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptActivityTest {
  private fun tool(
    name: String,
    started: String? = null,
    finished: String? = null,
  ) = ConversationPart.ToolCall(name, "{}", "ok", startedAt = started, finishedAt = finished)

  @Test
  fun consecutiveThinkingAndToolsBecomeOneActivityBetweenProse() {
    val intro = ConversationPart.Text("Looking.")
    val thinking = ConversationPart.Thinking("plan")
    val read = tool("read")
    val bash = tool("bash")
    val answer = ConversationPart.Text("Fixed.")

    val segments = transcriptSegments(listOf(intro, thinking, read, bash, answer))

    assertEquals(
      listOf(
        TranscriptSegment.Prose(intro),
        TranscriptSegment.Activity(listOf(thinking, read, bash)),
        TranscriptSegment.Prose(answer),
      ),
      segments,
    )
  }

  @Test
  fun trailingActivityAndProseOnlyTurnsAreKept() {
    assertEquals(
      listOf(TranscriptSegment.Activity(listOf(tool("edit")))),
      transcriptSegments(listOf(tool("edit"))),
    )
    assertEquals(emptyList<TranscriptSegment>(), transcriptSegments(emptyList()))
  }

  @Test
  fun summaryCountsPiAndClaudeToolNamesAlike() {
    val parts =
      listOf(
        ConversationPart.Thinking("x"),
        tool("edit"),
        tool("Edit"),
        tool("write"),
        tool("bash"),
        tool("Bash"),
        tool("Glob"),
        tool("mcp__custom"),
      )
    assertEquals(
      "Thought, ran 1 search, edited 2 files, wrote 1 file, ran 2 commands, used 1 tool",
      activitySummary(parts),
    )
  }

  @Test
  fun durationNeedsBothEndsAndRejectsUnparseableOrReversedTimes() {
    val parts =
      listOf(
        tool("read", "2026-10-12T10:00:00Z", "2026-10-12T10:00:01Z"),
        tool("bash", "2026-10-12T10:00:01Z", "2026-10-12T10:00:06.500Z"),
      )
    assertEquals(Duration.ofMillis(6_500), activityDuration(parts))
    assertNull(activityDuration(listOf(tool("bash", "2026-10-12T10:00:00Z"))))
    assertNull(activityDuration(listOf(tool("bash", "yesterday", "today"))))
    assertNull(toolDuration(tool("bash", "2026-10-12T10:00:05Z", "2026-10-12T10:00:00Z")))
  }

  @Test
  fun compactDurationScalesUnits() {
    assertEquals("420ms", compactDuration(Duration.ofMillis(420)))
    assertEquals("3.1s", compactDuration(Duration.ofMillis(3_140)))
    assertEquals("48s", compactDuration(Duration.ofSeconds(48)))
    assertEquals("2m 5s", compactDuration(Duration.ofSeconds(125)))
  }
}
