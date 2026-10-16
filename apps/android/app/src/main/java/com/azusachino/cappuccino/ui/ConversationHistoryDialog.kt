package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.io.SavedConversationInfo
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

@Composable
internal fun ConversationHistoryDialog(
  conversations: List<SavedConversationInfo>,
  savedMachineNames: Map<UUID, String>,
  currentConversationId: String?,
  timeZone: ZoneId,
  onSelect: (UUID, String) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = MaterialTheme.appColors
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          painter = painterResource(R.drawable.ic_history),
          contentDescription = null,
          modifier = Modifier.size(20.dp),
          tint = colors.accent,
        )
        Text("Conversation History")
      }
    },
    text = {
      if (conversations.isEmpty()) {
        Text(
          "No saved conversation history yet. Conversations are automatically preserved as agents work or when starting a fresh session (/new).",
          style = MaterialTheme.typography.bodyMedium,
          color = colors.muted,
        )
      } else {
        Column(
          modifier =
            Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          conversations.forEach { saved ->
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
              shape = RoundedCornerShape(8.dp),
              color = if (isCurrent) colors.accent.copy(alpha = 0.12f) else colors.card2,
              border =
                BorderStroke(
                  1.dp,
                  if (isCurrent) colors.accent.copy(alpha = 0.5f) else colors.line,
                ),
              onClick = {
                onDismiss()
                onSelect(saved.agent.machineId, saved.conversationId)
              },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
              ) {
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
                  Text(
                    "$machineName · Saved $formattedTime · $turnsLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.muted,
                  )
                }
                Text("›", style = MaterialTheme.typography.bodyLarge, color = colors.faint)
              }
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
  )
}
