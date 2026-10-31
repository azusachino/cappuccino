package com.azusachino.cappuccino.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.io.ConnectionLogEntry
import com.azusachino.cappuccino.io.ConnectionLogLevel
import com.azusachino.cappuccino.io.ConnectionLogger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectionLogsSheet(onDismiss: () -> Unit) {
  val context = LocalContext.current
  val colors = MaterialTheme.appColors
  var logs by remember { mutableStateOf(ConnectionLogger.getLogs()) }
  var onlyErrors by remember { mutableStateOf(false) }

  DisposableEffect(Unit) {
    val unsubscribe = ConnectionLogger.subscribe { logs = ConnectionLogger.getLogs() }
    onDispose { unsubscribe() }
  }

  val filteredLogs =
    remember(logs, onlyErrors) {
      if (onlyErrors) {
        logs.filter { it.level == ConnectionLogLevel.WARN || it.level == ConnectionLogLevel.ERROR }
      } else {
        logs
      }
    }

  val listState = rememberLazyListState()
  LaunchedEffect(filteredLogs.size) {
    if (filteredLogs.isNotEmpty()) {
      listState.scrollToItem(filteredLogs.size - 1)
    }
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Header
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            "Connection Logs",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.text,
          )
          Text(
            "${filteredLogs.size} events recorded",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
          )
        }
        Row(
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          IconButton(
            onClick = {
              val text = ConnectionLogger.exportText()
              if (text.isNotBlank()) {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("Connection Logs", text))
                Toast.makeText(context, "Copied connection logs", Toast.LENGTH_SHORT).show()
              } else {
                Toast.makeText(context, "No logs to copy", Toast.LENGTH_SHORT).show()
              }
            },
            modifier = Modifier.size(36.dp),
          ) {
            Icon(
              painterResource(R.drawable.ic_copy),
              contentDescription = "Copy logs",
              tint = colors.accent,
              modifier = Modifier.size(18.dp),
            )
          }
          IconButton(
            onClick = {
              ConnectionLogger.clear()
              Toast.makeText(context, "Logs cleared", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(36.dp),
          ) {
            Icon(
              painterResource(R.drawable.ic_trash),
              contentDescription = "Clear logs",
              tint = colors.muted,
              modifier = Modifier.size(18.dp),
            )
          }
        }
      }

      // Filter chips
      Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        FilterChip(
          selected = !onlyErrors,
          onClick = { onlyErrors = false },
          label = { Text("All events", style = MaterialTheme.typography.labelSmall) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = colors.card2,
              selectedLabelColor = colors.accent,
            ),
        )
        FilterChip(
          selected = onlyErrors,
          onClick = { onlyErrors = true },
          label = { Text("Warnings & Errors", style = MaterialTheme.typography.labelSmall) },
          colors =
            FilterChipDefaults.filterChipColors(
              selectedContainerColor = colors.card2,
              selectedLabelColor = colors.warn,
            ),
        )
      }

      HorizontalDivider(color = colors.line.copy(alpha = 0.3f))

      // Logs List
      if (filteredLogs.isEmpty()) {
        Box(
          Modifier.fillMaxWidth().height(200.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            if (onlyErrors) "No errors or warnings recorded."
            else "No connection events logged yet.",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
          )
        }
      } else {
        LazyColumn(
          state = listState,
          modifier =
            Modifier.fillMaxWidth()
              .heightIn(min = 200.dp, max = 450.dp)
              .background(colors.card, RoundedCornerShape(12.dp))
              .padding(8.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(filteredLogs, key = { it.id }) { entry -> LogEntryRow(entry) }
        }
      }
    }
  }
}

@Composable
private fun LogEntryRow(entry: ConnectionLogEntry) {
  val colors = MaterialTheme.appColors
  val badgeBg =
    when (entry.level) {
      ConnectionLogLevel.INFO -> colors.card2
      ConnectionLogLevel.WARN -> colors.warn.copy(alpha = 0.15f)
      ConnectionLogLevel.ERROR -> colors.err.copy(alpha = 0.15f)
    }
  val badgeColor =
    when (entry.level) {
      ConnectionLogLevel.INFO -> colors.muted
      ConnectionLogLevel.WARN -> colors.warn
      ConnectionLogLevel.ERROR -> colors.err
    }

  Row(
    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Text(
      entry.formattedTime,
      style =
        TextStyle(
          fontFamily = JetBrainsMono,
          fontSize = 10.sp,
          color = colors.faint,
        ),
      modifier = Modifier.padding(top = 2.dp),
    )
    Spacer(Modifier.width(6.dp))
    Box(
      Modifier.background(badgeBg, RoundedCornerShape(4.dp))
        .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
      Text(
        entry.category,
        style =
          TextStyle(
            fontFamily = JetBrainsMono,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            color = badgeColor,
          ),
      )
    }
    Spacer(Modifier.width(6.dp))
    Text(
      entry.message,
      style =
        TextStyle(
          fontFamily = JetBrainsMono,
          fontSize = 11.sp,
          lineHeight = 15.sp,
          color =
            when (entry.level) {
              ConnectionLogLevel.INFO -> colors.text
              ConnectionLogLevel.WARN -> colors.warn
              ConnectionLogLevel.ERROR -> colors.err
            },
        ),
      modifier = Modifier.weight(1f),
    )
  }
}
