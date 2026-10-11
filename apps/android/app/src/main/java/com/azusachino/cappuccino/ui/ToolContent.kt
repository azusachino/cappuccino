package com.azusachino.cappuccino.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal data class ToolContent(val text: String, val language: String)

private val prettyJson = Json { prettyPrint = true }

internal fun formatToolContent(text: String, language: String = "text"): ToolContent =
  try {
    ToolContent(
      prettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text)),
      "json",
    )
  } catch (_: IllegalArgumentException) {
    ToolContent(text, language)
  }

internal fun toolCommand(input: String): ToolContent? {
  val fields =
    try {
      Json.parseToJsonElement(input) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
  val command =
    listOf("command", "CommandLine", "cmd").firstNotNullOfOrNull { key ->
      (fields[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    } ?: return null
  val shell =
    (fields["shell"] as? JsonPrimitive)
      ?.takeIf { it.isString }
      ?.content
      ?.substringAfterLast('/')
      ?.takeIf { it.isNotBlank() } ?: "shell"
  return ToolContent(command, shell)
}

internal fun toolSummary(input: String): String {
  val fields =
    try {
      Json.parseToJsonElement(input) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    }
  return listOf("command", "CommandLine", "cmd", "path", "file_path", "pattern", "query", "url")
    .firstNotNullOfOrNull { key ->
      (fields?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    } ?: input
}

internal fun toolOtherArguments(input: String): String? {
  val fields =
    try {
      Json.parseToJsonElement(input) as? JsonObject
    } catch (_: IllegalArgumentException) {
      null
    } ?: return null
  val summary = toolSummary(input)
  val key =
    listOf("command", "CommandLine", "cmd", "path", "file_path", "pattern", "query", "url")
      .firstOrNull {
        (fields[it] as? JsonPrimitive)?.takeIf { value -> value.isString }?.content == summary
      }
  val remaining = fields.filterKeys { it != key }
  return if (remaining.isEmpty()) null else JsonObject(remaining).toString()
}

internal fun toolErrorPreview(text: String): String {
  if (toolPreview(text) == text) return text
  val count = text.codePointCount(0, text.length)
  val head =
    text
      .substring(0, text.offsetByCodePoints(0, minOf(count, 2000)))
      .lines()
      .take(5)
      .joinToString("\n")
  val tail =
    text
      .substring(text.offsetByCodePoints(text.length, -minOf(count, 2000)))
      .lines()
      .takeLast(6)
      .joinToString("\n")
  return "$head\n…\n$tail"
}

internal fun toolPreview(text: String): String {
  val lines = text.lineSequence().take(12).joinToString("\n")
  val end = lines.offsetByCodePoints(0, minOf(4000, lines.codePointCount(0, lines.length)))
  return lines.substring(0, end)
}

/** A fence longer than any source run keeps tool output literal, including embedded Markdown. */
internal fun fencedToolContent(content: ToolContent): String {
  val longest =
    content.text.lineSequence().maxOfOrNull { line ->
      line.trimStart().takeWhile { it == '`' }.length
    } ?: 0
  val fence = "`".repeat(maxOf(3, longest + 1))
  return "$fence${content.language}\n${content.text}\n$fence"
}
