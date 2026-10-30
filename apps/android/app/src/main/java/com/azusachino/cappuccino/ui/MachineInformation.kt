package com.azusachino.cappuccino.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.azusachino.cappuccino.BuildConfig
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.BRIDGE_PROTOCOL_VERSION
import com.azusachino.cappuccino.io.ConnectedUiState
import com.azusachino.cappuccino.io.ConnectionState
import com.azusachino.cappuccino.io.MachineProfile
import com.azusachino.cappuccino.io.TransportBadge
import com.azusachino.cappuccino.io.machineConnection

internal fun machineInformationStatus(profile: MachineProfile, state: ConnectedUiState): String =
  when (val connection = state.machineConnection(profile).connection) {
    ConnectionState.Connected -> "Connected"
    ConnectionState.Connecting -> "Connecting"
    ConnectionState.Recovering -> "Reconnecting"
    ConnectionState.Disconnected -> "Not connected"
    is ConnectionState.Error ->
      if (connection.message.startsWith("Incompatible bridge protocol")) "Protocol conflict"
      else "Needs attention"
  }

internal fun machineInformationWarnings(
  profile: MachineProfile,
  state: ConnectedUiState,
): List<String> {
  if (state.machineConnection(profile).connection != ConnectionState.Connected) return emptyList()
  val capabilities =
    state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }?.capabilities
      ?: return emptyList()
  return listOf(
      "agent_discovery" to "Agent discovery is unavailable.",
      "live_output" to "Live output is unavailable.",
      "conversation_history" to "Conversation history is unavailable.",
      "prompt_input" to "Prompt input is unavailable on this bridge.",
      "prompt_answers" to "Approval and question answers are unavailable.",
    )
    .filter { capabilities[it.first] == false }
    .map { it.second }
}

internal fun connectionStatusColor(status: String, colors: AppColors): Color =
  when (status) {
    "Connected" -> colors.ok
    "Connecting",
    "Reconnecting" -> colors.warn
    "Protocol conflict",
    "Needs attention" -> colors.err
    else -> colors.muted
  }

@Composable
internal fun StatusPill(
  text: String,
  color: Color,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(12.dp),
    color = color.copy(alpha = 0.12f),
    border = BorderStroke(0.5.dp, color.copy(alpha = 0.28f)),
  ) {
    Row(
      Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Box(Modifier.size(6.dp).background(color, CircleShape))
      Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Medium,
        color = color,
      )
    }
  }
}

/** tw93-style card: letterspaced uppercase section header and elevated glass card. */
@Composable
internal fun MachineCard(title: String, content: @Composable () -> Unit) {
  Text(
    title.uppercase(),
    Modifier.padding(start = 28.dp, end = 24.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.5.sp),
    color = MaterialTheme.colorScheme.primary,
  )
  Column(
    Modifier.fillMaxWidth()
      .padding(horizontal = 16.dp)
      .glassSurface(shape = RoundedCornerShape(16.dp), isElevated = true)
      .padding(vertical = 4.dp)
  ) {
    content()
  }
}

/** 0.5dp hairline divider with quiet opacity. */
@Composable
internal fun MachineDivider() {
  HorizontalDivider(
    Modifier.padding(horizontal = 16.dp),
    thickness = 0.5.dp,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
  )
}

