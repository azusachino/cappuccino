import Foundation
import Testing

@testable import CappuccinoCore

/// Ports TranscriptActivityTest: each Android rule gets the same expectation.
struct TranscriptActivityTests {
  private func tool(
    _ name: String, input: String = "{}", output: String? = "ok", isError: Bool? = nil,
    images: Int? = nil, started: String? = nil, finished: String? = nil
  ) -> ToolCall {
    ToolCall(
      name: name, input: input, output: output, isError: isError, startedAt: started,
      finishedAt: finished, images: images)
  }

  @Test func readOfAnImageSaysSoWhenThereIsNoText() {
    func read(_ images: Int?, output: String = "") -> ToolCall {
      tool("Read", input: #"{"file_path":"shot.png"}"#, output: output, images: images)
    }
    #expect(stepOutcome(read(1))?.text == "image")
    #expect(stepOutcome(read(3))?.text == "3 images")
    #expect(stepOutcome(read(nil)) == nil)
    // Text output still reports its lines rather than the image count.
    #expect(stepOutcome(read(1, output: "only line"))?.text == "1 line")
  }

  @Test func consecutiveThinkingAndToolsBecomeOneActivityBetweenProse() {
    let intro = ConversationPart.text("Looking.")
    let thinking = ConversationPart.thinking("plan")
    let read = ConversationPart.toolCall(tool("read"))
    let bash = ConversationPart.toolCall(tool("bash"))
    let answer = ConversationPart.text("Fixed.")
    #expect(
      transcriptSegments([intro, thinking, read, bash, answer])
        == [
          .prose(intro),
          .activity([thinking, read, bash]),
          .prose(answer),
        ])
  }

  @Test func trailingActivityAndProseOnlyTurnsAreKept() {
    #expect(
      transcriptSegments([.toolCall(tool("edit"))])
        == [.activity([.toolCall(tool("edit"))])])
    #expect(transcriptSegments([]) == [])
  }

  @Test func summaryCountsDistinctTargetsInTheOrderFirstDone() {
    let parts: [ConversationPart] = [
      .thinking("x"),
      .toolCall(tool("grep", input: #"{"pattern":"tail"}"#)),
      .toolCall(tool("read", input: #"{"path":"a.kt"}"#)),
      .toolCall(tool("Read", input: #"{"file_path":"a.kt"}"#)),
      .toolCall(tool("read", input: #"{"path":"b.kt"}"#)),
      .toolCall(tool("bash", input: #"{"command":"make test"}"#)),
    ]
    #expect(activitySummary(parts) == "Thought, searched, read 2 files, ran 1 command")
    // The noun just said is not repeated.
    #expect(
      activitySummary([
        .toolCall(tool("Edit", input: #"{"file_path":"a.kt"}"#)),
        .toolCall(tool("write", input: #"{"path":"b.kt"}"#)),
      ]) == "Edited 1 file, wrote 1")
    #expect(activitySummary([.toolCall(tool("mcp__x"))]) == "Used mcp__x")
  }

  @Test func stepWordsNameTheToolAndWhatItTouched() {
    #expect(
      stepWords(tool("Bash", input: #"{"command":"make test\nmore"}"#))
        == StepWords(label: "bash", subject: "make test"))
    #expect(
      stepWords(tool("read", input: #"{"path":"a.kt","offset":10,"limit":20}"#))
        == StepWords(label: "read", subject: "a.kt:10+20"))
    #expect(
      stepWords(tool("Glob", input: #"{"pattern":"**/*.kt","path":"src"}"#))
        == StepWords(label: "glob", subject: "**/*.kt  src"))
    #expect(stepWords(tool("mcp__x", input: "not json")) == StepWords(label: "mcp__x", subject: ""))
  }

  @Test func outcomesSayOnlyWhatTheRecordProves() {
    // Shared first and last lines are not counted as changes.
    #expect(
      stepOutcome(
        tool("Edit", input: #"{"file_path":"a","old_string":"a\nb\nc","new_string":"a\nx\ny\nc"}"#))
        == StepOutcome("+2 −1", tone: .ok))
    #expect(
      stepOutcome(tool("edit", input: #"{"path":"a","edits":[{"oldText":"p","newText":"q"}]}"#))
        == StepOutcome("+1 −1", tone: .ok))
    #expect(
      stepOutcome(tool("write", input: #"{"path":"a","content":"1\n2\n3\n"}"#))
        == StepOutcome("+3", tone: .ok))
    #expect(
      stepOutcome(tool("read", input: #"{"path":"a"}"#, output: "one\ntwo\n"))
        == StepOutcome("2 lines"))
    #expect(
      stepOutcome(tool("Grep", input: #"{"pattern":"z"}"#, output: "No matches found"))
        == StepOutcome("no matches"))
    #expect(
      stepOutcome(tool("grep", input: #"{"pattern":"z"}"#, output: "a.kt:3: z"))
        == StepOutcome("1 match"))
    #expect(
      stepOutcome(
        tool("bash", input: #"{"command":"npm test"}"#, output: "Tests: 48 passed\nDone in 3.1s"))
        == StepOutcome("48 passed · 3.1s", tone: .ok))
    #expect(
      stepOutcome(tool("bash", input: #"{"command":"true"}"#, output: ""))
        == StepOutcome("no output"))
    #expect(
      stepOutcome(
        tool("bash", input: #"{"command":"make"}"#, output: "Exit code 2\nboom", isError: true))
        == StepOutcome("exit 2", tone: .err))
    // No recorded result is neither a failure nor a running call: nothing is said.
    #expect(stepOutcome(tool("bash", input: #"{"command":"sleep 9"}"#, output: nil)) == nil)
    // Output of an unrelated command is never read as a test run.
    #expect(testOutcome("3 passed", command: "echo") == nil)
  }

  @Test func durationNeedsBothEndsAndRejectsUnparseableOrReversedTimes() {
    let parts: [ConversationPart] = [
      .toolCall(tool("read", started: "2026-10-12T10:00:00Z", finished: "2026-10-12T10:00:01Z")),
      .toolCall(
        tool("bash", started: "2026-10-12T10:00:01Z", finished: "2026-10-12T10:00:06.500Z")),
    ]
    #expect(activityDurationMillis(parts) == 6_500)
    #expect(
      activityDurationMillis([.toolCall(tool("bash", started: "2026-10-12T10:00:00Z"))]) == nil)
    #expect(
      activityDurationMillis([.toolCall(tool("bash", started: "yesterday", finished: "today"))])
        == nil)
    #expect(
      toolDurationMillis(
        tool("bash", started: "2026-10-12T10:00:05Z", finished: "2026-10-12T10:00:00Z")) == nil)
  }

  @Test func compactDurationScalesUnits() {
    #expect(compactDuration(millis: 420) == "420ms")
    #expect(compactDuration(millis: 3_140) == "3.1s")
    #expect(compactDuration(millis: 48_000) == "48s")
    #expect(compactDuration(millis: 125_000) == "2m 5s")
  }
}
