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
  fun readOfAnImageSaysSoWhenThereIsNoText() {
    fun read(images: Int?, output: String = "") =
      ConversationPart.ToolCall("Read", """{"file_path":"shot.png"}""", output, images = images)

    assertEquals("image", stepOutcome(read(1))?.text)
    assertEquals("3 images", stepOutcome(read(3))?.text)
    assertNull(stepOutcome(read(null)))
    // Text output still reports its lines rather than the image count.
    assertEquals("1 line", stepOutcome(read(1, output = "only line"))?.text)
  }

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
  fun blankTextDoesNotFractureActivityOrProduceEmptyProse() {
    val tool1 = tool("bash")
    val blank = ConversationPart.Text("")
    val whitespace = ConversationPart.Text("   \n  ")
    val tool2 = tool("read")
    val answer = ConversationPart.Text("Done.")

    val segments = transcriptSegments(listOf(tool1, blank, tool2, whitespace, answer))

    assertEquals(
      listOf(
        TranscriptSegment.Activity(listOf(tool1, tool2)),
        TranscriptSegment.Prose(answer),
      ),
      segments,
    )
  }

  private fun call(name: String, input: String, output: String? = "ok", isError: Boolean? = null) =
    ConversationPart.ToolCall(name, input, output, isError)

  @Test
  fun summaryCountsDistinctTargetsInTheOrderFirstDone() {
    val parts =
      listOf(
        ConversationPart.Thinking("x"),
        call("grep", """{"pattern":"tail"}"""),
        call("read", """{"path":"a.kt"}"""),
        call("Read", """{"file_path":"a.kt"}"""),
        call("read", """{"path":"b.kt"}"""),
        call("bash", """{"command":"make test"}"""),
      )
    assertEquals("Thought, searched, read 2 files, ran 1 command", activitySummary(parts))
    // The noun just said is not repeated.
    assertEquals(
      "Edited 1 file, wrote 1",
      activitySummary(
        listOf(call("Edit", """{"file_path":"a.kt"}"""), call("write", """{"path":"b.kt"}"""))
      ),
    )
    assertEquals("Used mcp__x", activitySummary(listOf(call("mcp__x", "{}"))))
  }

  @Test
  fun stepWordsNameTheToolAndWhatItTouched() {
    assertEquals(
      "bash" to "make test",
      stepWords(call("Bash", """{"command":"make test\nmore"}""")),
    )
    assertEquals(
      "read" to "a.kt:10+20",
      stepWords(call("read", """{"path":"a.kt","offset":10,"limit":20}""")),
    )
    assertEquals(
      "glob" to "**/*.kt  src",
      stepWords(call("Glob", """{"pattern":"**/*.kt","path":"src"}""")),
    )
    assertEquals(
      "run_command" to "Check devices",
      stepWords(
        call("run_command", """{"CommandLine":"adb devices","toolSummary":"Check devices"}""")
      ),
    )
    assertEquals(
      "read_file" to "/path/to/file.kt",
      stepWords(call("read_file", """{"Path":"/path/to/file.kt"}""")),
    )
    assertEquals("mcp__x" to "", stepWords(call("mcp__x", "not json")))
  }

  @Test
  fun cleanToolOutputStripsAntigravityRunnerTimestamps() {
    val withOutput =
      "Created At: 2026-10-31T10:07:08+09:00\nCompleted At: 2026-10-31T10:07:08+09:00\nCommand exited with code 0.\n\nCommand output:\nList of devices attached\n0cadf428  device"
    assertEquals("List of devices attached\n0cadf428  device", cleanToolOutput(withOutput))

    val noOutput =
      "Created At: 2026-10-31T10:07:08+09:00\nCompleted At: 2026-10-31T10:07:08+09:00\nCommand exited with code 0.\n\nNo output from the command."
    assertEquals("Completed successfully (no output)", cleanToolOutput(noOutput))
  }

  @Test
  fun outcomesSayOnlyWhatTheRecordProves() {
    // Shared first and last lines are not counted as changes.
    assertEquals(
      StepOutcome("+2 −1", Tone.OK),
      stepOutcome(
        call("Edit", """{"file_path":"a","old_string":"a\nb\nc","new_string":"a\nx\ny\nc"}""")
      ),
    )
    assertEquals(
      StepOutcome("+1 −1", Tone.OK),
      stepOutcome(call("edit", """{"path":"a","edits":[{"oldText":"p","newText":"q"}]}""")),
    )
    assertEquals(
      StepOutcome("+3", Tone.OK),
      stepOutcome(call("write", """{"path":"a","content":"1\n2\n3\n"}""")),
    )
    assertEquals(
      StepOutcome("2 lines"),
      stepOutcome(call("read", """{"path":"a"}""", "one\ntwo\n")),
    )
    assertEquals(
      StepOutcome("no matches"),
      stepOutcome(call("Grep", """{"pattern":"z"}""", "No matches found")),
    )
    assertEquals(
      StepOutcome("1 match"),
      stepOutcome(call("grep", """{"pattern":"z"}""", "a.kt:3: z")),
    )
    assertEquals(
      StepOutcome("48 passed · 3.1s", Tone.OK),
      stepOutcome(call("bash", """{"command":"npm test"}""", "Tests: 48 passed\nDone in 3.1s")),
    )
    assertEquals(StepOutcome("no output"), stepOutcome(call("bash", """{"command":"true"}""", "")))
    assertEquals(
      StepOutcome("exit 2", Tone.ERR),
      stepOutcome(call("bash", """{"command":"make"}""", "Exit code 2\nboom", isError = true)),
    )
    // No recorded result is neither a failure nor a running call: nothing is said.
    assertNull(stepOutcome(call("bash", """{"command":"sleep 9"}""", output = null)))
    // Output of an unrelated command is never read as a test run.
    assertNull(testOutcome("3 passed", "echo"))
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
