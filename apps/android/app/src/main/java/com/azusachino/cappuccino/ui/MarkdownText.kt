package com.azusachino.cappuccino.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.mikepenz.markdown.annotator.annotatorSettings
import com.mikepenz.markdown.compose.Markdown
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.NoOpImageTransformerImpl
import com.mikepenz.markdown.model.rememberMarkdownState
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.parser.Parser
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

private val markdownParser = Parser.builder().build()

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

// The renderer's default engine: markdown-jvm 0.7.9, GFMFlavourDescriptor.
// Its public ASTNode startOffset/endOffset are UTF-16 offsets into the original source.
private val imageSpanParser =
  MarkdownParser(GFMFlavourDescriptor(), false, CancellationToken.NonCancellable)

/**
 * Escapes a plain-text image caption with standard CommonMark backslash escaping of every active
 * ASCII punctuation character (per CommonMark §2.4 "Backslash escapes": all ASCII punctuation), so
 * the caption is inert literal text: it cannot inject Markdown links, emphasis (`* _ ~`), table
 * pipes (`|`), HTML (`<`), code, or extend the `[Image: ...]` literal with brackets. The escaped
 * literal re-parses as plain text with the exact original characters visible.
 */
internal fun escapeImageCaption(alt: String): String {
  if (alt.isEmpty()) return alt
  val out = StringBuilder(alt.length + 16)
  for (ch in alt) {
    if (ch == '\\' || ch in '!'..'/' || ch in ':'..'@' || ch in '['..'`' || ch in '{'..'~') {
      out.append('\\')
    }
    out.append(ch)
  }
  return out.toString()
}

/**
 * Rewrites only outermost IMAGE ranges recognized by the native renderer's own GFM grammar. Every
 * other source byte remains unchanged, including invalid image syntax in table cells. ALT comes
 * from this IMAGE's own LINK_TEXT, never a paragraph, URL map or counter. The retained CommonMark
 * utility flattens only that local ALT; it has no authority over document ranges. Escape the whole
 * generated literal, including its wrapper, so reference definitions cannot turn captions into
 * links. Code and incomplete streaming syntax remain native literal text.
 */
internal fun normalizeImageCaptions(document: String): String {
  if (!document.contains("![")) return document

  val edits = mutableListOf<Triple<Int, Int, String>>()
  val stack = ArrayDeque<ASTNode>()
  stack.addLast(imageSpanParser.buildMarkdownTreeFromString(document as CharSequence))
  while (stack.isNotEmpty()) {
    val node = stack.removeLast()
    if (node.type == MarkdownElementTypes.IMAGE) {
      val own = ArrayDeque<ASTNode>()
      own.addLast(node)
      var alt: String? = null
      while (own.isNotEmpty()) {
        val child = own.removeFirst()
        if (child.type == MarkdownElementTypes.LINK_TEXT) {
          val raw = document.substring(child.startOffset + 1, child.endOffset - 1)
          alt = plainMarkdownText(markdownParser.parse(raw))
          break
        }
        own.addAll(child.children)
      }
      edits.add(
        Triple(
          node.startOffset,
          node.endOffset,
          escapeImageCaption("[Image: ${checkNotNull(alt)}]"),
        )
      )
      // Never descend into an outer image: nested image ranges would overlap its replacement.
      continue
    }
    node.children.forEach { stack.addLast(it) }
  }
  if (edits.isEmpty()) return document

  val out = StringBuilder(document.length)
  var cursor = 0
  for ((start, end, literal) in edits.sortedBy { it.first }) {
    out.append(document, cursor, start)
    out.append(literal)
    cursor = end
  }
  out.append(document, cursor, document.length)
  return out.toString()
}

/**
 * Global passive boundary: every node rendered through [MarkdownText] sees this handler, so link
 * interactions can never launch ACTION_VIEW and no image transformer ever fetches (F-01 policy,
 * carried over unchanged from the production renderer this replaces).
 */
object NoOpUriHandler : UriHandler {
  override fun openUri(uri: String) {
    // Passive: never navigate, never fetch.
  }
}

