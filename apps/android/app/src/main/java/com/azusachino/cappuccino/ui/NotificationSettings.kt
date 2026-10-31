package com.azusachino.cappuccino.ui

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
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
    preferences.permissionRequested = true
    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
  SettingRow(
    "Agent notifications",
    detail = "When an agent finishes or needs your answer",
    icon = {
      Icon(
        painterResource(R.drawable.ic_notification),
        contentDescription = null,
        tint = MaterialTheme.appColors.accent,
        modifier = Modifier.size(20.dp),
      )
    },
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
  if (enabled && !granted) {
    SettingsDivider()
    SettingRow(
      "Android permission",
      value = "Not allowed",
      warning = true,
      icon = {
        Icon(
          painterResource(R.drawable.ic_attention),
          contentDescription = null,
          tint = MaterialTheme.appColors.warn,
          modifier = Modifier.size(20.dp),
        )
      },
      onClick = ::requestPermission,
    )
  }
  SettingsDivider()
  SettingRow(
    "System notification settings",
    icon = {
      Icon(
        painterResource(R.drawable.ic_settings),
        contentDescription = null,
        tint = MaterialTheme.appColors.muted,
        modifier = Modifier.size(20.dp),
      )
    },
    onClick = {
      context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
          .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
      )
    },
  )
}