/** Single row for keys and values with rock-solid right alignment. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MachineDetailRow(
  label: String,
  value: String? = null,
  valueColor: Color? = null,
  isMono: Boolean = false,
  showChevron: Boolean = false,
  trailing: (@Composable () -> Unit)? = null,
  onClick: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = MaterialTheme.appColors
  Row(
    Modifier.fillMaxWidth()
      .heightIn(min = 46.dp)
      .then(
        if (onClick != null || onLongClick != null) {
          Modifier.combinedClickable(
            role = Role.Button,
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() },
          )
        } else Modifier
      )
      .padding(horizontal = 16.dp, vertical = 11.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.text,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.width(12.dp))
    Row(
      modifier = Modifier.weight(1f),
      horizontalArrangement = Arrangement.End,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      if (value != null) {
        Text(
          text = value,
          style =
            if (isMono)
              TextStyle(
                fontFamily = JetBrainsMono,
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
              )
            else MaterialTheme.typography.bodyMedium,
          color = valueColor ?: colors.muted,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          textAlign = TextAlign.End,
        )
      }
      trailing?.invoke()
      if (showChevron) {
        Text(
          "›",
          modifier = Modifier.padding(start = 6.dp),
          style = MaterialTheme.typography.bodyMedium,
          color = colors.faint,
        )
      }
    }
  }
}

/** Dedicated field for longer values (URLs, hashes, errors) to prevent line wrap artifacts. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MachineDetailField(
  label: String,
  value: String,
  isMono: Boolean = false,
  isError: Boolean = false,
  onClick: (() -> Unit)? = null,
  onLongClick: (() -> Unit)? = null,
) {
  val colors = MaterialTheme.appColors
  Column(
    Modifier.fillMaxWidth()
      .then(
        if (onClick != null || onLongClick != null) {
          Modifier.combinedClickable(
            role = Role.Button,
            onClick = { onClick?.invoke() },
            onLongClick = { onLongClick?.invoke() },
          )
        } else Modifier
      )
      .padding(horizontal = 16.dp, vertical = 10.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      label,
      style = MaterialTheme.typography.bodyMedium,
      color = colors.text,
    )
    Text(
      value,
      style =
        if (isMono)
          TextStyle(
            fontFamily = JetBrainsMono,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Normal,
          )
        else MaterialTheme.typography.bodySmall,
      color = if (isError) colors.err else colors.muted,
    )
  }
}

/** Full-screen tap-to-preview dialog for truncated or long text with full copy support. */
@Composable
internal fun ValuePreviewDialog(
  title: String,
  value: String,
  isMono: Boolean = true,
  onDismiss: () -> Unit,
) {
  val context = LocalContext.current
  val colors = MaterialTheme.appColors
  var copied by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = colors.text,
          modifier = Modifier.weight(1f, fill = false),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        IconButton(
          onClick = {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(title, value))
            copied = true
            Toast.makeText(context, "Copied $title to clipboard", Toast.LENGTH_SHORT).show()
          },
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            painterResource(if (copied) R.drawable.ic_check else R.drawable.ic_copy),
            contentDescription = "Copy $title",
            modifier = Modifier.size(18.dp),
            tint = if (copied) colors.ok else colors.accent,
          )
        }
      }
    },
    text = {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Surface(
          shape = RoundedCornerShape(8.dp),
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier.fillMaxWidth(),
        ) {
          SelectionContainer {
            Text(
              text = value,
              modifier = Modifier.padding(12.dp),
              style =
                if (isMono)
                  TextStyle(
                    fontFamily = JetBrainsMono,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontWeight = FontWeight.Normal,
                  )
                else MaterialTheme.typography.bodyMedium,
              color = colors.text,
            )
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    dismissButton = null,
    containerColor = MaterialTheme.colorScheme.surface,
  )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MachineInformation(
  profile: MachineProfile,
  state: ConnectedUiState,
  onDismiss: () -> Unit,
  onRename: () -> Unit,
) {
  val colors = MaterialTheme.appColors
  val context = LocalContext.current
  val details = state.bridgeDetails[profile.id]?.takeIf { it.machineId == profile.machineId }
  val connection = state.machineConnection(profile).connection
  val current = connection == ConnectionState.Connected
  val status = machineInformationStatus(profile, state)
  val statusColor = connectionStatusColor(status, colors)

  var previewItem by remember { mutableStateOf<Pair<String, String>?>(null) }
  val onPreview = { label: String, value: String -> previewItem = Pair(label, value) }
  val onCopy = { label: String, value: String ->
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Copied $label to clipboard", Toast.LENGTH_SHORT).show()
  }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header: Name + Status badge
      Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          profile.label.takeUnless { it == "iroh.invalid" } ?: "Machine",
          Modifier.weight(1f, fill = false),
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold,
          color = colors.text,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(12.dp))
        StatusPill(text = status, color = statusColor)
      }

      if (connection is ConnectionState.Error) {
        val bannerText =
          if (status == "Protocol conflict") {
            val bridgeProto =
              Regex("""bridge protocol v?(\d+)""").find(connection.message)?.groupValues?.get(1)
            val num = bridgeProto?.toIntOrNull() ?: 1
            if (num > 1) {
              "Protocol conflict: this bridge uses v$bridgeProto (newer than app v1). Please update Cappuccino to the latest version."
            } else {
              "Protocol conflict: this bridge uses v$bridgeProto (older than app v1). Please update cappuccino-bridge on your machine."
            }
          } else connection.message
        ErrorBanner(
          bannerText,
          modifier =
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable {
              onPreview("Error details", bannerText)
            },
        )
      }
      machineInformationWarnings(profile, state).forEach { warning ->
        ErrorBanner(
          warning,
          modifier =
            Modifier.padding(horizontal = 16.dp, vertical = 2.dp).clickable {
              onPreview("Warning details", warning)
            },
        )
      }

      // Card 1: Routing & Transport
      MachineCard("Routing & Transport") {
        if (profile.endpoint.iroh != null && profile.endpoint.hasTailnet) {
          val primary = "iroh P2P (default)"
          MachineDetailRow(
            "Primary route",
            primary,
            onClick = { onPreview("Primary route", primary) },
            onLongClick = { onCopy("Primary route", primary) },
          )
          MachineDivider()
          val fallback = "Tailnet (automatic)"
          MachineDetailRow(
            "Fallback route",
            fallback,
            onClick = { onPreview("Fallback route", fallback) },
            onLongClick = { onCopy("Fallback route", fallback) },
          )
          MachineDivider()
          val currentBadge = state.machineBadges[profile.id]
          val (activeText, activeColor) =
            when (currentBadge) {
              TransportBadge.TAILNET -> "Tailnet fallback" to colors.warn
              TransportBadge.IROH_DIRECT -> "iroh direct (default)" to colors.ok
              TransportBadge.IROH_RELAYED -> "iroh relay (default)" to colors.ok
              else -> if (current) "Connected" to colors.ok else status to colors.muted
            }
          MachineDetailRow(
            "Active route",
            activeText,
            valueColor = activeColor,
            onClick = { onPreview("Active route", activeText) },
            onLongClick = { onCopy("Active route", activeText) },
          )
          MachineDivider()
          val host =
            profile.endpoint.base.host ?: profile.endpoint.base.toString().removeSuffix("/")
          MachineDetailField(
            "Tailnet address",
            host,
            isMono = true,
            onClick = { onPreview("Tailnet address", host) },
            onLongClick = { onCopy("Tailnet address", host) },
          )
        } else if (profile.endpoint.iroh != null) {
          val primary = "iroh P2P (default)"
          MachineDetailRow(
            "Primary route",
            primary,
            onClick = { onPreview("Primary route", primary) },
            onLongClick = { onCopy("Primary route", primary) },
          )
          MachineDivider()
          val currentBadge = state.machineBadges[profile.id]
          val (activeText, activeColor) =
            when (currentBadge) {
              TransportBadge.IROH_DIRECT -> "iroh direct (default)" to colors.ok
              TransportBadge.IROH_RELAYED -> "iroh relay (default)" to colors.ok
              else -> if (current) "Connected" to colors.ok else status to colors.muted
            }
          MachineDetailRow(
            "Active route",
            activeText,
            valueColor = activeColor,
            onClick = { onPreview("Active route", activeText) },
            onLongClick = { onCopy("Active route", activeText) },
          )
        } else {
          val primary = "Tailnet"
          MachineDetailRow(
            "Primary route",
            primary,
            onClick = { onPreview("Primary route", primary) },
            onLongClick = { onCopy("Primary route", primary) },
          )
          MachineDivider()
          val host =
            profile.endpoint.base.host ?: profile.endpoint.base.toString().removeSuffix("/")
          MachineDetailField(
            "Tailnet address",
            host,
            isMono = true,
            onClick = { onPreview("Tailnet address", host) },
            onLongClick = { onCopy("Tailnet address", host) },
          )
        }
        profile.endpoint.iroh?.let { iroh ->
          MachineDivider()
          MachineDetailField(
            "Iroh node ID",
            iroh.endpointId,
            isMono = true,
            onClick = { onPreview("Iroh node ID", iroh.endpointId) },
            onLongClick = { onCopy("Iroh node ID", iroh.endpointId) },
          )
          MachineDivider()
          val relay = iroh.relayUrl ?: "Default N0 relay (aps1 Tokyo)"
          MachineDetailField(
            "Iroh relay",
            relay,
            isMono = true,
            onClick = { onPreview("Iroh relay", relay) },
            onLongClick = { onCopy("Iroh relay", relay) },
          )
          if (iroh.directAddrs.isNotEmpty()) {
            MachineDivider()
            val addrs = iroh.directAddrs.joinToString("\n")
            MachineDetailField(
              "Direct addresses (${iroh.directAddrs.size})",
              addrs,
              isMono = true,
              onClick = { onPreview("Direct addresses", addrs) },
              onLongClick = { onCopy("Direct addresses", addrs) },
            )
          }
        }
      }

      // Card 2: System & Runtime
      MachineCard("System & Runtime") {
        val bridgeVer = details?.let { "${it.version}(v${it.protocol})" } ?: "Not reported"
        MachineDetailRow(
          "Bridge version",
          bridgeVer,
          isMono = true,
          onClick = { onPreview("Bridge version", bridgeVer) },
          onLongClick = { onCopy("Bridge version", bridgeVer) },
        )
        MachineDivider()
        val appVer = "${BuildConfig.VERSION_NAME}(v$BRIDGE_PROTOCOL_VERSION)"
        MachineDetailRow(
          "App version",
          appVer,
          isMono = true,
          onClick = { onPreview("App version", appVer) },
          onLongClick = { onCopy("App version", appVer) },
        )
        if (
          state.activeProfileId == profile.id && state.selectedAgent?.machineId == profile.machineId
        ) {
          state.selectedAgent.cwd?.let { cwd ->
            MachineDivider()
            MachineDetailField(
              "Working directory",
              cwd,
              isMono = true,
              onClick = { onPreview("Working directory", cwd) },
              onLongClick = { onCopy("Working directory", cwd) },
            )
          }
          state.selectedAgent.branch?.let { branch ->
            MachineDivider()
            MachineDetailRow(
              "Branch",
              branch,
              isMono = true,
              onClick = { onPreview("Branch", branch) },
              onLongClick = { onCopy("Branch", branch) },
            )
          }
        }
      }

      // Card 3: Actions
      MachineCard("Actions") {
        MachineDetailRow(
          "Rename machine",
          showChevron = true,
          onClick = onRename,
        )
      }
    }
  }

  previewItem?.let { (label, value) ->
    ValuePreviewDialog(
      title = label,
      value = value,
      onDismiss = { previewItem = null },
    )
  }
}
