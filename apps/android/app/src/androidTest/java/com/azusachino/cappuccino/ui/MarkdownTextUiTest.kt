package com.azusachino.cappuccino.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Production-rendered integration tests for the native Markdown boundary: the library component
 * (not the pure helpers) must render structured content and preserve the passive `[Image: alt]`
 * contract, fresh-content replacement, and reset semantics.
 */
@RunWith(AndroidJUnit4::class)
class MarkdownTextUiTest {
  @get:Rule val compose = createComposeRule()

  /** Records every ACTION_VIEW attempt through the composition's [UriHandler]. */
  private class RecordingUriHandler : UriHandler {
    val opened = mutableListOf<String>()

    override fun openUri(uri: String) {
      opened += uri
    }
  }

  private var recordedHandler: RecordingUriHandler? = null

  private fun setContentPassive(markdown: String) {
    val handler = RecordingUriHandler()
    recordedHandler = handler
    compose.setContent {
      CompositionLocalProvider(LocalUriHandler provides handler) {
        MaterialTheme { Surface { MarkdownText(text = markdown) } }
      }
    }
    compose.waitForIdle()
  }

  private fun setContent(markdown: String) {
    compose.setContent { MaterialTheme { Surface { MarkdownText(text = markdown) } } }
    compose.waitForIdle()
  }

  private fun capture(name: String) {
    val bitmap =
      requireNotNull(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
          .uiAutomation
          .takeScreenshot()
      )
    try {
      androidx.test.platform.io.PlatformTestStorageRegistry.getInstance()
        .openOutputFile("$name.png")
        .use {
          org.junit.Assert.assertTrue(
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
          )
        }
    } finally {
      bitmap.recycle()
    }
  }

  @Test
  fun defaultReaderHeadingsUseMobileBodySize() {
    // Headings stay at the reader's body size; weight, not size, marks them.
    compose.setContent {
      MaterialTheme { Surface { MarkdownText("# Mobile heading\n\nBody text") } }
    }
    compose.waitForIdle()
    fun style(text: String): androidx.compose.ui.text.TextStyle {
      val layouts = mutableListOf<TextLayoutResult>()
      compose.onNodeWithText(text, useUnmergedTree = true).performSemanticsAction(
        SemanticsActions.GetTextLayoutResult
      ) { action ->
        org.junit.Assert.assertTrue(action(layouts))
      }
      return layouts.single().layoutInput.style
    }
    org.junit.Assert.assertEquals(style("Body text").fontSize, style("Mobile heading").fontSize)
    org.junit.Assert.assertEquals(
      androidx.compose.ui.text.font.FontWeight.Bold,
      style("Mobile heading").fontWeight,
    )
  }

  @Test
  fun rendersHeadingsListsQuotesTablesAndCode() {
    setContent(
      """
      # Title

      - one
        - nested
      > quoted

      | a | b |
      |---|---|
      | 1 | 2 |

      ```
      fenced()
      ```
      """
        .trimIndent()
    )
    compose.onNodeWithText("Title").assertExists()
    compose.onNodeWithText("nested").assertExists()
    compose.onNodeWithText("quoted").assertExists()
    // Table cells merge into one table text node; match a cell value as a bounded substring.
    compose.onNodeWithText("1", substring = true).assertExists()
    compose.onNodeWithText("fenced()").assertExists()
  }

  @Test
  fun rendersBlockImageAltTextPassively() {
    setContent("![a sunset photo](https://example.invalid/sunset.png)")
    compose.onNodeWithText("[Image: a sunset photo]").assertExists()
  }

  @Test
  fun rendersInlineImageAltInsideParagraphWithEmphasis() {
    setContent("Read **this** ![alt text](https://example.invalid/i.png) now.")
    // Emphasis merges into the paragraph text node; bounded substring match.
    compose.onNodeWithText("this", substring = true).assertExists()
    // The pre-pass replaces the image span in the source, so the caption is native paragraph text
    // merged into the paragraph's single semantics node; match the precise merged text.
    compose.onNodeWithText("Read this [Image: alt text] now.", substring = true).assertExists()
  }

  @Test
  fun rendersImageAltAfterOrdinaryLinkInSameParagraph() {
    setContentPassive(
      "See [the docs](https://example.invalid/docs) then ![sky alt](https://example.invalid/sky.png)."
    )
    // Caption and link are one merged native paragraph text node after source normalization.
    compose
      .onNodeWithText(
        "See the docs then [Image: sky alt].",
        substring = true,
      )
      .assertExists()
    compose.onNodeWithText("the docs", substring = true).assertExists()
  }

