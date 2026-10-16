package com.azusachino.cappuccino.io

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.azusachino.cappuccino.MainActivity
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.AgentCompletions
import com.azusachino.cappuccino.ui.agentIconResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Independent read-only connections to configured remote machines, never agent lifecycle/input. */
class AgentNotificationService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val jobs = mutableMapOf<String, Pair<MachineProfile, Job>>()

  @Volatile private var lastActivityTimestamp = System.currentTimeMillis()

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onTaskRemoved(rootIntent: Intent?) {
    stopSelf()
    super.onTaskRemoved(rootIntent)
  }

  override fun onCreate() {
    super.onCreate()
    lastActivityTimestamp = System.currentTimeMillis()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(MONITOR, "Agent monitoring", NotificationManager.IMPORTANCE_LOW)
    )
    manager.createNotificationChannel(
      NotificationChannel(
        ALERTS,
        "Agent completion and input",
        NotificationManager.IMPORTANCE_DEFAULT,
      )
    )
    startForeground(
      1,
      NotificationCompat.Builder(this, MONITOR)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Cappuccino agent monitoring")
        .setContentText("Watching configured machines for completion and input requests")
        .setContentIntent(openApp())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build(),
    )
    scope.launch {
      val store = ProfileStore(this@AgentNotificationService)
      val preferences = NotificationPreferences(this@AgentNotificationService)
      while (isActive) {
        if (
          !preferences.enabled ||
            !permissionGranted(this@AgentNotificationService) ||
            !NotificationManagerCompat.from(this@AgentNotificationService).areNotificationsEnabled()
        ) {
          stopSelf()
          break
        }
        val profiles = store.read().filter { it.id !in preferences.paused() }.associateBy { it.id }
        if (
          profiles.isEmpty() || System.currentTimeMillis() - lastActivityTimestamp > IDLE_TIMEOUT_MS
        ) {
          stopSelf()
          break
        }
        jobs.keys.toList().forEach { id ->
          if (profiles[id] != jobs[id]?.first) {
            jobs.remove(id)?.second?.cancel()
          }
        }
        profiles.values.forEach { profile ->
          if (profile.id !in jobs) {
            jobs[profile.id] =
              profile to
                scope.launch {
                  val client = BridgeClient(profile.endpoint)
                  val completions = AgentCompletions()
                  val blocked = mutableMapOf<String, String>()
                  var consecutiveErrors = 0
                  while (isActive) {
                    var anyActive = false
                    try {
                      client.verify(profile.machineId)
                      val agents = client.agents(profile.machineId)
                      consecutiveErrors = 0
                      anyActive = agents.any { it.working || it.status == "blocked" }
                      if (anyActive) {
                        lastActivityTimestamp = System.currentTimeMillis()
                      }
                      completions.observe(profile.machineId, agents).forEach { event ->
                        lastActivityTimestamp = System.currentTimeMillis()
                        post(
                          preferences,
                          "done:${event.key}",
                          "Agent finished",
                          "${profile.label}: ${event.agent.agent} finished a work cycle",
                          profile.id,
                          event.agent.paneId,
                          event.agent.nativeSessionRef,
                          event.agent.agent,
                        )
                      }
                      val current = mutableMapOf<String, String>()
                      agents.forEach { agent ->
                        val pane = agent.paneId
                        val reference = agent.nativeSessionRef
                        val sequence = agent.stateChangeSeq
                        if (
                          pane != null &&
                            reference != null &&
                            sequence != null &&
                            agent.status == "blocked"
                        ) {
                          val key = "input:${agent.machineId}:$pane:$reference:$sequence"
                          current[pane] = key
                          if (blocked[pane] != key) {
                            lastActivityTimestamp = System.currentTimeMillis()
                            post(
                              preferences,
                              key,
                              "Agent needs input",
                              "${profile.label}: ${agent.agent} is waiting for input",
                              profile.id,
                              pane,
                              reference,
                              agent.agent,
                            )
                          }
                        }
                      }
                      blocked.clear()
                      blocked.putAll(current)
                    } catch (error: Exception) {
                      if (error is CancellationException) throw error
                      completions.reset(profile.machineId)
                      consecutiveErrors++
                      // No error body, endpoint, native reference, transcript or token goes into
                      // logs.
                      android.util.Log.w("CappuccinoAlerts", "monitor_unavailable")
                    }
                    val delayMs =
                      when {
                        consecutiveErrors > 0 ->
                          minOf(MAX_BACKOFF_MS, 2_000L * (1L shl minOf(consecutiveErrors, 5)))
                        anyActive -> ACTIVE_POLL_INTERVAL_MS
                        else -> IDLE_POLL_INTERVAL_MS
                      }
                    delay(delayMs)
                  }
                }
          }
        }
        delay(2000)
      }
    }
  }

  private fun post(
    preferences: NotificationPreferences,
    key: String,
    title: String,
    text: String,
    profileId: String,
    pane: String?,
    reference: String?,
    agent: String? = null,
  ) {
    if (
      !preferences.enabled ||
        profileId in preferences.paused() ||
        !permissionGranted(this) ||
        !NotificationManagerCompat.from(this).areNotificationsEnabled()
    )
      return
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(ALERTS)?.importance == NotificationManager.IMPORTANCE_NONE)
      return
    if (preferences.wasNotified(key)) return
    val icon = agent?.let { agentIconResource(it) } ?: R.drawable.ic_notification
    val publicVersion =
      NotificationCompat.Builder(this, ALERTS)
        .setSmallIcon(icon)
        .setContentTitle("Cappuccino agent alert")
        .build()
    manager.notify(
      key,
      2,
      NotificationCompat.Builder(this, ALERTS)
        .setSmallIcon(icon)
        .setContentTitle(title)
        .setContentText(text.take(240))
        .setContentIntent(
          PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
              .setAction("com.azusachino.cappuccino.ALERT.$key")
              .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              .putExtra("alert_profile", profileId)
              .putExtra("alert_pane", pane)
              .putExtra("alert_reference", reference),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
          )
        )
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        .setPublicVersion(publicVersion)
        .setAutoCancel(true)
        .build(),
    )
    preferences.recordNotified(key)
  }

  private fun openApp(): PendingIntent =
    PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    lastActivityTimestamp = System.currentTimeMillis()
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  companion object {
    private const val MONITOR = "agent-monitoring"
    private const val ALERTS = "agent-alerts"
    private const val ACTIVE_POLL_INTERVAL_MS = 3_000L
    private const val IDLE_POLL_INTERVAL_MS = 10_000L
    private const val MAX_BACKOFF_MS = 60_000L
    private const val IDLE_TIMEOUT_MS = 5 * 60 * 1_000L

    fun permissionGranted(context: Context): Boolean =
      Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
          PackageManager.PERMISSION_GRANTED

    fun start(context: Context) {
      if (
        permissionGranted(context) &&
          NotificationManagerCompat.from(context).areNotificationsEnabled() &&
          NotificationPreferences(context).enabled &&
          ProfileStore(context).read().isNotEmpty()
      )
        ContextCompat.startForegroundService(
          context,
          Intent(context, AgentNotificationService::class.java),
        )
    }
  }
}
