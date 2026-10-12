package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.activityState

@Composable
internal fun ToolHistoryPanel(
  entries: List<ToolActivityEntry>,
  omitted: Int,
  modifier: Modifier = Modifier,
  onAtLatest: ((Boolean) -> Unit)? = null,
) {
  val newestFirst = remember(entries) { entries.asReversed() }
  val list = rememberLazyListState()
  var followNewest by remember { mutableStateOf(true) }
  var automaticScroll by remember { mutableStateOf(false) }
  LaunchedEffect(list) {
    var manualScroll = false
    snapshotFlow { Triple(list.isScrollInProgress, !list.canScrollBackward, automaticScroll) }
      .collect { (scrolling, atTop, automatic) ->
        onAtLatest?.invoke(atTop)
        if (scrolling && !automatic) {
          manualScroll = true
          followNewest = false
        } else if (!scrolling && !automatic && manualScroll) {
          manualScroll = false
          followNewest = atTop
        }
      }
  }
  LaunchedEffect(entries) {
    if (followNewest && entries.isNotEmpty()) {
      automaticScroll = true
      try {
        list.scrollToItem(0)
      } finally {
        automaticScroll = false
      }
    }
  }
  Column(modifier) {
    val failed = entries.count { it.part.activityState == ToolActivityState.FAILED }
    Text(
      "Shown: ${entries.size} · Loaded: ${entries.size.toLong() + omitted}\nFailed among shown: $failed",
      Modifier.padding(16.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
      state = list,
      modifier = Modifier.fillMaxSize().testTag("toolActivityList"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (entries.isEmpty()) item { Text("No recorded tools") }
      items(newestFirst, key = { "${it.turnId}:${it.partIndex}" }) {
        ConversationDetails(it.part, onInspect = { followNewest = false })
      }
    }
  }
}
