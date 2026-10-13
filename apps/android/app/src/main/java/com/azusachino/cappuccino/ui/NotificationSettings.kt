package com.azusachino.cappuccino.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.azusachino.cappuccino.io.AgentNotificationService
import com.azusachino.cappuccino.io.NotificationPreferences

@Composable
internal fun NotificationSettings() {
  val context = LocalContext.current
  val preferences = remember { NotificationPreferences(context) }
  var enabled by remember { mutableStateOf(preferences.enabled) }
  var granted by remember { mutableStateOf(AgentNotificationService.permissionGranted(context)) }
  val permission =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
      granted = it
      if (it && enabled) AgentNotificationService.start(context)
    }
  fun requestPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      preferences.permissionRequested = true
      permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
  SettingRow(
    "Agent notifications",
    detail = "When an agent finishes or needs your answer",
    trailing = {
      Switch(
        checked = enabled,
        onCheckedChange = {
          enabled = it
          preferences.enabled = it
          if (!it) context.stopService(Intent(context, AgentNotificationService::class.java))
          else if (granted) AgentNotificationService.start(context) else requestPermission()
        },
      )
    },
    onClick = null,
  )
  if (enabled && !granted)
    SettingRow(
      "Android permission",
      value = "Not allowed",
      warning = true,
      onClick = ::requestPermission,
    )
  SettingRow(
    "System notification settings",
    onClick = {
      context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      )
    },
  )
}
