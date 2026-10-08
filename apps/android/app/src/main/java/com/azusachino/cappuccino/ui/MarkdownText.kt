package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text as MarkdownLiteral
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private val markdownParser = Parser.builder().build()

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
  val document = remember(text) { markdownParser.parse(text) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    document.children().forEach { MarkdownBlock(it, 0) }
  }
}

private fun Node.children(): Sequence<Node> = generateSequence(firstChild) { it.next }

@Composable
private fun MarkdownBlock(node: Node, depth: Int) {
  // Keep adversarial nesting off the Compose call stack without discarding its text.
  if (depth >= 32) {
    Text(plainMarkdownText(node), style = MaterialTheme.typography.bodyMedium)
    return
  }
  when (node) {
    is Heading ->
      Text(
        markdownInlineText(node, MaterialTheme.colorScheme.surface),
        modifier = Modifier.semantics { heading() }.padding(top = 4.dp),
        style =
          when (node.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            else -> MaterialTheme.typography.titleMedium
          },
        fontWeight = FontWeight.Bold,
      )
    is Paragraph ->
      Text(
        markdownInlineText(node, MaterialTheme.colorScheme.surface),
        style = MaterialTheme.typography.bodyMedium,
      )
    is FencedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'))
    is IndentedCodeBlock -> MarkdownCode(node.literal.trimEnd('\n'))
    is HtmlBlock -> MarkdownCode(node.literal.trimEnd('\n'))
    is ThematicBreak -> HorizontalDivider()
    is BulletList,
    is OrderedList ->
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        node.children().forEachIndexed { index, item ->
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val marker =
              if (node is OrderedList) "${(node.markerStartNumber ?: 1) + index}." else "•"
            Text(marker, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
              item.children().forEach { MarkdownBlock(it, depth + 1) }
            }
          }
        }
      }
    is BlockQuote ->
      Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(6.dp),
      ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          node.children().forEach { MarkdownBlock(it, depth + 1) }
        }
      }
    else -> Text(plainMarkdownText(node), style = MaterialTheme.typography.bodyMedium)
  }
}

@Composable
private fun MarkdownCode(text: String) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(6.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text,
      modifier = Modifier.padding(8.dp),
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

internal fun markdownInlineText(
  node: Node,
  codeBackground: Color = Color.Unspecified,
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
        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
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
      else -> current.children().toList().asReversed().forEach { pending.addLast(it) }
    }
  }
}
