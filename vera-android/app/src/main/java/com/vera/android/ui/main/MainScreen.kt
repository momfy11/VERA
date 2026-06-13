package com.vera.android.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.android.audio.VoiceState
import com.vera.android.viewmodel.ChatMessage
import com.vera.android.viewmodel.MainViewModel
import kotlinx.coroutines.launch

private val BgColor = Color(0xFF0A0A12)
private val SurfaceColor = Color(0xFF13131F)
private val UserBubble = Color(0xFF6750A4)
private val AssistantBubble = Color(0xFF1C1C2E)
private val OrangeAccent = Color(0xFFFF6D00)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFF888899)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(ui.messages.size - 1) }
    }

    if (ui.firstLogin) {
        WelcomeDialog(displayName = ui.displayName, onDismiss = vm::dismissFirstLogin)
    }

    ui.pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { vm.rejectAction(action.actionId) },
            containerColor = SurfaceColor,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Confirm") },
            text = { Text(action.summary) },
            confirmButton = { TextButton(onClick = { vm.approveAction(action.actionId) }) { Text("Approve", color = OrangeAccent) } },
            dismissButton = { TextButton(onClick = { vm.rejectAction(action.actionId) }) { Text("Reject", color = TextSecondary) } },
        )
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("VERA", color = OrangeAccent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Box(
                            modifier = Modifier.size(7.dp).clip(CircleShape)
                                .background(if (ui.isConnected) Color(0xFF22CC66) else Color(0xFF666666))
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHelp) { Icon(Icons.Default.Help, "Help", tint = TextSecondary) }
                    IconButton(onClick = onOpenSuggestions) { Icon(Icons.Default.Lightbulb, "Suggestions", tint = TextSecondary) }
                    IconButton(onClick = onOpenMemories) { Icon(Icons.Default.Memory, "Memories", tint = TextSecondary) }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, "Settings", tint = TextSecondary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceColor),
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .background(SurfaceColor)
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                if (ui.interimText.isNotBlank()) {
                    Text(
                        ui.interimText,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message VERA…", color = TextSecondary, fontSize = 14.sp) },
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedBorderColor = OrangeAccent,
                            unfocusedBorderColor = Color(0xFF2A2A3A),
                            cursorColor = OrangeAccent,
                            focusedContainerColor = Color(0xFF0D0D1A),
                            unfocusedContainerColor = Color(0xFF0D0D1A),
                        ),
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank()) { vm.sendMessage(inputText); inputText = "" }
                        }),
                        trailingIcon = {
                            if (inputText.isNotBlank()) {
                                IconButton(onClick = { vm.sendMessage(inputText); inputText = "" }) {
                                    Icon(Icons.Default.Send, "Send", tint = OrangeAccent)
                                }
                            }
                        }
                    )
                    FilledIconButton(
                        onClick = vm::toggleVoice,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = if (ui.voiceState == VoiceState.LISTENING) Color(0xFFCC2200) else OrangeAccent,
                        ),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (ui.voiceState == VoiceState.LISTENING) Icons.Default.MicOff else Icons.Default.Mic,
                            "Voice",
                            tint = Color.White,
                        )
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            items(ui.messages, key = { it.id }) { msg -> MessageBubble(msg) }
            if (ui.isTyping) { item { TypingIndicator() } }
            if (ui.error != null) {
                item { Text(ui.error!!, color = Color(0xFFFF5555), fontSize = 13.sp, modifier = Modifier.padding(8.dp)) }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            Box(
                modifier = Modifier.padding(end = 8.dp).size(28.dp).clip(CircleShape).background(OrangeAccent),
                contentAlignment = Alignment.Center,
            ) {
                Text("V", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Box(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(
                    topStart = 18.dp, topEnd = 18.dp,
                    bottomStart = if (isUser) 18.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 18.dp,
                ))
                .background(if (isUser) UserBubble else AssistantBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(msg.text, color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier.size(28.dp).clip(CircleShape).background(OrangeAccent),
            contentAlignment = Alignment.Center,
        ) {
            Text("V", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        Box(
            modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(AssistantBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text("● ● ●", color = TextSecondary, fontSize = 13.sp, letterSpacing = 3.sp)
        }
    }
}

@Composable
fun WelcomeDialog(displayName: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        titleContentColor = OrangeAccent,
        textContentColor = TextPrimary,
        title = { Text("Welcome${if (displayName.isNotBlank()) ", $displayName" else ""}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("I'm VERA — your personal AI assistant, always here.")
                Spacer(Modifier.height(4.dp))
                Text("What I can do:", color = OrangeAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                listOf(
                    "Answer questions & search the web",
                    "Manage calendar & Gmail",
                    "Control Spotify & any media",
                    "Launch & interact with any app",
                    "Set reminders — spoken aloud",
                    "Remember your preferences",
                    "Proactive tips & suggestions",
                ).forEach { Text("  • $it", fontSize = 14.sp) }
                Spacer(Modifier.height(4.dp))
                Text("Tap ? anytime for tips.", color = TextSecondary, fontSize = 13.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Let's go", color = OrangeAccent, fontWeight = FontWeight.Bold) }
        }
    )
}
