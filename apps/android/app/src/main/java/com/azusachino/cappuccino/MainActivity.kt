package com.azusachino.cappuccino

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.azusachino.cappuccino.ui.CappuccinoShell

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      val dark = isSystemInDarkTheme()
      val colors =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
        } else {
          if (dark) darkColorScheme(primary = Color(0xFFD7B79E))
          else lightColorScheme(primary = Color(0xFF71513C))
        }
      MaterialTheme(colorScheme = colors) { CappuccinoShell() }
    }
  }
}
