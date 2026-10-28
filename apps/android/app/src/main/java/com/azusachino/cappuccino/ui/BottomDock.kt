package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** One dock entry: icon plus the label people read. */
internal data class DockItem(val iconRes: Int, val label: String)

/**
 * Telegram-style floating glass dock: a detached rounded island the content scrolls beneath. The
 * selected destination is a filled pill; the rest stay quiet icons with small labels.
 */
@Composable
internal fun BottomDock(
  items: List<DockItem>,
  selectedIndex: Int,
  onSelect: (Int) -> Unit,
  hazeState: HazeState,
  backgroundColor: Color,
  modifier: Modifier = Modifier,
) {
  Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    Column(
      Modifier.glassBackdrop(
        hazeState = hazeState,
        backgroundColor = backgroundColor,
        shape = RoundedCornerShape(28.dp),
      )
    ) {
      Row(
        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val haptics = LocalHapticFeedback.current
        items.forEachIndexed { index, item ->
          val isSelected = index == selectedIndex
          if (isSelected) {
            Row(
              Modifier.clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable {
                  haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onSelect(index)
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                painterResource(item.iconRes),
                contentDescription = item.label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
              )
            }
          } else {
            Column(
              Modifier.clickable { onSelect(index) }.padding(horizontal = 14.dp, vertical = 4.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Icon(
                painterResource(item.iconRes),
                contentDescription = item.label,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.appColors.muted,
              )
              Text(
                item.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.appColors.muted,
              )
            }
          }
        }
      }
    }
  }
}
