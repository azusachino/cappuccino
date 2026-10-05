package com.azusachino.cappuccino.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.azusachino.cappuccino.R
import com.azusachino.cappuccino.core.MessageDelivery

private enum class Screen(val title: Int, val icon: Int, val heading: Int, val detail: Int) {
  CHATS(R.string.chats, R.drawable.ic_chat, R.string.no_agents, R.string.attachment_unavailable),
  ATTENTION(
    R.string.attention,
    R.drawable.ic_attention,
    R.string.no_requests,
    R.string.bridge_required,
  ),
  MACHINES(
    R.string.machines,
    R.drawable.ic_machine,
    R.string.no_machines,
    R.string.pairing_unavailable,
  ),
}

@Composable
fun CappuccinoShell() {
  var screen by rememberSaveable { mutableStateOf(Screen.CHATS) }
  var delivery by rememberSaveable { mutableStateOf(MessageDelivery.FOLLOW_UP) }
  Scaffold(
    bottomBar = {
      NavigationBar {
        Screen.entries.forEach { item ->
          NavigationBarItem(
            selected = screen == item,
            onClick = { screen = item },
            icon = { Icon(painterResource(item.icon), contentDescription = null) },
            label = { Text(stringResource(item.title)) },
          )
        }
      }
    }
  ) { padding ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .padding(padding)
          .verticalScroll(rememberScrollState())
          .padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
      Text(
        stringResource(screen.title),
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
      )
      Icon(painterResource(screen.icon), contentDescription = null, modifier = Modifier.size(48.dp))
      Text(
        stringResource(screen.heading),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
      )
      Text(stringResource(screen.detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
      if (screen == Screen.CHATS) {
        Text(stringResource(R.string.message_timing), style = MaterialTheme.typography.titleMedium)
        Column(Modifier.selectableGroup()) {
          MessageDelivery.entries.forEach { choice ->
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .heightIn(min = 48.dp)
                  .selectable(
                    selected = delivery == choice,
                    onClick = { delivery = choice },
                    role = Role.RadioButton,
                  ),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              RadioButton(selected = delivery == choice, onClick = null)
              Text(
                stringResource(
                  if (choice == MessageDelivery.NUDGE) R.string.nudge else R.string.follow_up
                )
              )
            }
          }
        }
        OutlinedTextField(
          value = "",
          onValueChange = {},
          enabled = false,
          label = { Text(stringResource(R.string.message)) },
          placeholder = { Text(stringResource(R.string.attach_to_reply)) },
          modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {}, enabled = false) { Text(stringResource(R.string.send)) }
      }
    }
  }
}
