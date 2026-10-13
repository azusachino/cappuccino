package com.azusachino.cappuccino.ui

import org.commonmark.node.Image
import org.commonmark.node.Paragraph
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure regression tests for the render-only caption normalization pre-pass
 * [normalizeImageCaptions]: own-image identity by exact AST source range, ALT escaping, and
 * preservation of every other Markdown byte. These are the position-adapter RED→GREEN contract; the
 * previous paragraph-node-based ALT resolution picked the paragraph's FIRST `LINK_TEXT`, so tests 1
 * and 2 fail on the old adapter.
 */
class MarkdownImageCaptionTest {
  private val spanParser =
    Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES).build()

  /** Complete images must be gone; literal code tokens must not be miscounted as images. */
  private fun realImages(normalized: String): List<Image> {
    val images = mutableListOf<Image>()
    val stack = ArrayDeque<org.commonmark.node.Node>()
    stack.addLast(spanParser.parse(normalized))
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      if (node is Image) images.add(node)
      generateSequence(node.firstChild) { it.next }.forEach { stack.addLast(it) }
    }
    return images
  }

  @Test
  fun priorOrdinaryLinkDoesNotStealImageCaption() {
    val md =
      "See [the docs](https://example.invalid/d) then ![sky alt](https://example.invalid/s.png)"
    val out = normalizeImageCaptions(md)
    assertEquals(
      "See [the docs](https://example.invalid/d) then \\[Image\\: sky alt\\]",
      out,
    )
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun twoSameUrlImagesInSameParagraphKeepDistinctAlts() {
    val md =
      "![first alt](https://example.invalid/x.png) middle ![second alt](https://example.invalid/x.png)"
    val out = normalizeImageCaptions(md)
    assertEquals(
      "\\[Image\\: first alt\\] middle \\[Image\\: second alt\\]",
      out,
    )
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun paragraphStructureAndOtherBytesPreserved() {
    val md =
      "# Head\n\nA [link](https://example.invalid/l) and ![pic](https://example.invalid/p.png) tail.\n\n> quoted ![q](https://example.invalid/q.png)\n"
    val out = normalizeImageCaptions(md)
    assertEquals(
      "# Head\n\nA [link](https://example.invalid/l) and \\[Image\\: pic\\] tail.\n\n> quoted \\[Image\\: q\\]\n",
      out,
    )
  }

  @Test
  fun emptyAltYieldsEmptyCaptionLiteral() {
    assertEquals(
      "a \\[Image\\: \\] b",
      normalizeImageCaptions("a ![](https://example.invalid/e.png) b"),
    )
  }

  @Test
  fun referenceImageUsesDefinitionSyntaxRangeAndAlt() {
    val md = "![ref alt][logo]\n\n[logo]: https://example.invalid/logo.png"
    val out = normalizeImageCaptions(md)
    assertEquals("\\[Image\\: ref alt\\]\n\n[logo]: https://example.invalid/logo.png", out)
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun fencedAndInlineCodeImageSyntaxStaysLiteral() {
    val md =
      "```\n![fenced](https://example.invalid/f.png)\n```\n\nRun `![inline](https://example.invalid/i.png)` now"
    val out = normalizeImageCaptions(md)
    assertEquals(md, out)
  }

  @Test
  fun altWithMarkdownPunctuationIsEscapedAgainstInjection() {
    val out = normalizeImageCaptions("![weird [alt] \\ here](https://example.invalid/w.png)")
    assertEquals("\\[Image\\: weird \\[alt\\] \\\\ here\\]", out)
    // The escaped literal must re-parse as ONE paragraph of plain text, not a link or structure.
    val para = spanParser.parse(out).firstChild
    assertTrue(para is Paragraph)
    assertTrue(realImages(out).isEmpty())
    assertTrue(
      generateSequence(para.firstChild) { it.next }.none { it is org.commonmark.node.Link }
    )
  }

  @Test
  fun emphasisInAltFlattensToPlainText() {
    val out = normalizeImageCaptions("![*bold* alt](https://example.invalid/b.png)")
    assertEquals("\\[Image\\: bold alt\\]", out)
  }

  @Test
  fun astralCharactersBeforeImageDoNotShiftRange() {
    val md = "🦄🌈 ![emoji alt](https://example.invalid/e2.png) end"
    val out = normalizeImageCaptions(md)
    assertEquals("🦄🌈 \\[Image\\: emoji alt\\] end", out)
  }

  @Test
  fun imageInsideListAndTableCellNormalized() {
    val md =
      "- item ![li](https://example.invalid/li.png)\n\n| h |\n| --- |\n| ![t](https://example.invalid/t.png) |"
    val out = normalizeImageCaptions(md)
    assertEquals(
      "- item \\[Image\\: li\\]\n\n| h |\n| --- |\n| \\[Image\\: t\\] |",
      out,
    )
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun nestedImageKeepsOnlyOutermostRangeAndOwnPlainAlt() {
    val md = "![outer ![inner](https://example.invalid/u)](https://example.invalid/v)"
    val out = normalizeImageCaptions(md)
    // Exactly one replacement, outer range only, no overlap crash, outer identity; the inner
    // ALT flattens to plain text ("inner") per the documented plain-ALT semantics.
    assertEquals("\\[Image\\: outer inner\\]", out)
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun crOnlyLineEndingsDoNotCorruptSpans() {
    val md = "para one\r![cr alt](https://example.invalid/cr.png)\rsecond"
    val out = normalizeImageCaptions(md)
    assertEquals("para one\r\\[Image\\: cr alt\\]\rsecond", out)
    assertTrue(realImages(out).isEmpty())
  }

  @Test
  fun gfmTableCaptionPipeStaysInertInsideLiteral() {
    // A native-recognized escaped ALT pipe must not split columns after re-render.
    val md = "| a | b |\n| --- | --- |\n| ![p\\|ipe](https://example.invalid/p.png) | z |"
    val out = normalizeImageCaptions(md)
    assertEquals("| a | b |\n| --- | --- |\n| \\[Image\\: p\\|ipe\\] | z |", out)
    val row = spanParser.parse(out).lastChild
    assertTrue(
      generateSequence(row.firstChild) { it.next }.none { it is org.commonmark.node.Image }
    )
  }

  @Test
  fun escapedCaptionShieldsEmphasisAndHtmlMarkers() {
    val out = normalizeImageCaptions("![*em* <b>x</b> _u_](https://example.invalid/h.png)")
    // Emphasis markers flatten to plain text (documented ALT semantics); HTML survives literally
    // and every ASCII punctuation char is backslash-escaped, which the native GFM renderer shows
    // as the original characters (per CommonMark §2.4 escaped-punctuation display).
    assertEquals("\\[Image\\: em \\<b\\>x\\<\\/b\\> u\\]", out)
  }

  @Test
  fun documentWithoutImagesIsUnchanged() {
    val md = "# T\n\nplain [l](https://example.invalid/x) text\n"
    assertEquals(md, normalizeImageCaptions(md))
  }

  @Test
  fun incompleteStreamingImageSyntaxStaysLiteral() {
    val md = "loading ![alt and more text"
    assertEquals(md, normalizeImageCaptions(md))
  }

  // ---------------------------------------------------------------------
  // NATIVE AST proof: the ACTUAL INSTALLED 0.45.0 renderer engine — the
  // IntelliJ/JetBrains GFM parser (org.intellij.markdown, markdown-jvm
  // 0.7.9, resolved transitively by com.mikepenz:multiplatform-markdown-
  // renderer) — NOT the commonmark-java locator above. Every fixture below
  // asserts against the native AST of the ORIGINAL and the NORMALIZED
  // source; Java re-parse alone is not proof.
  // ---------------------------------------------------------------------

  /** The production renderer's own engine and default flavour: IntelliJ/JetBrains GFM. */
  private val nativeParser =
    MarkdownParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)

  private fun nativeTree(src: String): ASTNode =
    nativeParser.buildMarkdownTreeFromString(src as CharSequence)

  private fun collect(root: ASTNode, match: (ASTNode) -> Boolean): List<ASTNode> {
    val out = mutableListOf<ASTNode>()
    val stack = ArrayDeque<ASTNode>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
      val n = stack.removeLast()
      if (match(n)) out.add(n)
      n.children.asReversed().forEach { stack.addLast(it) }
    }
    return out
  }

  private fun nativeImages(src: String) =
    collect(nativeTree(src)) { it.type == MarkdownElementTypes.IMAGE }

  private fun src(root: ASTNode, src: String, n: ASTNode) =
    src.substring(n.startOffset, n.endOffset)

  @Test
  fun nativeAstOriginalHasImageAndNormalizedHasNone() {
    val md =
      "See [the docs](https://example.invalid/d) then ![sky alt](https://example.invalid/s.png)"
    assertEquals(1, nativeImages(md).size)
    assertEquals(0, nativeImages(normalizeImageCaptions(md)).size)
    // Native round-trip: offsets of the normalized native tree reconstruct the exact source.
    val out = normalizeImageCaptions(md)
    val root = nativeTree(out)
    assertEquals(out, src(root, out, root))
  }

  @Test
  fun nativeAstEscapedCaptionInjectsNoImageLinkHtmlOrEmphasis() {
    val out = normalizeImageCaptions("![*em* <b>x</b> _u_ [l](u)](https://example.invalid/h.png)")
    val forbidden =
      setOf(
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.EMPH,
        MarkdownElementTypes.STRONG,
        MarkdownElementTypes.HTML_BLOCK,
      )
    val nodes = collect(nativeTree(out)) { it.type in forbidden }
    assertTrue("injection nodes: ${nodes.map { it.type }}", nodes.isEmpty())
    // The generated wrapper is literal even when a matching reference definition exists.
    val destinations = collect(nativeTree(out)) { it.type == MarkdownElementTypes.LINK_DESTINATION }
    assertTrue("unexpected destinations", destinations.isEmpty())
    // The visible literal, with CommonMark escapes unescaped, is the original plain ALT chars.
    val para = nativeTree(out).children.first { it.type == MarkdownElementTypes.PARAGRAPH }
    val literal = para.getTextInNode(out).replace(Regex("\\\\([!-/:-@\\[-`{-~])"), "$1")
    assertTrue("literal: $literal", literal.contains("em <b>x</b> u"))
  }

  @Test
  fun nativeGfmTableValidEscapedPipeKeepsRowCellCounts() {
    // Valid GFM: the image's ALT carries a backslash-escaped pipe, so the table stays 2 columns.
    val md = "| a | b |\n| --- | --- |\n| ![p\\|ipe](https://example.invalid/p.png) | z |"
    fun rowCellCounts(t: String): List<Int> {
      val table = collect(nativeTree(t)) { it.type == GFMElementTypes.TABLE }.first()
      return table.children
        .filter { it.type == GFMElementTypes.HEADER || it.type == GFMElementTypes.ROW }
        .map { row -> row.children.count { it.type == GFMTokenTypes.CELL } }
    }
    assertEquals(listOf(2, 2), rowCellCounts(md))
    assertEquals(1, nativeImages(md).size)
    val out = normalizeImageCaptions(md)
    assertEquals(listOf(2, 2), rowCellCounts(out))
    assertEquals(0, nativeImages(out).size)
  }

  @Test
  fun nativeGfmTableRawUnescapedPipeSplitsColumnCounterexample() {
    // No native IMAGE exists: normalization must not repair syntax or change native cells.
    val raw = "| a | b |\n| --- | --- |\n| ![p|ipe](https://example.invalid/p.png) | z |"
    assertEquals(0, nativeImages(raw).size)
    assertTrue(
      collect(nativeTree(raw)) { it.type == GFMTokenTypes.CELL }
        .any { it.getTextInNode(raw).contains("![p") }
    )
    val out = normalizeImageCaptions(raw)
    assertEquals(raw, out)
    assertEquals(
      collect(nativeTree(raw)) { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(raw) },
      collect(nativeTree(out)) { it.type == GFMTokenTypes.CELL }.map { it.getTextInNode(out) },
    )
    assertEquals(0, nativeImages(out).size)
  }

  @Test
  fun generatedCaptionCannotBindToCanonicalReferenceDefinition() {
    val md =
      "![alt](https://example.invalid/image)\n\n[Image: alt]: https://example.invalid/collision"
    val out = normalizeImageCaptions(md)
    assertEquals("\\[Image\\: alt\\]\n\n[Image: alt]: https://example.invalid/collision", out)
    val paragraph = nativeTree(out).children.first { it.type == MarkdownElementTypes.PARAGRAPH }
    val forbidden =
      setOf(
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.INLINE_LINK,
        MarkdownElementTypes.IMAGE,
        MarkdownElementTypes.EMPH,
        MarkdownElementTypes.STRONG,
      )
    assertTrue(collect(paragraph) { it.type in forbidden }.isEmpty())
    assertEquals(
      "[Image: alt]: https://example.invalid/collision",
      collect(nativeTree(out)) { it.type == MarkdownElementTypes.LINK_DEFINITION }
        .single()
        .getTextInNode(out)
        .toString(),
    )
  }

  @Test
  fun nativeAstReferenceImageAndDefinitionIdentityPreserved() {
    val md = "![ref alt][logo]\n\n[logo]: https://example.invalid/logo.png"
    assertEquals(1, nativeImages(md).size)
    val out = normalizeImageCaptions(md)
    assertEquals(0, nativeImages(out).size)
    // The LINK_DEFINITION region is byte-identical between original and normalized native AST.
    val def = collect(nativeTree(out)) { it.type == MarkdownElementTypes.LINK_DEFINITION }.single()
    assertEquals("[logo]: https://example.invalid/logo.png", src(nativeTree(out), out, def))
    val origDef =
      collect(nativeTree(md)) { it.type == MarkdownElementTypes.LINK_DEFINITION }.single()
    assertEquals(origDef.getTextInNode(md).toString(), def.getTextInNode(out).toString())
    assertEquals(md.substring(md.indexOf("\n\n")), out.substring(out.indexOf("\n\n")))
  }

  @Test
  fun nativeAstDistinctSameUrlImagesAndEmojiSourceRanges() {
    val md =
      "🦄🌈 ![first alt](https://example.invalid/x.png) middle ![second alt](https://example.invalid/x.png)"
    val origImgs = nativeImages(md)
    assertEquals(2, origImgs.size)
    assertTrue(origImgs[0].startOffset < origImgs[1].startOffset)
    // Native IMAGE source range reproduces the exact raw image syntax including astral prefix.
    assertTrue(
      md.substring(origImgs[0].startOffset, origImgs[0].endOffset).startsWith("![first alt]")
    )
    val out = normalizeImageCaptions(md)
    assertEquals(0, nativeImages(out).size)
    val root = nativeTree(out)
    assertEquals(out, src(root, out, root))
  }

  @Test
  fun nativeOwnImageRangesPreserveLineEndingsAndMultilineAlt() {
    for (separator in listOf("\n", "\r\n", "\r")) {
      val md =
        "🦄 prefix${separator}![first${separator}second](https://example.invalid/x)${separator}suffix"
      val image = nativeImages(md).single()
      assertEquals(
        "![first${separator}second](https://example.invalid/x)",
        md.substring(image.startOffset, image.endOffset),
      )
      val out = normalizeImageCaptions(md)
      assertEquals("🦄 prefix${separator}\\[Image\\: first\nsecond\\]${separator}suffix", out)
      assertEquals(0, nativeImages(out).size)
    }
  }

  @Test
  fun nativeAstCodeAndEscapedPunctuationStayLiteralAndStructurePreserved() {
    val md =
      "Run `![inline](https://example.invalid/i.png)` and \\![escaped](https://example.invalid/e.png)"
    assertEquals(0, nativeImages(md).size)
    assertEquals(md, normalizeImageCaptions(md))
  }
}
