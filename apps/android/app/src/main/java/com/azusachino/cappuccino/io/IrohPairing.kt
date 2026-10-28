package com.azusachino.cappuccino.io

import com.azusachino.cappuccino.core.JsonObject
import com.azusachino.cappuccino.core.JsonValue
import com.azusachino.cappuccino.core.ProtocolException
import com.azusachino.cappuccino.core.parseJsonObject

/** A parsed pairing payload v1: where the bridge endpoint is and the token to present. */
data class IrohPairing(val endpoint: IrohEndpointAddr, val token: String)

private const val IROH_PAIRING_VERSION = 1L

/**
 * Parses `{"v":1,"endpoint_id","relay","addrs","token"}`. Any other version is rejected so an old
 * app never guesses at a newer payload's meaning.
 */
fun parseIrohPairing(text: String): IrohPairing {
  val json = parseJsonObject(text)
  if (json.long("v") != IROH_PAIRING_VERSION) throw ProtocolException("Unsupported pairing version")
  val endpointId = json.string("endpoint_id")
  val token = json.string("token")
  if (endpointId.isEmpty() || token.isEmpty()) throw ProtocolException("Empty pairing field")
  val addrs =
    (json.fields["addrs"] as? JsonValue.Array)?.value?.map {
      (it as? JsonValue.StringValue)?.value ?: throw ProtocolException("Invalid pairing addr")
    } ?: throw ProtocolException("Invalid addrs")
  return IrohPairing(
    endpoint = IrohEndpointAddr(endpointId, relayUrl(json), addrs),
    token = token,
  )
}

/** The bridge sends `null` until its endpoint has a home relay; the key itself is required. */
private fun relayUrl(json: JsonObject): String? =
  when (val value = json.fields["relay"]) {
    JsonValue.Null -> null
    is JsonValue.StringValue ->
      value.value.ifEmpty { throw ProtocolException("Empty pairing relay") }
    else -> throw ProtocolException("Invalid relay")
  }
