package com.azusachino.cappuccino.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.compose.elements.MarkdownCodeBackground
import dev.textmate.grammar.Grammar
import dev.textmate.grammar.raw.GrammarReader
import dev.textmate.grammar.tokenize.StateStack
import dev.textmate.regex.JoniOnigLib
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun codeLanguage(info: String?): String? {
  val name =
    info?.trim()?.takeWhile { !it.isWhitespace() }?.lowercase()?.takeIf { it.isNotEmpty() }
      ?: return null
  return when (name) {
    "bash",
    "sh",
    "zsh",
    "shell" -> "bash"
    "py" -> "python"
    "js" -> "javascript"
    "jsx" -> "javascriptreact"
    "ts" -> "typescript"
    "tsx" -> "typescriptreact"
    "rs" -> "rust"
    "yml" -> "yaml"
    "plain",
    "plaintext",
    "txt" -> "text"
    else -> name
  }
}

private val grammarFiles =
  mapOf(
    "bash" to "shell-unix-bash.tmLanguage.json",
    "python" to "MagicPython.tmLanguage.json",
    "json" to "JSON.tmLanguage.json",
    "jsonc" to "JSONC.tmLanguage.json",
    "javascript" to "JavaScript.tmLanguage.json",
    "javascriptreact" to "JavaScriptReact.tmLanguage.json",
    "typescript" to "TypeScript.tmLanguage.json",
    "typescriptreact" to "TypeScriptReact.tmLanguage.json",
    "rust" to "rust.tmLanguage.json",
    "yaml" to "yaml.tmLanguage.json",
    "swift" to "swift.tmLanguage.json",
    "go" to "go.tmLanguage.json",
  )

// VS Code YAML captures include documentation metadata. GrammarReader 0.3.0
// models capture maps as rules, so remove only string comments, never real captures.
internal fun codeGrammarDocument(source: String): String {
  fun normalize(node: JsonElement, captures: Boolean = false): JsonElement =
    when (node) {
      is JsonObject ->
        JsonObject(
          node
            .filterNot { (key, value) ->
              captures && key == "comment" && value is JsonPrimitive && value.isString
            }
            .mapValues { (key, value) ->
              normalize(
                value,
                key in setOf("captures", "beginCaptures", "endCaptures", "whileCaptures"),
              )
            }
        )
      is JsonArray -> JsonArray(node.map { normalize(it) })
      else -> node
    }
  return normalize(Json.parseToJsonElement(source)).toString()
}

internal fun loadCodeGrammar(
  language: String?,
  names: List<String>,
  open: (String) -> InputStream,
): Grammar? {
  val file = grammarFiles[codeLanguage(language)] ?: return null
  // Each job owns its mutable grammars. External includes resolve within the pinned bundle.
  val raw =
    names
      .filter { it.endsWith(".tmLanguage.json") }
      .associateWith { name ->
        open(name).bufferedReader().use {
          GrammarReader.readGrammar(codeGrammarDocument(it.readText()))
        }
      }
  val root = raw.getValue(file)
  val byScope = raw.values.associateBy { it.scopeName }
  return Grammar(root.scopeName, root, JoniOnigLib(), { byScope[it] })
}

internal fun codeCanvas(dark: Boolean): Color = if (dark) Color(0xFF181825) else Color(0xFFE6E9EF)

internal fun codeScopeColor(scopes: List<String>, dark: Boolean): Color {
  val scope =
    scopes
      .asReversed()
      .firstOrNull { s ->
        listOf(
            "comment",
            "string",
            "keyword",
            "storage",
            "constant",
            "entity.name",
            "support",
            "variable",
          )
          .any { s.startsWith(it) }
      }
      .orEmpty()
  var color =
    when {
      scope.startsWith("comment") -> if (dark) Color(0xFFA6ADC8) else Color(0xFF5C5F77)
      scope.startsWith("string") -> if (dark) Color(0xFFA6E3A1) else Color(0xFF40A02B)
      scope.startsWith("keyword") || scope.startsWith("storage") ->
        if (dark) Color(0xFFCBA6F7) else Color(0xFF8839EF)
      scope.startsWith("constant") -> if (dark) Color(0xFFFAB387) else Color(0xFFFE640B)
      scope.startsWith("entity.name") || scope.startsWith("support") ->
        if (dark) Color(0xFF89B4FA) else Color(0xFF1E66F5)
      scope.startsWith("variable") -> if (dark) Color(0xFFF38BA8) else Color(0xFFD20F39)
      else -> if (dark) Color(0xFFCDD6F4) else Color(0xFF4C4F69)
    }
  // Latte's raw pastel accents do not all meet normal-text contrast on its canvas.
  val canvas = codeCanvas(dark).luminance()
  while (
    (maxOf(color.luminance(), canvas) + 0.05f) / (minOf(color.luminance(), canvas) + 0.05f) < 4.5f
  ) {
    color = color.copy(red = color.red * 0.9f, green = color.green * 0.9f, blue = color.blue * 0.9f)
  }
  return color
}

internal suspend fun highlightNativeCode(
  code: String,
  grammar: Grammar,
  dark: Boolean,
): AnnotatedString {
  val result = AnnotatedString.Builder(code)
  var state: StateStack? = null
  var offset = 0
  // Preserve CRLF, bare CR, Unicode and trailing separators in the original string.
  val breaks = Regex("\\r\\n|\\r|\\n").findAll(code)
  for (end in
    breaks.map { it.range.first to (it.range.last + 1) } + sequenceOf(code.length to code.length)) {
    currentCoroutineContext().ensureActive()
    val line = code.substring(offset, end.first)
    val tokens = grammar.tokenizeLine(line, state)
    state = tokens.ruleStack
    for (token in tokens.tokens) {
      val start = token.startIndex.coerceIn(0, line.length)
      val finish = token.endIndex.coerceIn(start, line.length)
      if (finish > start)
        result.addStyle(
          SpanStyle(color = codeScopeColor(token.scopes, dark)),
          offset + start,
          offset + finish,
        )
    }
    offset = end.second
  }
  return result.toAnnotatedString()
}

private val codeHighlightJobs = Semaphore(2)

private data class HighlightedBlock(
  val source: String,
  val language: String?,
  val dark: Boolean,
  val text: AnnotatedString,
)

@Composable
internal fun NativeCodeBlock(code: String, language: String?, modifier: Modifier = Modifier) {
  val assets = LocalContext.current.assets
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val canonical = codeLanguage(language)
  val highlighted by
    produceState<HighlightedBlock?>(null, code, canonical, dark, assets) {
      value =
        withContext(Dispatchers.Default) {
          codeHighlightJobs.withPermit {
            val grammar =
              loadCodeGrammar(canonical, assets.list("code-grammars")?.toList().orEmpty()) {
                assets.open("code-grammars/$it")
              }
            HighlightedBlock(
              code,
              canonical,
              dark,
              if (grammar == null) AnnotatedString(code)
              else highlightNativeCode(code, grammar, dark),
            )
          }
        }
    }
  val rendered =
    highlighted?.takeIf { it.source == code && it.language == canonical && it.dark == dark }?.text
      ?: AnnotatedString(code)
  val label = language?.trim()?.takeIf { it.isNotEmpty() } ?: "Code"
  MarkdownCodeBackground(
    color = codeCanvas(dark),
    modifier = modifier.fillMaxWidth().padding(vertical = 8.dp),
    showHeader = true,
    language = label,
    code = code,
  ) {
    Text(
      rendered,
      Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
      color = codeScopeColor(emptyList(), dark),
      fontFamily = FontFamily.Monospace,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
