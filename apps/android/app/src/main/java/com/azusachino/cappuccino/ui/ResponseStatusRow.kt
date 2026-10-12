package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.ConversationPart

/** A response that ended without an answer: aborted or failed, and the provider's reason. */
@Composable
internal fun ResponseStatusRow(part: ConversationPart.ResponseStatus) {
  Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Icon(
        painterResource(
          if (part.status == "aborted") com.azusachino.cappuccino.R.drawable.ic_close
          else com.azusachino.cappuccino.R.drawable.ic_attention
        ),
        contentDescription = null,
        tint =
          if (part.status == "failed") MaterialTheme.colorScheme.error
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
        if (part.status == "aborted") "Response aborted" else "Response failed",
        style = MaterialTheme.typography.labelLarge,
      )
    }
    part.detail
      ?.takeIf { it.isNotBlank() }
      ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
  }
}