  @Test
  fun rendersDistinctAltForSameUrlImages() {
    // ONE paragraph: ordinary link FIRST, then two SAME-URL images with DIFFERENT correct ALTs.
    setContentPassive(
      "See [the docs](https://example.invalid/docs) ![morning alt](https://example.invalid/same.png)" +
        " and ![evening alt](https://example.invalid/same.png)"
    )
    // The whole paragraph is one merged native text node after source normalization; assert its
    // exact visible text: link label kept, both distinct captions in source order.
    compose
      .onNodeWithText(
        "See the docs [Image: morning alt] and [Image: evening alt]",
        substring = true,
      )
      .assertExists()
    compose.onNodeWithText("the docs", substring = true).assertExists()
  }

  @Test
  fun rendersNestedTableAndListImageAlt() {
    setContent(
      """
      | col |
      |-----|
      | ![table alt](https://example.invalid/t.png) |

      - ![list alt](https://example.invalid/l.png)
      """
        .trimIndent()
    )
    compose.onNodeWithText("[Image: table alt]", substring = true).assertExists()
    compose.onNodeWithText("[Image: list alt]", substring = true).assertExists()
  }

  @Test
  fun rendersReferenceImageAlt() {
    setContent("![ref alt][logo]\n\n[logo]: https://example.invalid/logo.png")
    compose.onNodeWithText("[Image: ref alt]").assertExists()
  }

  @Test
  fun codeFenceImageSyntaxStaysLiteral() {
    setContent("```\n![not an image](https://example.invalid/x.png)\n```")
    compose.onNodeWithText("![not an image](https://example.invalid/x.png)").assertExists()
    compose.onNodeWithText("[Image: not an image]").assertDoesNotExist()
  }

  @Test
  fun contentReplacementRendersFreshWithoutOldState() {
    // ONE composition, ONE mutable input: old present -> new present -> EMPTY (all old gone) ->
    // fresh caption from a new source. No invented generation; canonical text drives all states.
    var markdown by mutableStateOf("# First document\n\nold paragraph")
    compose.setContent { MaterialTheme { Surface { MarkdownText(text = markdown) } } }
    compose.waitForIdle()
    compose.onNodeWithText("old paragraph").assertExists()
    capture("MarkdownResetOld")
    markdown = "# Second document\n\nnew paragraph ![fresh alt](https://example.invalid/f.png)"
    compose.waitUntil(5_000) {
      compose
        .onAllNodesWithText("[Image: fresh alt]", substring = true)
        .fetchSemanticsNodes()
        .isNotEmpty()
    }
    compose.onNodeWithText("old paragraph", substring = true).assertDoesNotExist()
    compose.onNodeWithText("new paragraph [Image: fresh alt]").assertExists()
    capture("MarkdownResetNew")
    // EMPTY reset: all old body and captions must disappear, nothing invented.
    markdown = ""
    compose.waitUntil(5_000) {
      compose
        .onAllNodesWithText("[Image: fresh alt]", substring = true)
        .fetchSemanticsNodes()
        .isEmpty()
    }
    compose.onNodeWithText("old paragraph", substring = true).assertDoesNotExist()
    compose.onNodeWithText("[Image: fresh alt]", substring = true).assertDoesNotExist()
    compose.onNodeWithText("Second document", substring = true).assertDoesNotExist()
    compose.onNodeWithText("new paragraph", substring = true).assertDoesNotExist()
    capture("MarkdownResetEmpty")
    // Fresh source renders a fresh caption natively.
    markdown = "# Third document\n\n![regen alt](https://example.invalid/r.png)"
    compose.waitUntil(5_000) {
      compose.onAllNodesWithText("[Image: regen alt]").fetchSemanticsNodes().isNotEmpty()
    }
    compose.onNodeWithText("[Image: regen alt]").assertExists()
    capture("MarkdownResetFresh")
    android.util.Log.i(
      "MarkdownGlyphProof",
      "one composition old/new/EMPTY/fresh native reset verified",
    )
  }

