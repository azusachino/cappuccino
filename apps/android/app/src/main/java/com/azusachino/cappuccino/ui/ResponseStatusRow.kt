package com.azusachino.cappuccino.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.core.ConversationPart

/**
 * A response that ended without an answer: aborted or failed, rendered as a Telegram service pill.
 */
@Composable
internal fun ResponseStatusRow(part: ConversationPart.ResponseStatus) {
  val colors = MaterialTheme.appColors
  Box(
    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = colors.card2.copy(alpha = 0.65f),
      border = BorderStroke(0.6.dp, colors.line.copy(alpha = 0.45f)),
    ) {
      Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            painterResource(
              if (part.status == "aborted") com.azusachino.cappuccino.R.drawable.ic_close
              else com.azusachino.cappuccino.R.drawable.ic_attention
            ),
            contentDescription = null,
            modifier = Modifier.size(13.dp),
            tint = if (part.status == "failed") MaterialTheme.colorScheme.error else colors.muted,
          )
          Text(
            if (part.status == "aborted") "Response aborted" else "Response failed",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (part.status == "failed") MaterialTheme.colorScheme.error else colors.muted,
          )
        }
        part.detail
          ?.takeIf { it.isNotBlank() }
          ?.let {
            Text(
              it,
              style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
              color = colors.faint,
            )
          }
      }
    }
  }
}
