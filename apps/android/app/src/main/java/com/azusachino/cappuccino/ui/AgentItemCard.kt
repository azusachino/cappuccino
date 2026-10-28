package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.AgentRow

@Composable
internal fun AgentItemCard(
  agent: AgentRow,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
    shape = MaterialTheme.shapes.medium,
  ) {
    ListItem(
      headlineContent = { Text(agent.label, fontWeight = FontWeight.SemiBold) },
      supportingContent = {
        Text(
          listOfNotNull(agent.agent.replaceFirstChar { it.uppercase() }, agent.branch)
            .joinToString(" · ")
        )
      },
      leadingContent = { AgentAvatar(agent.agent, 40.dp) },
      trailingContent = { StatusBadge(agent.status) },
    )
  }
}
