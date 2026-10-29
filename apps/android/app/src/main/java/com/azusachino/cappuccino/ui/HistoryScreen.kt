package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.io.SavedConversationInfo
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable
internal fun HistoryScreen(
  conversations: List<SavedConversationInfo>,
  savedMachineNames: Map<UUID, String>,
  currentConversationId: String?,
  timeZone: ZoneId,
  onOpenSaved: (UUID, String) -> Unit,
  onDeleteSaved: (UUID, String) -> Unit,
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(0.dp),
  lazyListState: LazyListState = rememberLazyListState(),
) {
  val colors = MaterialTheme.appColors
  var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }

  if (conversations.isEmpty()) {
    Column(modifier = modifier.fillMaxSize().padding(contentPadding)) {
      Text(
        stringResource(R.string.history),
        Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = colors.text,
      )
      Box(
        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center,
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Surface(
            shape = CircleShape,
            color = colors.card2,
            modifier = Modifier.size(64.dp),
          ) {
            Box(contentAlignment = Alignment.Center) {
              Icon(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = colors.muted,
              )
            }
          }
          Text(
            "No saved sessions",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
          )
          Text(
            "Nothing brewing yet. Conversations are preserved automatically while agents run or start a fresh session (/new), and kept locally for 30 days.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.muted,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  } else {
    LazyColumn(
      state = lazyListState,
      modifier = modifier.fillMaxSize(),
      contentPadding =
        PaddingValues(
          start = 16.dp,
          end = 16.dp,
          top = contentPadding.calculateTopPadding(),
          bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      item(key = "history_large_title") {
        Text(
          stringResource(R.string.history),
          Modifier.padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
          style = MaterialTheme.typography.headlineLarge,
          fontWeight = FontWeight.Bold,
          color = colors.text,
        )
      }

      item(key = "history_header") {
        Text(
          "Retained locally for 30 days · ${conversations.size} saved",
          Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
          style = MaterialTheme.typography.labelMedium,
          color = colors.muted,
        )
      }

      items(conversations, key = { "${it.agent.machineId}:${it.conversationId}" }) { saved ->
        val isCurrent = saved.conversationId == currentConversationId
        val formattedTime =
          formatConversationTimestamp(
            Instant.ofEpochMilli(saved.savedAt).toString(),
            timeZone,
          ) ?: "Recently"
        val machineName = savedMachineNames[saved.agent.machineId] ?: saved.agent.agent
        val turnsLabel =
          if (saved.messageCount == 1) "1 message" else "${saved.messageCount} messages"

        Surface(
          shape = RoundedCornerShape(16.dp),
          color = if (isCurrent) colors.accent.copy(alpha = 0.12f) else colors.card,
          border =
            BorderStroke(
              GlassDefaults.RimStrokeWidth,
              if (isCurrent) colors.accent.copy(alpha = 0.5f) else colors.line,
            ),
          onClick = { onOpenSaved(saved.agent.machineId, saved.conversationId) },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            AgentAvatar(saved.agent.agent, 36.dp)

            Column(modifier = Modifier.weight(1f)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
              ) {
                Text(
                  saved.agent.label,
                  style = MaterialTheme.typography.bodyMedium,
                  fontWeight = FontWeight.SemiBold,
                  color = colors.text,
                )
                if (isCurrent) {
                  Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = colors.accent.copy(alpha = 0.2f),
                  ) {
                    Text(
                      "Active",
                      modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                      style = MaterialTheme.typography.labelSmall,
                      color = colors.accent,
                    )
                  }
                }
              }
              Spacer(Modifier.height(2.dp))
              Text(
                "$machineName · $turnsLabel",
                style = MaterialTheme.typography.bodySmall,
                color = colors.text,
              )
              Text(
                "Saved $formattedTime",
                style = MaterialTheme.typography.labelSmall,
                color = colors.muted,
              )
            }

            IconButton(
              onClick = { pendingDelete = "${saved.agent.machineId}|${saved.conversationId}" },
              modifier = Modifier.size(36.dp),
            ) {
              Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Delete saved conversation",
                modifier = Modifier.size(16.dp),
                tint = colors.muted,
              )
            }
          }
        }
      }
    }
  }

  pendingDelete?.let { target ->
    val parts = target.split("|")
    val targetMachine = runCatching { UUID.fromString(parts[0]) }.getOrNull()
    val targetConvo = parts.getOrNull(1)
    val targetItem = conversations.firstOrNull {
      it.agent.machineId == targetMachine && it.conversationId == targetConvo
    }

    AlertDialog(
      onDismissRequest = { pendingDelete = null },
      title = { Text("Delete saved session?") },
      text = {
        Text(
          "Delete the saved session for \"${targetItem?.agent?.label ?: "agent"}\"? This action cannot be undone."
        )
      },
      confirmButton = {
        TextButton(
          onClick = {
            if (targetMachine != null && targetConvo != null) {
              onDeleteSaved(targetMachine, targetConvo)
            }
            pendingDelete = null
          }
        ) {
          Text("Delete", color = MaterialTheme.colorScheme.error)
        }
      },
      dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
    )
  }
}
