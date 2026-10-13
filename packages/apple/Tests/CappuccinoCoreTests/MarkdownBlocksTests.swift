import CappuccinoCore
import Foundation
import Testing

@Suite struct MarkdownBlocksTests {
  @Test func repliesKeepTheirBlocks() {
    let blocks = markdownBlocks(
      """
      # Gradle

      Android caches Gradle.

      - **Apple simulator:** boots early
      - UI journeys run on main

      1. first
      2. second

      ```kotlin
      val value = 42
      ```

      > quoted
      """)
    let kinds = blocks.map(\.kind)
    #expect(
      kinds == [
        .heading(level: 1), .paragraph, .listItem(marker: "•", depth: 1),
        .listItem(marker: "•", depth: 1), .listItem(marker: "1.", depth: 1),
        .listItem(marker: "2.", depth: 1), .code(language: "kotlin"), .quote,
      ])
    let texts = blocks.map { String($0.text.characters) }
    #expect(texts[1] == "Android caches Gradle.")
    // The bold lead-in stays inside its own list item, not glued to the paragraph before it.
    #expect(texts[2] == "Apple simulator: boots early")
    #expect(texts[6] == "val value = 42")
  }

  @Test func plainTextIsOneParagraph() {
    #expect(markdownBlocks("just words").map(\.kind) == [.paragraph])
  }
}
