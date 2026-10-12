package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
  var errors by rememberSaveable { mutableStateOf(false) }
  val newestFirst =
    remember(entries, errors) {
      entries.filter { (it.part.isError == true) == errors }.asReversed()
    }
  val normalList = rememberLazyListState()
  val errorList = rememberLazyListState()
  val list = if (errors) errorList else normalList
  var followNormal by rememberSaveable { mutableStateOf(true) }
  var followErrors by rememberSaveable { mutableStateOf(true) }
  val followNewest = if (errors) followErrors else followNormal
  fun setFollowing(value: Boolean) {
    if (errors) followErrors = value else followNormal = value
  }
  var automaticScroll by remember { mutableStateOf(false) }
  LaunchedEffect(list) {
    var manualScroll = false
    snapshotFlow { Triple(list.isScrollInProgress, !list.canScrollBackward, automaticScroll) }
      .collect { (scrolling, atTop, automatic) ->
        onAtLatest?.invoke(atTop)
        if (scrolling && !automatic) {
          manualScroll = true
          setFollowing(false)
        } else if (!scrolling && !automatic && manualScroll) {
          manualScroll = false
          setFollowing(atTop)
        }
      }
  }
  LaunchedEffect(newestFirst, list) {
    if (followNewest && newestFirst.isNotEmpty()) {
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
    Row {
      TextButton(onClick = { errors = false }) {
        Text(
          "Normal (${entries.size - failed})",
          color =
            if (!errors) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      TextButton(onClick = { errors = true }) {
        Text(
          "Errors ($failed)",
          color =
            if (errors) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    if (omitted > 0)
      Text(
        "${entries.size} shown / ${entries.size.toLong() + omitted} loaded calls",
        Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.bodySmall,
      )
    LazyColumn(
      state = list,
      modifier = Modifier.fillMaxSize().testTag("toolActivityList"),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      if (newestFirst.isEmpty())
        item { Text(if (errors) "No recorded errors" else "No recorded normal tools") }
      items(newestFirst, key = { "${it.turnId}:${it.partIndex}" }) {
        ConversationDetails(it.part, onInspect = { setFollowing(false) })
      }
    }
  }
}
