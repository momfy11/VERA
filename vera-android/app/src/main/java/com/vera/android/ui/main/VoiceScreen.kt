package com.vera.android.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.android.audio.VoiceState
import com.vera.android.avatar.VeraAvatar3D
import com.vera.android.viewmodel.ActionRequest
import com.vera.android.viewmodel.FaceState
import com.vera.android.viewmodel.MainViewModel

private val BgColor = Color(0xFF0A0A12)
private val SurfaceColor = Color(0xFF13131F)
private val OrangeAccent = Color(0xFFFF6D00)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFF888899)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceScreen(
    vm: MainViewModel,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val faceState by vm.faceState.collectAsState()
    val avatarState by vm.avatarState.collectAsState()
    var micButtonActive by remember { mutableStateOf(false) }

    LaunchedEffect(ui.voiceState) {
        if (ui.voiceState == VoiceState.IDLE) micButtonActive = false
    }

    if (ui.firstLogin) {
        WelcomeDialog(displayName = ui.displayName, onDismiss = vm::dismissFirstLogin)
    }

    ui.pendingAction?.let { action ->
        ActionDialog(action, vm)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BgColor)
            .systemBarsPadding(),
    ) {
        // ── Top bar ────────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("VERA", color = OrangeAccent, fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (ui.isConnected) Color(0xFF22CC66) else Color(0xFF444455))
            )
        }

        // ── Face animation ─────────────────────────────────────────────────────
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            VeraAvatar3D(renderState = avatarState)

            // Status / interim text
            val statusText = when (faceState) {
                FaceState.LISTENING -> if (ui.interimText.isNotBlank()) ui.interimText else "Listening…"
                FaceState.THINKING  -> "Thinking…"
                FaceState.SPEAKING  -> ""
                FaceState.IDLE      -> if (ui.error != null) ui.error!! else ""
            }
            if (statusText.isNotBlank()) {
                Text(
                    text = statusText,
                    color = if (ui.error != null && faceState == FaceState.IDLE) Color(0xFFFF5555) else TextSecondary,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        }

        // ── Bottom bar ─────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(SurfaceColor)
                .navigationBarsPadding()
                .padding(horizontal = 32.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onOpenHistory, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.History, "History", tint = TextSecondary, modifier = Modifier.size(26.dp))
            }

            FilledIconButton(
                onClick = {
                    if (micButtonActive) {
                        micButtonActive = false
                        vm.stopVoice()
                    } else {
                        micButtonActive = true
                        vm.startVoiceManual()
                    }
                },
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (micButtonActive) Color(0xFFCC2200) else OrangeAccent,
                ),
                modifier = Modifier.size(64.dp),
            ) {
                Icon(
                    if (micButtonActive) Icons.Default.MicOff else Icons.Default.Mic,
                    "Voice",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }

            IconButton(onClick = onOpenSettings, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Settings, "Settings", tint = TextSecondary, modifier = Modifier.size(26.dp))
            }
        }
    }
}


@Composable
private fun ActionDialog(action: ActionRequest, vm: MainViewModel) {
    AlertDialog(
        onDismissRequest = { vm.rejectAction(action.actionId) },
        containerColor = SurfaceColor,
        titleContentColor = TextPrimary,
        textContentColor = TextSecondary,
        title = { Text("Confirm") },
        text = { Text(action.summary) },
        confirmButton = {
            TextButton(onClick = { vm.approveAction(action.actionId) }) {
                Text("Approve", color = OrangeAccent)
            }
        },
        dismissButton = {
            TextButton(onClick = { vm.rejectAction(action.actionId) }) {
                Text("Reject", color = TextSecondary)
            }
        },
    )
}