/**
 * Production Markdown boundary. Document rendering delegates to the pinned MikePenz 0.45.0 native
 * M3 renderer (headings, lists, nested blocks, quotes, code, tables) inside the global
 * [NoOpUriHandler] scope with [NoOpImageTransformerImpl], so all content stays passive.
 *
 * Native-recognized images become escaped literal captions before rendering. Paragraphs and tables
 * additionally drop the library's default link listener; all other blocks remain inside the global
 * no-navigation boundary.
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, compact: Boolean = true) {
  // Input-keyed memoization: the pre-pass is pure in [text], so recompositions reuse the
  // normalized string without re-parsing (stable transformer/components inputs likewise).
  val renderingText = remember(text) { normalizeImageCaptions(text) }
  // Keep the last measured body while an update parses; clearing input is an
  // explicit reset and must not retain the previous conversation.
  val markdownState =
    rememberMarkdownState(content = renderingText, retainState = renderingText.isNotEmpty())
  val components = remember { passiveMarkdownComponents() }
  val imageTransformer = remember { NoOpImageTransformerImpl() }
  CompositionLocalProvider(LocalUriHandler provides NoOpUriHandler) {
    Markdown(
      markdownState = markdownState,
      colors =
        markdownColor(
          text = MaterialTheme.appColors.body,
          codeBackground = MaterialTheme.appColors.codeBackground,
          inlineCodeBackground = MaterialTheme.appColors.card2,
          dividerColor = MaterialTheme.appColors.line,
        ),
      typography = appMarkdownTypography(compact),
      modifier = modifier,
      imageTransformer = imageTransformer,
      components = components,
    )
  }
}

/**
 * The library's inline-image component models do not carry own-image identity: the model's `node`
 * is the ENCLOSING PARAGRAPH (0.45.0 `buildImageInlineContent`/`MarkdownInlineImageWithSize`, lines
 * 345-363/384-386), so `resolveImageAlt` against it returns that paragraph's FIRST `LINK_TEXT` —
 * wrong for a paragraph holding an ordinary link before an image, and identical for two same-URL
 * images in one paragraph. Instead of patching that unfixable model, complete images are replaced
 * before rendering by [normalizeImageCaptions], so the library never builds real `Image` inline
 * content for them: no image component override is needed, no image hook can mis-resolve, and no
 * fetch path exists ([NoOpImageTransformerImpl] retained as the global transformer boundary).
 */
internal fun passiveMarkdownComponents() =
  markdownComponents(
    paragraph = { model ->
      MarkdownParagraph(
        content = model.content,
        node = model.node,
        annotatorSettings = annotatorSettings(linkInteractionListener = null),
      )
    },
    table = { model ->
      MarkdownTable(
        content = model.content,
        node = model.node,
        style = model.typography.text,
        annotatorSettings = annotatorSettings(linkInteractionListener = null),
      )
    },
    codeFence = { model ->
      MarkdownCodeFence(content = model.content, node = model.node) { code, language, _ ->
        NativeCodeBlock(code, language)
      }
    },
    codeBlock = { model ->
      MarkdownCodeBlock(content = model.content, node = model.node) { code, language, _ ->
        NativeCodeBlock(code, language)
      }
    },
  )

/**
 * Retained pure inline flattener: the documented passive-text semantics (ALT text, no annotations,
 * bounded depth) exercised by the preserved unit contract. The library owns all production document
 * rendering; this is a flattening utility, not a second renderer.
 */
internal fun markdownInlineText(
  node: Node,
  codeBackground: androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color.Unspecified,
): AnnotatedString = buildAnnotatedString {
  fun appendNode(current: Node, depth: Int) {
    if (depth >= 32) {
      append(plainMarkdownText(current))
      return
    }
    fun children() {
      current.children().forEach { appendNode(it, depth + 1) }
    }
    when (current) {
      is MarkdownLiteral -> append(current.literal)
      is Code ->
        withStyle(SpanStyle(fontFamily = JetBrainsMono, background = codeBackground)) {
          append(current.literal)
        }
      is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { children() }
      is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { children() }
      is SoftLineBreak -> append(' ')
      is HardLineBreak -> append('\n')
      is HtmlInline -> append(current.literal)
      // Links/images are passive text: no automatic navigation, HTML or remote image fetches.
      is Link -> withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { children() }
      is Image -> {
        append("[Image: ")
        children()
        append(']')
      }
      else -> children()
    }
  }
  appendNode(node, 0)
}

internal fun plainMarkdownText(node: Node): String = buildString {
  val pending = ArrayDeque<Node>()
  pending.addLast(node)
  while (pending.isNotEmpty()) {
    val current = pending.removeLast()
    when (current) {
      is MarkdownLiteral -> append(current.literal)
      is Code -> append(current.literal)
      is HtmlInline -> append(current.literal)
      is HtmlBlock -> append(current.literal)
      is FencedCodeBlock -> append(current.literal)
      is IndentedCodeBlock -> append(current.literal)
      is SoftLineBreak,
      is HardLineBreak -> append('\n')
      else ->
        generateSequence(current.firstChild) { it.next }
          .toList()
          .asReversed()
          .forEach { pending.addLast(it) }
    }
  }
}

// Every block role is set: unset ones fall back to the library's bodyLarge/display sizes.
@Composable
private fun appMarkdownTypography(compact: Boolean) =
  with(MaterialTheme.typography) {
    val text = if (compact) bodyMedium else bodyLarge
    val heading = text.copy(fontWeight = FontWeight.Bold, color = MaterialTheme.appColors.text)
    markdownTypography(
      h1 = heading,
      h2 = heading,
      h3 = heading,
      h4 = heading,
      h5 = heading,
      h6 = heading,
      text = text,
      code = bodySmall,
      inlineCode =
        text.copy(fontSize = text.fontSize * 0.92f, color = MaterialTheme.appColors.text),
      quote = text.copy(color = MaterialTheme.appColors.muted),
      paragraph = text,
      ordered = text,
      bullet = text,
      list = text,
      textLink =
        androidx.compose.ui.text.TextLinkStyles(
          style =
            SpanStyle(
              color = MaterialTheme.appColors.accent,
              textDecoration = TextDecoration.Underline,
            )
        ),
      table = bodySmall,
      alertTitle = text.copy(fontWeight = FontWeight.Bold),
    )
  }