  @Test
  fun viewMonitorPositiveControlIsSensitive() {
    // Positive control: a real ACTION_VIEW under a blocking monitor MUST register a hit, proving
    // the monitor used above actually observes ACTION_VIEW. Synthetic https://example.invalid only.
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val monitor =
      instrumentation.addMonitor(
        android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
          addDataScheme("https")
        },
        null,
        true,
      )
    try {
      instrumentation.targetContext.startActivity(
        android.content
          .Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://example.invalid/control"),
          )
          .setClass(
            instrumentation.targetContext,
            com.azusachino.cappuccino.MainActivity::class.java,
          )
          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      instrumentation.waitForIdleSync()
      org.junit.Assert.assertEquals(1, monitor.hits)
    } finally {
      instrumentation.removeMonitor(monitor)
    }
  }

  @Test
  fun nativeLinkGlyphTapsArePassiveAcrossBlocksAndLiteralCaptions() {
    val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
    val filter =
      android.content.IntentFilter(android.content.Intent.ACTION_VIEW).apply {
        addDataScheme("https")
      }
    val calibration = instrumentation.addMonitor(filter, null, true)
    try {
      // Explicit task-app component: even a broken monitor cannot start an external browser.
      instrumentation.targetContext.startActivity(
        android.content
          .Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://example.invalid/calibration"),
          )
          .setClass(
            instrumentation.targetContext,
            com.azusachino.cappuccino.MainActivity::class.java,
          )
          .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
      )
      instrumentation.waitForIdleSync()
      org.junit.Assert.assertEquals(1, calibration.hits)
      android.util.Log.i("MarkdownGlyphProof", "blocked VIEW calibration hits=${calibration.hits}")
    } finally {
      instrumentation.removeMonitor(calibration)
    }
    val monitor = instrumentation.addMonitor(filter, null, true)
    try {
      setContentPassive(
        """
        # [HeadingGlyph](https://example.invalid/heading)

        [ParagraphGlyph](https://example.invalid/paragraph) ![morning alt](https://example.invalid/same) and ![evening alt](https://example.invalid/same)

        - [ListGlyph](https://example.invalid/list)

        > [QuoteGlyph](https://example.invalid/quote)

        | label | caption |
        | --- | --- |
        | [TableGlyph](https://example.invalid/table) | ![p\|ipe](https://example.invalid/table-image) |

        ![collision](https://example.invalid/image)

        [Image: collision]: https://example.invalid/collision
        """
          .trimIndent()
      )
      val handler = checkNotNull(recordedHandler)
      handler.openUri("https://example.invalid/uri-calibration")
      org.junit.Assert.assertEquals(1, handler.opened.size)
      handler.opened.clear()
      compose
        .onNodeWithText("ParagraphGlyph [Image: morning alt] and [Image: evening alt]")
        .assertExists()
      compose.onNodeWithText("[Image: p|ipe]", substring = true).assertExists()
      compose.onNodeWithText("[Image: collision]").assertExists()
      capture("MarkdownNativeGlyphBefore")
      var taps = 0
      for (label in
        listOf(
          "HeadingGlyph",
          "ParagraphGlyph",
          "ListGlyph",
          "QuoteGlyph",
          "TableGlyph",
          "[Image: collision]",
        )) {
        compose.waitUntil(5_000) {
          compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        val node = compose.onNodeWithText(label, substring = true, useUnmergedTree = true)
        node.assertIsDisplayed()
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
          org.junit.Assert.assertTrue(action(layouts))
        }
        val layout = layouts.single { it.layoutInput.text.text.contains(label) }
        val text = layout.layoutInput.text
        val start = text.text.indexOf(label)
        org.junit.Assert.assertTrue(start >= 0)
        val offset = start + label.length / 2
        val glyph = layout.getBoundingBox(offset)
        org.junit.Assert.assertTrue(
          "clipped $label $glyph",
          glyph.width > 0 &&
            glyph.height > 0 &&
            glyph.center.x >= 0 &&
            glyph.center.y >= 0 &&
            glyph.center.x < layout.size.width &&
            glyph.center.y < layout.size.height,
        )
        val annotations = text.getLinkAnnotations(start, start + label.length)
        android.util.Log.i(
          "MarkdownGlyphProof",
          "label=$label source=https://example.invalid text=${text.text} offset=$offset glyph=$glyph local=${glyph.center} bounds=${node.fetchSemanticsNode().boundsInRoot} linkAnnotations=$annotations inertIfAbsent=${annotations.isEmpty()}",
        )
        node.performTouchInput { click(glyph.center) }
        compose.waitForIdle()
        taps++
        org.junit.Assert.assertEquals(0, handler.opened.size)
        org.junit.Assert.assertEquals(0, monitor.hits)
      }
      org.junit.Assert.assertEquals(6, taps)
      capture("MarkdownNativeGlyphAfter")
      android.util.Log.i(
        "MarkdownGlyphProof",
        "production glyph taps=$taps outerURI=${handler.opened.size} blockingVIEW=${monitor.hits}; distinctALT/order/table/collision verified",
      )
    } finally {
      instrumentation.removeMonitor(monitor)
      recordedHandler = null
    }
  }
}
