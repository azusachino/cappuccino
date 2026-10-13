package com.azusachino.cappuccino.io

import android.content.Context
import androidx.core.content.edit

internal class NotificationPreferences(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences("agent_notifications", Context.MODE_PRIVATE)
  var enabled: Boolean
    get() = preferences.getBoolean("enabled", true)
    set(value) {
      preferences.edit(commit = true) { putBoolean("enabled", value) }
    }

  var permissionRequested: Boolean
    get() = preferences.getBoolean("permission_requested", false)
    set(value) {
      preferences.edit(commit = true) { putBoolean("permission_requested", value) }
    }

  fun wasNotified(key: String): Boolean =
    key in (preferences.getStringSet("notified", emptySet()) ?: emptySet())

  @Synchronized
  fun recordNotified(key: String) {
    val keys =
      ((preferences.getStringSet("notified", emptySet()) ?: emptySet()).toList() + key)
        .takeLast(2048)
        .toSet()
    preferences.edit(commit = true) { putStringSet("notified", keys) }
  }

  fun paused(): Set<String> = preferences.getStringSet("paused", emptySet())!!.toSet()

  fun setPaused(id: String, paused: Boolean) {
    val next = if (paused) paused() + id else paused() - id
    preferences.edit(commit = true) { putStringSet("paused", next) }
  }
}
