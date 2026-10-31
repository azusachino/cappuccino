package com.azusachino.cappuccino.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.core.ConversationPart
import com.azusachino.cappuccino.core.ConversationTurn
import com.azusachino.cappuccino.core.TranscriptSegment
import com.azusachino.cappuccino.core.transcriptSegments
import com.azusachino.cappuccino.core.visibleInChat
import com.azusachino.cappuccino.io.SessionStats
import java.time.ZoneId

@Composable
internal fun SessionStatusLine(stats: SessionStats, agent: String? = null) {
  val colors = MaterialTheme.appColors
  val tokens = stats.contextTokens
  val window = stats.contextWindow ?: defaultContextWindow(stats.model, agent)
  val percent =
    when {
      tokens != null -> (tokens * 100 / window).coerceIn(0, 100).toInt()
      else ->
        stats.rateLimits?.windows?.firstOrNull { it.window == "session" }?.usedPercent?.toInt()
    }

  val rateLimitWindows =
    stats.rateLimits?.windows?.filterNot {
      it.window == "session" && percent != null && tokens == null
    } ?: emptyList()

  if (percent == null && rateLimitWindows.isEmpty()) return

  Row(
    Modifier.fillMaxWidth()
      .horizontalScroll(rememberScrollState())
      .padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    if (percent != null) {
      Box(
        Modifier.size(width = 52.dp, height = 4.dp)
          .background(colors.card2, RoundedCornerShape(2.dp))
          .clip(RoundedCornerShape(2.dp))
      ) {
        Box(
          Modifier.fillMaxHeight()
            .fillMaxWidth(percent / 100f)
            .background(
              when {
                percent >= 85 -> MaterialTheme.colorScheme.error
                percent >= 70 -> colors.warn
                else -> colors.accent
              }
            )
        )
      }
      Text(
        if (tokens != null) "$percent% · ${formatTokens(tokens)} / ${formatTokens(window)}"
        else "$percent%",
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
        color =
          when {
            percent >= 85 -> MaterialTheme.colorScheme.error
            percent >= 70 -> colors.warn
            else -> colors.text
          },
      )
    }

    for (win in rateLimitWindows) {
      Text("·", style = MaterialTheme.typography.labelSmall, color = colors.faint)
      val prefix = if (win.window == "quota") stats.rateLimits?.provider ?: "quota" else win.window
      val resetText =
        win.resetsIn?.let { if (it.startsWith("~") || it.startsWith("↻")) " $it" else " ~$it" }
          ?: ""
      val label = "$prefix: ${win.usedPercent}%$resetText"
      val winColor =
        when {
          win.usedPercent >= 85 -> MaterialTheme.colorScheme.error
          win.usedPercent >= 70 -> colors.warn
          else -> colors.muted
        }
      Text(
        label,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
        color = winColor,
      )
    }
  }
}

