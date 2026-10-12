package com.azusachino.cappuccino.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier

/** Explicitly opened debug design preview. Never reads profiles or attaches to agents. */
class RewritePreviewActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent { Box(Modifier.fillMaxSize().safeDrawingPadding()) { RewriteDesignPrototype() } }
  }
}
