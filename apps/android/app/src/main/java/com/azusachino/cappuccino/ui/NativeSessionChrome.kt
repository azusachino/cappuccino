package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R

internal enum class SessionTab(val label: String) {
  TRANSCRIPT("Session"),
  TOOLS("Tools"),
}

@Composable
internal fun NativeSessionTabs(
  selected: SessionTab,
  shown: Int,
  omitted: Int,
  returned: Int,
  updates: Int = 0,
  onSelect: (SessionTab) -> Unit,
) {
  SecondaryTabRow(selectedTabIndex = selected.ordinal) {
    SessionTab.entries.forEach { tab ->
      Tab(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        text = {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
              if (tab == SessionTab.TOOLS) "Tools (${toolActivityCount(shown, omitted)})"
              else tab.label,
              maxLines = 1,
            )
            Text(
              if (tab == SessionTab.TOOLS)
                "$returned returned" + (if (updates > 0) " · $updates new" else "")
              else " ",
              style = MaterialTheme.typography.labelSmall,
              maxLines = 1,
            )
          }
        },
      )
    }
  }
}

/** In-flow actions: no tool badge, pulse, overlapping surface or viewport spacer. */
@Composable
internal fun NativeSessionFollowBar(
  following: Boolean,
  newAnswer: Boolean,
  onFollow: (Boolean) -> Unit,
  onLatest: () -> Unit,
) {
  Row(
    Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FilterChip(
      selected = following,
      onClick = { onFollow(!following) },
      label = {
        Text(if (following) "Following" else "Follow", style = MaterialTheme.typography.labelMedium)
      },
      leadingIcon = {
        Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null)
      },
    )
    TextButton(
      onClick = onLatest,
      enabled = !following || newAnswer,
      modifier = Modifier.testTag("jumpToLatest"),
    ) {
      Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null)
      Text(
        "Latest",
        Modifier.padding(start = 8.dp),
        style = MaterialTheme.typography.labelMedium,
      )
    }
  }
}
