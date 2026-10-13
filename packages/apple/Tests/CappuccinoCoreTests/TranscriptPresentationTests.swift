import Foundation
import Testing

@testable import CappuccinoCore

/// Presentation rules for the conversation page; each mirrors a Kotlin rule in the transcript UI.
struct TranscriptPresentationTests {
  private func turn(
    _ id: String, role: String = "agent", text: String? = nil, parts: [ConversationPart] = []
  )
    -> ConversationTurn
  {
    ConversationTurn(id: id, role: role, timestamp: nil, text: text, parts: parts)
  }

  @Test func repliesPreviewSixLinesAndSixHundredCharacters() {
    let lines = (1...8).map { "line \($0)" }.joined(separator: "\n")
    #expect(messagePreview(lines) == (1...6).map { "line \($0)" }.joined(separator: "\n"))
    #expect(messagePreview(String(repeating: "x", count: 900)).count == 600)
    #expect(messagePreview("short") == "short")
    #expect(transcriptInlineText(String(repeating: "y", count: 20_000)).count == 16_000)
  }

  @Test func liveStatusIsBlockedWhilePromptWaitsAndOtherwiseTheStreamsWord() {
    #expect(
      liveStatus(pendingPrompt: true, streamStatus: "working", listedStatus: nil) == "blocked")
    #expect(
      liveStatus(pendingPrompt: false, streamStatus: "Working", listedStatus: "idle") == "working")
    #expect(liveStatus(pendingPrompt: false, streamStatus: nil, listedStatus: "idle") == "idle")
    #expect(liveStatus(pendingPrompt: false, streamStatus: nil, listedStatus: nil) == "")
  }

  @Test func onlyTurnsWithTextOrPartsAreChatTurns() {
    let turns = [turn("a", text: "hi"), turn("b", text: "  "), turn("c", parts: [.text("x")])]
    #expect(chatTurns(turns).map(\.id) == ["a", "c"])
  }

  @Test func newestTurnsAreThoseAfterTheLastPerson() {
    let turns = [
      turn("u1", role: "user", text: "q"), turn("a1", text: "answer"),
      turn("u2", role: "user", text: "next"), turn("a2"), turn("a3"),
    ]
    #expect(newestTurnIDs(turns) == ["a2", "a3"])
    #expect(newestTurnIDs([turn("a1")]) == ["a1"])
  }

  @Test func localTimeIsHoursAndMinutesInTheGivenZone() throws {
    let time = try #require(localTime("2026-10-12T01:05:00Z", zone: TimeZone(identifier: "UTC")!))
    #expect(time.short == "01:05")
    #expect(localTime(nil) == nil)
    #expect(localTime("yesterday") == nil)
  }

  @Test func followingStopsOnDragAndResumesOnlyNearTheBottom() {
    var follow = FollowState()
    #expect(follow.following)
    follow.readerMoved(distanceFromBottom: 400)
    #expect(!follow.following)
    follow.readerMoved(distanceFromBottom: 60)
    #expect(follow.following)
    follow.dragged()
    #expect(!follow.following)
    follow.jumpToLatest()
    #expect(follow.following)
  }
}
