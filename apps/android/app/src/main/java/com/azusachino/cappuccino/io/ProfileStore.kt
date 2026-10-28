package com.azusachino.cappuccino.io

import android.content.Context
import androidx.core.content.edit
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.core.Endpoint
import com.azusachino.cappuccino.ui.ThemeMode
import java.util.UUID
import kotlinx.serialization.json.Json
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

class ProfileStore(context: Context) : MachineProfileStore {
  private val preferences =
    context.applicationContext.getSharedPreferences("bridge_profiles", Context.MODE_PRIVATE)

  override fun read(): List<MachineProfile> = runCatching {
    Json.parseToJsonElement(preferences.getString(KEY, "[]")!!).jsonArray.map { value ->
      val row = value.jsonObject
      MachineProfile(
        row.getValue("id").jsonPrimitive.content,
        row.getValue("label").jsonPrimitive.content,
        Endpoint.parse(
          row.getValue("endpoint").jsonPrimitive.content,
          allowLocalHttp = BuildConfig.DEBUG,
        ),
        UUID.fromString(row.getValue("machine_id").jsonPrimitive.content),
      )
    }
  }
    .getOrDefault(emptyList())

  override fun save(profile: MachineProfile) {
    val updated = read().filterNot { it.id == profile.id } + profile
    val json = buildJsonArray {
      updated.forEach { item ->
        add(
          buildJsonObject {
            put("id", item.id)
            put("label", item.label)
            put("endpoint", item.endpoint.base.toString())
            put("machine_id", item.machineId.toString())
          }
        )
      }
    }
    preferences.edit(commit = true) { putString(KEY, json.toString()) }
    check(preferences.getString(KEY, null) == json.toString()) { "Could not save machine profile" }
  }

  override fun remove(id: String) {
    val json = buildJsonArray {
      read()
        .filterNot { it.id == id }
        .forEach { item ->
          add(
            buildJsonObject {
              put("id", item.id)
              put("label", item.label)
              put("endpoint", item.endpoint.base.toString())
              put("machine_id", item.machineId.toString())
            }
          )
        }
    }
    preferences.edit(commit = true) { putString(KEY, json.toString()) }
    check(preferences.getString(KEY, null) == json.toString()) {
      "Could not remove machine profile"
    }
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
