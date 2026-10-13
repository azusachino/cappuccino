package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.JsonObject
import com.azusachino.cappuccino.core.JsonValue
import com.azusachino.cappuccino.core.ProtocolException
import com.azusachino.cappuccino.core.parsePaired
import java.util.UUID

/** Metadata from a verified handshake. Missing advertisement means unknown, not unsupported. */
data class BridgeDetails(
  val machineId: UUID,
  val version: String?,
  val protocol: Long,
  val capabilities: Map<String, Boolean>?,
)

internal fun parseBridgeDetails(json: JsonObject): BridgeDetails {
  val machine = parsePaired(json)
  val capabilities =
    when (val value = json.fields["capabilities"]) {
      null,
      JsonValue.Null -> null
      is JsonValue.Object ->
        value.value.fields.mapValues { (key, flag) ->
          (flag as? JsonValue.BooleanValue)?.value
            ?: throw ProtocolException("Invalid bridge capability: $key")
        }
      else -> throw ProtocolException("Invalid bridge capabilities")
    }
  return BridgeDetails(
    machine,
    json.optionalString("bridge_version"),
    json.long("protocol"),
    capabilities,
  )
}
