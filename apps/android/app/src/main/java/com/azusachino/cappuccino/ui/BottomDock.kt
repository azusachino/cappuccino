package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** One dock entry: icon plus the label people read. */
internal data class DockItem(val iconRes: Int, val label: String)

/**
 * Telegram-style full-width bottom bar: spans edge-to-edge across the bottom with a quiet hairline
 * divider and glass backdrop. Tabs are distributed evenly across the screen width.
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
  Column(
    modifier
      .fillMaxWidth()
      .glassBackdrop(
        hazeState = hazeState,
        backgroundColor = backgroundColor,
        shape = RectangleShape,
      )
  ) {
    HorizontalDivider(
      thickness = 0.5.dp,
      color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    )
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val haptics = LocalHapticFeedback.current
      items.forEachIndexed { index, item ->
        val isSelected = index == selectedIndex
        val tint =
          if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.appColors.muted
        Column(
          Modifier.weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .selectable(
              selected = isSelected,
              role = Role.Tab,
              onClick = {
                if (isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onSelect(index)
              },
            )
            .padding(vertical = 4.dp)
            .semantics { contentDescription = item.label },
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          Box(
            Modifier.clip(RoundedCornerShape(16.dp))
              .then(
                if (isSelected)
                  Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                else Modifier
              )
              .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
          ) {
            Icon(
              painterResource(item.iconRes),
              contentDescription = null,
              modifier = Modifier.size(22.dp),
              tint = tint,
            )
          }
          Text(
            item.label,
            style =
              MaterialTheme.typography.labelSmall.copy(
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
              ),
            color = tint,
          )
        }
      }
    }
  }
}
