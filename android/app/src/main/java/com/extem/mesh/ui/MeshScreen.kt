package com.extem.mesh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extem.mesh.core.EventLog
import com.extem.mesh.core.MeshRepository

/**
 * Stage 1 screen: one screen, no navigation.
 *
 * Everything it shows comes from [MeshRepository] (chat, peers, status) or [EventLog]
 * (what the radio actually did). The screen never invents data.
 */
@Composable
fun MeshScreen(
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val selfId by MeshRepository.selfId.collectAsState()
    val running by MeshRepository.running.collectAsState()
    val status by MeshRepository.status.collectAsState()
    val chat by MeshRepository.chat.collectAsState()
    val peers by MeshRepository.peers.collectAsState()
    val logLines by EventLog.recent.collectAsState()

    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(chat.size) {
        if (chat.isNotEmpty()) listState.animateScrollToItem(chat.size - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // ---------- header ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "EXTEM-Mesh",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = "node $selfId",
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
                fontSize = 14.sp
            )
        }
        Text(
            text = "Stage 1 · real Bluetooth LE link",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(Modifier.height(12.dp))

        // ---------- controls ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Status: $status", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Peers seen: ${peers.size}   ·   queued messages: ${MeshRepository.pendingCount()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        OutlinedButton(onClick = onStop) { Text("Stop mesh") }
                    } else {
                        Button(
                            onClick = { if (permissionsGranted) onStart() else onRequestPermissions() },
                            enabled = permissionsGranted
                        ) {
                            Text("Start mesh")
                        }
                    }
                    if (!permissionsGranted) {
                        Spacer(Modifier.width(12.dp))
                        OutlinedButton(onClick = onRequestPermissions) { Text("Allow Bluetooth") }
                    }
                }
                if (!permissionsGranted) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Bluetooth permissions are needed before the radio can be used.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---------- compose a message ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { if (it.length <= 200) draft = it },
                modifier = Modifier.weight(1f),
                label = { Text("Message") },
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (MeshRepository.queueMessage(draft)) draft = ""
                },
                enabled = draft.isNotBlank()
            ) {
                Text("Queue")
            }
        }
        Text(
            text = "Queued messages are sent the next time a peer is reached.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )

        Spacer(Modifier.height(8.dp))

        // ---------- chat ----------
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (chat.isEmpty()) {
                item {
                    Text(
                        text = "No messages yet. Start the mesh on both phones, then queue a message.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            items(chat, key = { it.id }) { message ->
                ChatRow(
                    from = if (message.mine) "you" else message.from,
                    text = message.text,
                    time = MeshRepository.formatTime(message.at),
                    mine = message.mine
                )
            }
        }

        // ---------- event log ----------
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            text = "Event log (last lines, also saved to extem-events.csv)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        Column(Modifier.height(96.dp)) {
            logLines.takeLast(5).forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }
    }
}

@Composable
private fun ChatRow(from: String, text: String, time: String, mine: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .background(
                    color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = "$from · $time",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
