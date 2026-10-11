package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.ToolActivityState
import com.azusachino.cappuccino.core.activityState

internal data class ToolActivityEntry(
  val turnId: String,
  val partIndex: Int,
  val part: ConversationPart.ToolCall,
)

internal fun toolActivityEntries(turns: List<ConversationTurn>): List<ToolActivityEntry> =
  turns.flatMap { turn ->
    turn.parts.mapIndexedNotNull { index, part ->
      (part as? ConversationPart.ToolCall)?.let {
        ToolActivityEntry(turn.id, it.originalPartIndex ?: index, it)
      }
    }
  }

internal fun toolActivityLabel(entries: List<ToolActivityEntry>): String {
  val failed = entries.count { it.part.activityState == ToolActivityState.FAILED }
  val pending = entries.count { it.part.activityState == ToolActivityState.AWAITING_RESULT }
  return listOfNotNull(
      "Show tool activity · ${entries.size}",
      if (failed > 0) "$failed failed" else null,
      if (pending > 0) "$pending awaiting result" else null,
    )
    .joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolActivitySheet(
  entries: List<ToolActivityEntry>,
  targetTurnId: String?,
  onDismiss: () -> Unit,
  earlierOmitted: Boolean = false,
) {
  val initial =
    entries.indexOfFirst { it.turnId == targetTurnId }.takeIf { it >= 0 }
      ?: entries.lastIndex.coerceAtLeast(0)
  val list = rememberLazyListState(initialFirstVisibleItemIndex = initial)
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
  ) {
    Row(
      Modifier.fillMaxWidth().padding(horizontal = 16.dp),
      verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
      Icon(
        painterResource(com.azusachino.cappuccino.R.drawable.ic_agent_code),
        contentDescription = null,
      )
      Text(
        "Tool activity",
        Modifier.weight(1f).padding(start = 12.dp),
        style = MaterialTheme.typography.titleLarge,
      )
      IconButton(onClick = onDismiss) {
        Icon(
          painterResource(com.azusachino.cappuccino.R.drawable.ic_close),
          contentDescription = "Close tool activity",
        )
      }
    }
    Text(
      "${entries.size} operations · chronological order" +
        if (earlierOmitted) " · Earlier history not shown" else "",
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
      state = list,
      modifier = Modifier.fillMaxWidth().weight(1f, fill = false).testTag("toolActivityList"),
      contentPadding = PaddingValues(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      itemsIndexed(entries, key = { _, entry -> "${entry.turnId}:${entry.partIndex}" }) { _, entry
        ->
        ConversationDetails(entry.part)
      }
    }
  }
}
