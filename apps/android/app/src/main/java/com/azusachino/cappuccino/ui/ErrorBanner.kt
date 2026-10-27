package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R

/** A quiet, dismissible-looking inline error: icon, message, no shouting. */
@Composable
internal fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    shape = RoundedCornerShape(12.dp),
    color = MaterialTheme.colorScheme.errorContainer,
  ) {
    Row(
      Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        painterResource(R.drawable.ic_attention),
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.error,
      )
      Text(
        text,
        Modifier.weight(1f),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
      )
    }
  }
}

/** Convenience for a stack of banner texts. */
@Composable
internal fun ErrorBanners(texts: List<String>, modifier: Modifier = Modifier) {
  Column(modifier) { texts.forEach { ErrorBanner(it) } }
}

/** The Telegram "Connecting…" pattern: a full-width amber state strip, not a dialog. */
@Composable
internal fun WarningBanner(text: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier.fillMaxWidth(),
    color = MaterialTheme.appColors.warn.copy(alpha = 0.18f),
  ) {
    Row(
      Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(Modifier.size(8.dp).background(MaterialTheme.appColors.warn, RoundedCornerShape(2.dp)))
      Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.appColors.text,
      )
    }
  }
}
