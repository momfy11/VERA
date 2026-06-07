package com.vera.android.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vera.android.audio.VoiceState
import com.vera.android.viewmodel.ChatMessage
import com.vera.android.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSuggestions: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(ui.messages.size - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VERA") },
                actions = {
                    IconButton(onClick = onOpenSuggestions) { Icon(Icons.Default.Lightbulb, "Suggestions") }
                    IconButton(onClick = onOpenMemories) { Icon(Icons.Default.Memory, "Memories") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        bottomBar = {
            Column {
                if (ui.interimText.isNotBlank()) {
                    Text(
                        ui.interimText,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.secondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message VERA…") },
                        maxLines = 4,
                        trailingIcon = {
                            if (inputText.isNotBlank()) {
                                IconButton(onClick = { vm.sendMessage(inputText); inputText = "" }) {
                                    Icon(Icons.Default.Send, "Send")
                                }
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledIconButton(
                        onClick = vm::toggleVoice,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (ui.voiceState == VoiceState.LISTENING)
                                MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            if (ui.voiceState == VoiceState.LISTENING) Icons.Default.MicOff else Icons.Default.Mic,
                            "Voice"
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(ui.messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (ui.isTyping) {
                item { TypingIndicator() }
            }
        }
    }

    ui.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { vm.rejectAction(action.actionId) },
            title = { Text("Confirm action") },
            text = { Text(action.summary) },
            confirmButton = {
                TextButton(onClick = { vm.approveAction(action.actionId) }) { Text("Approve") }
            },
            dismissButton = {
                TextButton(onClick = { vm.rejectAction(action.actionId) }) { Text("Reject") }
            }
        )
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp,
                    )
                )
                .background(if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                msg.text,
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Text("●  ●  ●", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
