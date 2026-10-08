package com.azusachino.cappuccino

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.azusachino.cappuccino.io.ConnectedViewModel
import com.azusachino.cappuccino.io.ProfileStore
import com.azusachino.cappuccino.ui.CappuccinoShell
import com.azusachino.cappuccino.ui.CappuccinoTheme
import java.time.ZoneId

class MainActivity : ComponentActivity() {
  internal var deviceTimeZone by mutableStateOf(ZoneId.systemDefault())
    private set

  private val timezoneReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        deviceTimeZone =
          runCatching { ZoneId.of(intent?.getStringExtra("time-zone")) }
            .getOrElse { ZoneId.systemDefault() }
      }
    }

  override fun onStart() {
    super.onStart()
    ContextCompat.registerReceiver(
      this,
      timezoneReceiver,
      IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
      ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    deviceTimeZone = ZoneId.systemDefault()
  }

  override fun onStop() {
    unregisterReceiver(timezoneReceiver)
    super.onStop()
  }

  private var pendingConnect: Pair<String, String>? = null
  private val localNetworkPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      val request = pendingConnect
      pendingConnect = null
      if (granted && request != null) connectedViewModel?.addMachine(request.first, request.second)
      else if (!granted)
        Toast.makeText(
            this,
            "Local network permission is required to connect to bridge hosts.",
            Toast.LENGTH_LONG,
          )
          .show()
    }
  private var connectedViewModel: ConnectedViewModel? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val vm: ConnectedViewModel =
        viewModel(
          factory =
            viewModelFactory {
              initializer {
                ConnectedViewModel(
                  ProfileStore(application),
                  { endpoint -> com.azusachino.cappuccino.io.BridgeClient(endpoint) },
                  { kotlinx.coroutines.delay(it) },
                  createSavedStateHandle(),
                )
              }
            }
        )
      connectedViewModel = vm
      val owner = LocalLifecycleOwner.current
      androidx.compose.runtime.DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
          when (event) {
            Lifecycle.Event.ON_START -> vm.setForeground(true)
            Lifecycle.Event.ON_STOP -> vm.setForeground(false)
            else -> Unit
          }
        }
        owner.lifecycle.addObserver(observer)
        vm.setForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { owner.lifecycle.removeObserver(observer) }
      }
      val state by vm.state.collectAsStateWithLifecycle()
      CappuccinoTheme(themeMode = state.themeMode) {
        CappuccinoShell(vm, timeZone = deviceTimeZone) { label, url ->
          if (
            Build.VERSION.SDK_INT >= 37 &&
              checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
          ) {
            pendingConnect = label to url
            localNetworkPermission.launch("android.permission.ACCESS_LOCAL_NETWORK")
          } else {
            vm.addMachine(label, url)
          }
        }
      }
    }
  }
}
