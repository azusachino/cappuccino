package com.azusachino.cappuccino.ui

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R

/** Throwaway debug design. Synthetic data, no transport or persistence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RewriteDesignPrototype(initialPage: String = "Sessions", initialDark: Boolean = false) {
  var page by remember { mutableStateOf(initialPage) }
  var mode by remember { mutableStateOf(if (initialDark) "Dark" else "System") }
  val dark =
    when (mode) {
      "Dark" -> true
      "Light" -> false
      else -> isSystemInDarkTheme()
    }
  var palette by remember { mutableStateOf("Catppuccin") }
  var sessionTab by remember { mutableStateOf("Transcript") }
  var follow by remember { mutableStateOf(true) }
  var draft by remember { mutableStateOf("") }
  var systemAlerts by remember { mutableStateOf(false) }
  val scheme = previewPalette(palette, dark)
  MaterialTheme(colorScheme = scheme) {
    Surface(modifier = Modifier.fillMaxSize()) {
      Column {
        Text(
          "Design preview",
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
          color = scheme.onSurfaceVariant,
          style = MaterialTheme.typography.labelSmall,
        )
        Row(
          Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          if (page == "Session") {
            IconButton(onClick = { page = "Sessions" }) {
              Icon(painterResource(R.drawable.ic_back), contentDescription = "Back to sessions")
            }
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
              Text("Pi · Bridge maintenance", style = MaterialTheme.typography.titleMedium)
              Text("devbox / cappuccino", style = MaterialTheme.typography.labelMedium)
            }
          } else {
            Text(
              page,
              Modifier.padding(vertical = 12.dp),
              MaterialTheme.colorScheme.onSurface,
              style = MaterialTheme.typography.headlineSmall,
            )
            if (page == "Sessions") {
              IconButton(onClick = { page = "Settings" }) {
                Icon(painterResource(R.drawable.ic_machine), contentDescription = "Manage machines")
              }
            }
          }
        }
        HorizontalDivider()
        if (page == "Session") {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf("Transcript", "Tools", "Info").forEach { tab ->
              TextButton(onClick = { sessionTab = tab }) {
                Text(
                  tab,
                  color = if (sessionTab == tab) scheme.primary else scheme.onSurfaceVariant,
                )
              }
            }
          }
          HorizontalDivider()
        }
        LazyColumn(
          modifier = Modifier.weight(1f).fillMaxWidth(),
          contentPadding = PaddingValues(bottom = 16.dp),
        ) {
          when (page) {
            "Sessions" -> {
              item { Section("DEVBOX", "Connected · 2 existing sessions") }
              item {
                SessionRow("Pi", "Bridge maintenance", "Working · cappuccino") { page = "Session" }
              }
              item {
                SessionRow("Agy", "Native reader review", "Idle · app workspace") {
                  page = "Session"
                }
              }
              item { Section("LAPTOP", "Disconnected · saved connection") }
              item {
                ListItem(
                  headlineContent = { Text("Connect to discover sessions") },
                  supportingContent = { Text("Other machines remain connected.") },
                  trailingContent = { TextButton(onClick = {}) { Text("Connect") } },
                )
              }
            }
            "Session" ->
              when (sessionTab) {
                "Transcript" -> {
                  item {
                    Turn(
                      "YOU",
                      "12:40",
                      "Fix the reader jump.",
                    )
                  }
                  item {
                    Turn(
                      "PI",
                      "12:41",
                      "Tool updates were triggering follow. Separate transcript text from tool history.",
                    )
                  }
                  item {
                    Surface(
                      color = scheme.surfaceContainerLow,
                      modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                      Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(
                          Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                          Text("kotlin", style = MaterialTheme.typography.labelMedium)
                          Text(
                            "Copy",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                          )
                        }
                        Text(
                          "val answer = transcript.lastVisibleAnswer\n// tools update a separate surface",
                          Modifier.padding(top = 8.dp),
                          fontFamily = FontFamily.Monospace,
                          style = MaterialTheme.typography.bodySmall,
                        )
                      }
                    }
                  }
                  item {
                    Turn(
                      "PI",
                      "12:42",
                      "Next: verify the scroll anchor on the device.",
                    )
                  }
                  item { Turn("YOU", "12:43", "Good. Keep the controls out of the content.") }
                }
                "Tools" -> {
                  item {
                    Section("LOADED HISTORY", "Historical records, not unread activity or a queue")
                  }
                  item {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                      listOf(
                          "Shown from loaded history" to "100",
                          "Loaded operations" to "171",
                          "Loaded operations not shown" to "71",
                          "Failed results among those shown" to "1",
                        )
                        .forEach { (label, value) ->
                          Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                          ) {
                            Text(label, style = MaterialTheme.typography.bodySmall)
                            Text(value, fontFamily = FontFamily.Monospace)
                          }
                        }
                    }
                  }
                  item { HorizontalDivider(Modifier.padding(vertical = 12.dp)) }
                  item {
                    Turn(
                      "BASH · COMPLETED",
                      "12:41",
                      "./gradlew testDebugUnitTest\n156 tests passed · exit 0",
                    )
                  }
                  item {
                    Turn(
                      "BASH · FAILED RESULT",
                      "12:40",
                      "./gradlew connectedDebugAndroidTest\nRecorded exit 1; inspect the original result.",
                    )
                  }
                  item {
                    Turn(
                      "READ · RESULT NOT RECORDED",
                      "12:39",
                      "src/reader.kt\nNo result exists in the loaded history.",
                    )
                  }
                }
                else -> {
                  item {
                    Section("SESSION INFORMATION", "Canonical attachment · synthetic fixture")
                  }
                  item {
                    Turn(
                      "READINESS",
                      "",
                      "Live content is read-only in this preview. No prompt, approval or remote lifecycle action is available.",
                    )
                  }
                }
              }
            "Settings" -> {
              item {
                ListItem(
                  leadingContent = {
                    Icon(painterResource(R.drawable.ic_settings), contentDescription = null)
                  },
                  headlineContent = { Text("Appearance") },
                  supportingContent = { Text("Palette, light/dark mode and previews") },
                  trailingContent = {
                    TextButton(onClick = { page = "Appearance" }) { Text("Open") }
                  },
                )
              }
              item {
                ListItem(
                  leadingContent = {
                    Icon(painterResource(R.drawable.ic_machine), contentDescription = null)
                  },
                  headlineContent = { Text("Connections") },
                  supportingContent = { Text("Manage machines independently") },
                )
              }
              item {
                ListItem(
                  headlineContent = { Text("Completion alerts") },
                  leadingContent = {
                    Icon(painterResource(R.drawable.ic_attention), contentDescription = null)
                  },
                  supportingContent = { Text("System notifications or Telegram") },
                  trailingContent = {
                    TextButton(onClick = { page = "Alerts" }) { Text("Configure") }
                  },
                )
              }
              item {
                ListItem(
                  leadingContent = {
                    Icon(painterResource(R.drawable.ic_preview_archive), contentDescription = null)
                  },
                  headlineContent = { Text("Storage") },
                  supportingContent = {
                    Text("Saved conversations and temporary cache are separate")
                  },
                )
              }
            }
            "Alerts" -> {
              item { Section("COMPLETION", "Preview settings · alerts are not sent") }
              item {
                ListItem(
                  leadingContent = {
                    Icon(painterResource(R.drawable.ic_attention), contentDescription = null)
                  },
                  headlineContent = { Text("System notifications") },
                  supportingContent = { Text("When an agent session finishes") },
                  trailingContent = {
                    Switch(checked = systemAlerts, onCheckedChange = { systemAlerts = it })
                  },
                )
              }
              item {
                ListItem(
                  headlineContent = { Text("Telegram") },
                  supportingContent = { Text("Not configured") },
                  trailingContent = {
                    Switch(checked = false, onCheckedChange = {}, enabled = false)
                  },
                )
              }
            }
            "Appearance" -> {
              item { Section("Theme", "System, light or dark") }
              item {
                SingleChoiceSegmentedButtonRow(
                  Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                  listOf("System", "Light", "Dark").forEachIndexed { index, choice ->
                    SegmentedButton(
                      selected = mode == choice,
                      onClick = { mode = choice },
                      shape = SegmentedButtonDefaults.itemShape(index, 3),
                    ) {
                      Text(choice)
                    }
                  }
                }
              }
              item { Section("PALETTE", "Miniature native layouts, not just accent dots") }
              item {
                LazyRow(
                  contentPadding = PaddingValues(horizontal = 16.dp),
                  horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                  items(listOf("Default", "Dynamic", "Catppuccin")) { name ->
                    val previewScheme = previewPalette(name, dark)
                    MaterialTheme(colorScheme = previewScheme) {
                      Card(
                        onClick = { palette = name },
                        enabled =
                          name != "Dynamic" || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        border =
                          BorderStroke(
                            if (palette == name) 3.dp else 1.dp,
                            if (palette == name) previewScheme.primary
                            else previewScheme.outlineVariant,
                          ),
                        modifier = Modifier.width(120.dp).semantics { selected = palette == name },
                        colors =
                          CardDefaults.cardColors(
                            containerColor = previewScheme.surfaceContainerLow
                          ),
                      ) {
                        Column(
                          Modifier.padding(12.dp),
                          verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                          Text("Sessions", style = MaterialTheme.typography.titleSmall)
                          Box(
                            Modifier.fillMaxWidth().height(20.dp),
                            contentAlignment = Alignment.CenterEnd,
                          ) {
                            Text(if (palette == name) "✓" else "", color = previewScheme.primary)
                          }
                          HorizontalDivider()
                          Text(
                            "devbox / Pi",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                          )
                          Surface(color = previewScheme.primaryContainer) {
                            Text(
                              "Native output",
                              Modifier.padding(8.dp),
                              color = previewScheme.onPrimaryContainer,
                            )
                          }
                          Text(
                            "Transcript    Tools",
                            color = previewScheme.primary,
                            style = MaterialTheme.typography.labelSmall,
                          )
                          Text(
                            name,
                            style = MaterialTheme.typography.labelLarge,
                          )
                        }
                      }
                    }
                  }
                }
              }
              item {
                Text(
                  "Selected palette: $palette",
                  Modifier.padding(16.dp),
                  style = MaterialTheme.typography.bodySmall,
                  color = scheme.onSurfaceVariant,
                )
              }
            }
            else -> {
              item { Section("SAVED CONVERSATIONS", "Offline · timestamped · read-only") }
              item {
                ListItem(
                  headlineContent = { Text("Bridge maintenance") },
                  supportingContent = { Text("devbox · saved today at 12:30") },
                )
              }
            }
          }
        }
        HorizontalDivider()
        if (page == "Session") {
          Column {
            Row(
              Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (sessionTab == "Transcript") {
                FilterChip(
                  selected = follow,
                  onClick = { follow = !follow },
                  label = { Text(if (follow) "Following" else "Follow") },
                  leadingIcon = {
                    Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null)
                  },
                )
                TextButton(onClick = { follow = true }, enabled = !follow) {
                  Icon(painterResource(R.drawable.ic_arrow_downward), contentDescription = null)
                  Text("Latest", Modifier.padding(start = 8.dp))
                }
              } else {
                Text(
                  "Loaded history · read-only",
                  style = MaterialTheme.typography.labelMedium,
                  color = scheme.onSurfaceVariant,
                )
              }
            }
            if (sessionTab == "Transcript") {
              Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.Bottom,
              ) {
                OutlinedTextField(
                  value = draft,
                  onValueChange = { draft = it },
                  modifier = Modifier.weight(1f),
                  placeholder = { Text("Message Pi") },
                  maxLines = 4,
                )
                TextButton(onClick = {}, enabled = false) { Text("Send") }
              }
            }
          }
        } else {
          NavigationBar {
            listOf(
                "Sessions" to R.drawable.ic_chat,
                "Saved" to R.drawable.ic_preview_archive,
                "Settings" to R.drawable.ic_settings,
              )
              .forEach { (destination, icon) ->
                NavigationBarItem(
                  selected =
                    page == destination ||
                      (destination == "Settings" && page in listOf("Appearance", "Alerts")),
                  onClick = { page = destination },
                  icon = { Icon(painterResource(icon), contentDescription = null) },
                  label = { Text(destination) },
                )
              }
          }
        }
      }
    }
  }
}

@Composable
private fun Section(title: String, subtitle: String) {
  Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(
      title,
      color = MaterialTheme.colorScheme.primary,
      style = MaterialTheme.typography.labelLarge,
    )
    Text(
      subtitle,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}

@Composable
private fun SessionRow(provider: String, title: String, subtitle: String, open: () -> Unit) {
  ListItem(
    leadingContent = {
      Icon(
        painterResource(
          if (provider == "Pi") R.drawable.ic_agent_pi else R.drawable.ic_agent_spark
        ),
        contentDescription = provider,
        tint = MaterialTheme.colorScheme.primary,
      )
    },
    headlineContent = { Text(title) },
    supportingContent = { Text(subtitle) },
    trailingContent = { OutlinedButton(onClick = open) { Text("Read") } },
  )
}

@Composable
private fun Turn(speaker: String, timestamp: String, text: String) {
  Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(
        speaker,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.primary,
        style = MaterialTheme.typography.labelMedium,
      )
      Text(
        timestamp,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
      )
    }
    Text(text, style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider(Modifier.padding(top = 8.dp))
  }
}
