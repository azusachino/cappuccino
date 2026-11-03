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
  /** The bridge's Telegram route (`enabled`, `failing`, ...), or null when not advertised. */
  val telegramRoute: String? = null,
  /** Event names the bridge's Telegram route sends, such as `completion`. */
  val telegramEvents: List<String> = emptyList(),
)

/**
 * True when the machine's bridge is delivering this event to Telegram right now. The phone then
 * skips its own alert for it, so the owner gets one message, not two.
 */
internal fun telegramCovers(details: BridgeDetails?, event: String): Boolean =
  details != null && details.telegramRoute == "enabled" && event in details.telegramEvents

internal fun parseBridgeDetails(json: JsonObject): BridgeDetails {
  val machine = parsePaired(json)
  val telegram = json.optionalObject("telegram")
  val telegramEvents =
    ((telegram?.fields?.get("events") as? JsonValue.Array)?.value ?: emptyList()).mapNotNull {
      (it as? JsonValue.StringValue)?.value
    }
  // The bridge offers each capability as name -> version. A capability is usable only when it is
  // also agreed, so the map answers true for agreed names, false for offered-but-not-agreed ones.
  val offered =
    (json.fields["capabilities"] as? JsonValue.Object)?.value?.fields?.keys
      ?: throw ProtocolException("Invalid bridge capabilities")
  val agreed = (json.fields["agreed"] as? JsonValue.Object)?.value?.fields?.keys.orEmpty()
  val capabilities = offered.associateWith { it in agreed }
  return BridgeDetails(
    machine,
    json.optionalString("bridge_version"),
    json.long("protocol"),
    capabilities,
    telegramRoute = telegram?.optionalString("route"),
    telegramEvents = telegramEvents,
  )
}