@Composable
fun ConversationTurnRow(
  turn: ConversationTurn,
  modifier: Modifier = Modifier,
  timeZone: ZoneId = ZoneId.systemDefault(),
  assistantLabel: String = "Assistant",
  onInspect: () -> Unit = {},
  showToolsInline: Boolean = true,
  onTextSizeChanged: (() -> Unit)? = null,
  newest: Boolean = false,
) {
  val textModifier =
    remember(onTextSizeChanged) {
      if (onTextSizeChanged == null) Modifier else Modifier.onSizeChanged { onTextSizeChanged() }
    }
  val isUser = turn.role.lowercase() == "user"
  val parts = buildList {
    turn.text?.takeIf { it.isNotBlank() }?.let { add(ConversationPart.Text(it)) }
    addAll(turn.parts)
  }
  val shownParts = parts.filter {
    it.visibleInChat || (showToolsInline && it is ConversationPart.ToolCall)
  }
  val segments = remember(shownParts) { transcriptSegments(shownParts) }
  if (segments.isEmpty()) return
  // Telegram grouping: prose blocks in one turn share a bubble shape; inner meeting
  // corners collapse so consecutive blocks read as one lump with soft seams.
  val proseSlots =
    remember(segments, isUser) {
      var n = 0
      segments.map { seg ->
        if (seg is TranscriptSegment.Prose && seg.part is ConversationPart.Text && !isUser) {
          n++
          n - 1
        } else null
      }
    }
  val proseCount = remember(proseSlots) { proseSlots.filterNotNull().size }
  fun bubbleShape(slot: Int): RoundedCornerShape {
    val outer = 16.dp
    val seam = 6.dp
    return when {
      proseCount == 1 ->
        RoundedCornerShape(topStart = 4.dp, topEnd = outer, bottomStart = outer, bottomEnd = outer)
      slot == 0 ->
        RoundedCornerShape(topStart = 4.dp, topEnd = outer, bottomEnd = seam, bottomStart = seam)
      slot == proseCount - 1 ->
        RoundedCornerShape(topStart = seam, topEnd = seam, bottomEnd = outer, bottomStart = outer)
      else -> RoundedCornerShape(seam)
    }
  }
  Column(
    modifier.fillMaxWidth().semantics {
      contentDescription = if (isUser) "You" else assistantLabel
    },
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    segments.forEachIndexed { index, segment ->
      key(turn.id, index) {
        when (segment) {
          is TranscriptSegment.Activity -> ActivityGroup(segment.parts, newest, onInspect)
          is TranscriptSegment.Prose -> {
            val part = segment.part
            if (part is ConversationPart.ResponseStatus) ResponseStatusRow(part)
            // Thinking and tool calls arrive grouped as activity, never as prose.
            else if (part !is ConversationPart.Text) Unit
            else if (isUser) {
              val isLastUserProse =
                index ==
                  segments.indexOfLast {
                    it is TranscriptSegment.Prose && it.part is ConversationPart.Text
                  }
              UserPromptRow(
                body = {
                  MessageBody(
                    part.text,
                    modifier = textModifier,
                    markdown = false,
                    onInspect = onInspect,
                    transcript = true,
                  )
                },
                time =
                  turn.timestamp
                    ?.takeIf { isLastUserProse }
                    ?.let { formatShortConversationTime(it, timeZone) },
                timeDescription =
                  turn.timestamp
                    ?.takeIf { isLastUserProse }
                    ?.let { formatConversationTimestamp(it, timeZone) },
              )
            } else {
              val slot = proseSlots.getOrNull(index) ?: 0
              val isLastProse = (slot == proseCount - 1)
              val formattedTime =
                turn.timestamp
                  ?.takeIf { isLastProse }
                  ?.let { formatShortConversationTime(it, timeZone) }
              AgentResponseRow(
                body = {
                  MessageBody(
                    part.text,
                    onInspect = onInspect,
                    modifier = textModifier,
                    transcript = true,
                  )
                },
                assistantLabel = assistantLabel,
                time = formattedTime,
                timeDescription =
                  turn.timestamp
                    ?.takeIf { isLastProse }
                    ?.let { formatConversationTimestamp(it, timeZone) },
                showLabel = (slot == 0),
                shape = bubbleShape(slot),
              )
            }
          }
        }
      }
    }
  }
}

internal fun defaultContextWindow(model: String?, agent: String? = null): Long {
  val m = model?.trim()?.lowercase()
  val a = agent?.trim()?.lowercase()
  return when {
    m != null && ("terra" in m || "gpt-5" in m || "gpt-6" in m) -> 500_000L
    m != null && ("gemini" in m || "glm" in m) -> 1_000_000L
    m != null && "deepseek" in m -> 64_000L
    m != null && ("gpt-4" in m || "o1" in m || "o3" in m || "qwen" in m) -> 128_000L
    a in setOf("agy", "antigravity", "antigravity-cli") -> 1_000_000L
    else -> 200_000L
  }
}

/** 1234 as "1.2K", 200000 as "200K", 1234567 as "1.2M". */
internal fun formatTokens(count: Long): String =
  when {
    count >= 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fM", count / 1_000_000.0)
    count >= 1_000 -> String.format(java.util.Locale.ROOT, "%.1fK", count / 1_000.0)
    else -> count.toString()
  }.replace(".0", "")
