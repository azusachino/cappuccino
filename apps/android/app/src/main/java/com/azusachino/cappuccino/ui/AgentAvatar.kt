package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/** Telegram-style avatar: a gradient circle with the agent's initial, no glyph. */
@Composable
fun AgentAvatar(agent: String, size: Dp, modifier: Modifier = Modifier) {
  val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
  val (top, bottom) = agentBrandColors(agent, dark)
  Box(
    modifier.size(size).clip(CircleShape).background(Brush.verticalGradient(listOf(top, bottom))),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      agent.take(1).uppercase(),
      style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
      color = Color.White,
    )
  }
}
