package com.azusachino.cappuccino.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
internal fun ReaderFloatingControls(
  entries: List<ToolActivityEntry>,
  hasNewOutput: Boolean,
  onTools: () -> Unit,
  onLatest: () -> Unit,
  modifier: Modifier = Modifier,
  newActivity: Boolean = false,
) {
  if (entries.isEmpty() && !hasNewOutput) return
  val updateAlpha by
    animateFloatAsState(
      if (newActivity) 1f else 0f,
      animationSpec = tween(600),
      label = "newToolActivity",
    )
  Surface(
    modifier,
    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
    shadowElevation = 3.dp,
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      if (entries.isNotEmpty())
        TextButton(
          onClick = onTools,
          modifier =
            Modifier.semantics {
              contentDescription = toolActivityLabel(entries)
              stateDescription = if (newActivity) "New tool activity" else "Tool activity"
            },
        ) {
          Icon(
            painterResource(
              if (entries.any { it.part.isError == true })
                com.azusachino.cappuccino.R.drawable.ic_attention
              else com.azusachino.cappuccino.R.drawable.ic_agent_code
            ),
            contentDescription = null,
          )
          Spacer(Modifier.width(6.dp))
          Box(
            Modifier.size(6.dp)
              .alpha(updateAlpha)
              .background(MaterialTheme.colorScheme.primary, CircleShape)
          )
          Spacer(Modifier.width(4.dp))
          Text(
            "Tools · ${entries.size}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
          )
          val failedCount = entries.count { it.part.isError == true }
          if (failedCount > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
              "$failedCount",
              style = MaterialTheme.typography.labelLarge,
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
      if (hasNewOutput)
        TextButton(
          onClick = onLatest,
          modifier = Modifier.semantics { contentDescription = "Jump to latest" },
        ) {
          Icon(
            painterResource(com.azusachino.cappuccino.R.drawable.ic_arrow_downward),
            contentDescription = null,
          )
          Spacer(Modifier.width(6.dp))
          Text(
            "Jump to latest",
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
          )
        }
    }
  }
}
