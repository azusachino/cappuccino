package com.azusachino.cappuccino.io

import android.content.Context
import androidx.core.content.edit
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.ui.ThemeMode
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class MachineProfile(
  val id: String,
  val label: String,
  val endpoint: Endpoint,
  val machineId: UUID,
)

interface MachineProfileStore {
  fun read(): List<MachineProfile>

  fun save(profile: MachineProfile)

  fun remove(id: String)

  fun readThemeMode(): ThemeMode

  fun saveThemeMode(mode: ThemeMode)
}

/**
 * The stored profile list as JSON. Tailnet rows keep the original shape (no `type`), so profiles
 * saved before iroh decode unchanged and re-encode byte-for-byte. Iroh rows add `"type":"iroh"` and
 * an `iroh` object; their `endpoint` is the placeholder host and is not read back.
 */
internal object MachineProfileCodec {
  fun encode(profiles: List<MachineProfile>): String = buildJsonArray {
    profiles.forEach { profile ->
      add(
        buildJsonObject {
          put("id", profile.id)
          put("label", profile.label)
          put("endpoint", profile.endpoint.base.toString())
          put("machine_id", profile.machineId.toString())
          profile.endpoint.iroh?.let { addr ->
            put("type", IROH_TYPE)
            put(
              "iroh",
              buildJsonObject {
                put("endpoint_id", addr.endpointId)
                put("relay", addr.relayUrl?.let { JsonPrimitive(it) } ?: JsonNull)
                put(
                  "addrs",
                  buildJsonArray { addr.directAddrs.forEach { add(JsonPrimitive(it)) } },
                )
              },
            )
          }
        }
      )
    }
  }
    .toString()

  fun decode(text: String, allowLocalHttp: Boolean): List<MachineProfile> =
    Json.parseToJsonElement(text).jsonArray.map { value ->
      val row = value.jsonObject
      val endpoint =
        when (row["type"]?.jsonPrimitive?.content) {
          null -> Endpoint.parse(row.string("endpoint"), allowLocalHttp)
          IROH_TYPE -> Endpoint.iroh(irohAddr(row.getValue("iroh").jsonObject))
          else -> throw IllegalArgumentException("Unknown machine type")
        }
      MachineProfile(
        row.string("id"),
        row.string("label"),
        endpoint,
        UUID.fromString(row.string("machine_id")),
      )
    }

  private fun irohAddr(row: JsonObject): IrohEndpointAddr =
    IrohEndpointAddr(
      endpointId = row.string("endpoint_id"),
      relayUrl = (row.getValue("relay") as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content,
      directAddrs =
        row.getValue("addrs").jsonArray.map(JsonElement::jsonPrimitive).map { it.content },
    )

  private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

  private const val IROH_TYPE = "iroh"
}

class ProfileStore(context: Context) : MachineProfileStore {
  private val preferences =
    context.applicationContext.getSharedPreferences("bridge_profiles", Context.MODE_PRIVATE)

  override fun read(): List<MachineProfile> = runCatching {
    MachineProfileCodec.decode(
      preferences.getString(KEY, "[]")!!,
      allowLocalHttp = BuildConfig.DEBUG,
    )
  }
    .getOrDefault(emptyList())

  override fun save(profile: MachineProfile) {
    write(read().filterNot { it.id == profile.id } + profile, "Could not save machine profile")
  }

  override fun remove(id: String) {
    write(read().filterNot { it.id == id }, "Could not remove machine profile")
  }

  private fun write(profiles: List<MachineProfile>, failure: String) {
    val json = MachineProfileCodec.encode(profiles)
    preferences.edit(commit = true) { putString(KEY, json) }
    check(preferences.getString(KEY, null) == json) { failure }
  }

  override fun readThemeMode(): ThemeMode = runCatching {
    val name = preferences.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
    ThemeMode.valueOf(name ?: ThemeMode.SYSTEM.name)
  }
    .getOrDefault(ThemeMode.SYSTEM)

  override fun saveThemeMode(mode: ThemeMode) {
    preferences.edit(commit = true) { putString(KEY_THEME_MODE, mode.name) }
  }

  companion object {
    private const val KEY = "profiles"
    private const val KEY_THEME_MODE = "theme_mode"
  }
}
