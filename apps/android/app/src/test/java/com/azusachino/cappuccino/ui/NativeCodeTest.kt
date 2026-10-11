package com.azusachino.cappuccino.ui

import androidx.compose.ui.graphics.luminance
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeCodeTest {
  private val directory = File("src/main/assets/code-grammars")

  private fun grammar(language: String) =
    requireNotNull(
      loadCodeGrammar(language, directory.list()?.toList().orEmpty()) {
        File(directory, it).inputStream()
      }
    )

  @Test
  fun grammarMetadataAdaptationPreservesRealCapturesAndPatterns() {
    val source =
      """{"comment":"keep root documentation","patterns":[{"begin":"(x)","beginCaptures":{"comment":"documentation URL","1":{"name":"string"}}}]}"""
    val adapted =
      kotlinx.serialization.json.Json.parseToJsonElement(codeGrammarDocument(source))
        as kotlinx.serialization.json.JsonObject
    assertEquals(
      kotlinx.serialization.json.JsonPrimitive("keep root documentation"),
      adapted["comment"],
    )
    val pattern =
      (adapted["patterns"] as kotlinx.serialization.json.JsonArray).first()
        as kotlinx.serialization.json.JsonObject
    assertEquals(kotlinx.serialization.json.JsonPrimitive("(x)"), pattern["begin"])
    assertEquals(
      kotlinx.serialization.json.Json.parseToJsonElement("""{"1":{"name":"string"}}"""),
      pattern["beginCaptures"],
    )
  }

  @Test
  fun aliasesAndInfoStringsResolveWithoutGuessingUnknownLanguages() {
    assertEquals("python", codeLanguage("py title=example"))
    assertEquals("bash", codeLanguage(" SH\tignored"))
    assertEquals("yaml", codeLanguage("yml"))
    assertEquals("rust", codeLanguage("rs"))
    assertEquals("typescriptreact", codeLanguage("tsx"))
    assertEquals("fish", codeLanguage("fish"))
    assertNull(loadCodeGrammar("fish", emptyList()) { error("Unknown grammar must not be loaded") })
    assertNull(codeLanguage(null))
  }

  @Test
  fun supportedGrammarsProduceActualColoredSpansAndPreserveExactSource() = runBlocking {
    val examples =
      mapOf(
        "bash" to "# comment\r\nprintf '%s' \"${'$'}HOME\" | grep home > out\r\n",
        "python" to "# comment\nvalue = \"hello 世界🙂\"\nif True:\n    print(value)\n",
        "json" to "{\"name\": \"世界🙂\", \"count\": 42, \"enabled\": true}\n",
        "rust" to "// comment\nfn main() { let text = \"hello\"; println!(\"{}\", text); }\n",
        "yaml" to "# comment\nname: hello\nenabled: true\ncount: 42\n",
        "typescript" to "// comment\nconst name: string = \"hello\"; console.log(name);\n",
        "swift" to "// comment\nlet name = \"hello\"\nprint(name)\n",
        "go" to "// comment\npackage main\nfunc main() { println(\"hello\") }\n",
      )
    for ((language, source) in examples) for (dark in listOf(false, true)) {
      val highlighted = highlightNativeCode(source, grammar(language), dark)
      assertEquals("Exact $language text including separators", source, highlighted.text)
      assertTrue(
        "$language must produce distinct token colors",
        highlighted.spanStyles.map { it.item.color }.distinct().size > 1,
      )
      assertTrue(
        highlighted.spanStyles.all { it.start >= 0 && it.end <= source.length && it.end > it.start }
      )
    }
  }

  @Test
  fun catppuccinTokenColorsMeetNormalTextContrast() {
    for (dark in listOf(false, true)) for (scope in
      listOf("comment", "string", "keyword", "constant", "entity.name.function", "variable", "")) {
      val a = codeScopeColor(listOf(scope), dark).luminance()
      val b = codeCanvas(dark).luminance()
      assertTrue("$scope token contrast", (maxOf(a, b) + .05f) / (minOf(a, b) + .05f) >= 4.5f)
    }
  }

  @Test
  fun structuredCommandsStayExactAndPathsAreNotCommands() {
    val source = "printf '%s' \"${'$'}HOME\"\r\n"
    val input =
      kotlinx.serialization.json
        .buildJsonObject {
          put("command", kotlinx.serialization.json.JsonPrimitive(source))
          put("shell", kotlinx.serialization.json.JsonPrimitive("/bin/bash"))
        }
        .toString()
    assertEquals(ToolContent(source, "bash"), toolCommand(input))
    assertEquals("bash", toolCommand(input)?.language)
    assertNull(toolCommand("{\"path\":\"/tmp/file.py\"}"))
    assertNull(toolCommand("{\"command\":true}"))
    assertEquals("git status", toolCommand("{\"cmd\":\"git status\"}")?.text)
    assertEquals("{\"cwd\":{}}", toolOtherArguments("{\"cmd\":\"git status\",\"cwd\":{}}"))
  }
}
