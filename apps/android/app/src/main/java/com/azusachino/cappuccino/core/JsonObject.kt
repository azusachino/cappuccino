package com.azusachino.cappuccino.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as SerializationObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Small typed view of JSON values used by the protocol parser. */
data class JsonObject(val fields: Map<String, JsonValue>) {
  fun string(key: String): String =
    (fields[key] as? JsonValue.StringValue)?.value ?: throw ProtocolException("Invalid $key")

  fun optionalString(key: String): String? =
    when (val value = fields[key]) {
      null,
      JsonValue.Null -> null
      is JsonValue.StringValue -> value.value
      else -> throw ProtocolException("Invalid $key")
    }

  fun long(key: String): Long =
    (fields[key] as? JsonValue.Number)?.value ?: throw ProtocolException("Invalid $key")

  fun boolean(key: String): Boolean =
    (fields[key] as? JsonValue.BooleanValue)?.value ?: throw ProtocolException("Invalid $key")

  fun array(key: String): List<JsonObject> =
    ((fields[key] as? JsonValue.Array)?.value ?: throw ProtocolException("Invalid $key")).map {
      (it as? JsonValue.Object)?.value ?: throw ProtocolException("Invalid $key row")
    }
}

sealed interface JsonValue {
  data class Object(val value: JsonObject) : JsonValue

  data class Array(val value: List<JsonValue>) : JsonValue

  data class StringValue(val value: String) : JsonValue

  data class Number(val value: Long) : JsonValue

  data class BooleanValue(val value: Boolean) : JsonValue

  data object Null : JsonValue
}

fun parseJsonObject(text: String): JsonObject {
  if (text.toByteArray(Charsets.UTF_8).size > MAX_HTTP_BODY_BYTES)
    throw ProtocolException("HTTP body exceeds local limit")
  return (parseJson(text) as? JsonValue.Object)?.value
    ?: throw ProtocolException("Expected JSON object")
}

fun validateWebSocketMessage(text: String) {
  if (text.toByteArray(Charsets.UTF_8).size > MAX_WS_MESSAGE_BYTES)
    throw ProtocolException("WebSocket message exceeds local limit")
}

private fun parseJson(text: String): JsonValue =
  try {
    Json.parseToJsonElement(text).toValue()
  } catch (error: ProtocolException) {
    throw error
  } catch (_: Exception) {
    throw ProtocolException("Malformed JSON")
  }

private fun JsonElement.asObject(): JsonObject =
  (this as? SerializationObject)?.let { objectValue ->
    JsonObject(objectValue.mapValues { (_, value) -> value.toValue() })
  } ?: throw ProtocolException("Expected JSON object")

private fun JsonElement.toValue(): JsonValue =
  when (this) {
    is SerializationObject -> JsonValue.Object(asObject())
    is kotlinx.serialization.json.JsonArray -> JsonValue.Array(map { it.toValue() })
    JsonNull -> JsonValue.Null
    is JsonPrimitive ->
      when {
        isString -> JsonValue.StringValue(content)
        booleanOrNull != null -> JsonValue.BooleanValue(booleanOrNull!!)
        longOrNull != null -> JsonValue.Number(longOrNull!!)
        else -> throw ProtocolException("Unsupported JSON value")
      }
  }
