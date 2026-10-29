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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState

/** One dock entry: icon plus the label people read. */
internal data class DockItem(val iconRes: Int, val label: String)

/** Fully rounded capsule: 50% corners stay a stadium at any tab height. */
private val DockPillShape = RoundedCornerShape(percent = 50)

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
          // Selected and idle tabs share one layout (icon over label, same padding and type), so
          // selection only swaps colours and the pill; widths never shift.
          val tint =
            if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.appColors.muted
          // Every tab clips to the same capsule, so the press ripple and the selected fill are
          // both round from the first frame; an unclipped idle tab flashed a square ripple.
          Column(
            Modifier.clip(DockPillShape)
              .then(
                if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                else Modifier
              )
              .selectable(
                selected = isSelected,
                role = Role.Tab,
                onClick = {
                  if (isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                  onSelect(index)
                },
              )
              .padding(horizontal = 14.dp, vertical = 4.dp)
              .semantics { contentDescription = item.label },
            horizontalAlignment = Alignment.CenterHorizontally,
          ) {
            Icon(
              painterResource(item.iconRes),
              contentDescription = null,
              modifier = Modifier.size(20.dp),
              tint = tint,
            )
            Text(item.label, style = MaterialTheme.typography.labelSmall, color = tint)
          }
        }
      }
    }
  }
}
