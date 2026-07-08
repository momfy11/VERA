package com.vera.android.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vera.android.data.prefs.SecurePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit, onTrainWakeWord: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    var ttsEnabled by remember { mutableStateOf(prefs.ttsEnabled) }
    var ttsRate by remember { mutableFloatStateOf(prefs.ttsRate) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Text-to-speech", Modifier.weight(1f))
                Switch(
                    checked = ttsEnabled,
                    onCheckedChange = { ttsEnabled = it; prefs.ttsEnabled = it }
                )
            }

            Spacer(Modifier.height(16.dp))

            Text("Speech rate: ${String.format("%.1f", ttsRate)}x")
            Slider(
                value = ttsRate,
                onValueChange = { ttsRate = it; prefs.ttsRate = it },
                valueRange = 0.5f..2.0f,
                steps = 14,
            )

            Spacer(Modifier.height(24.dp))

            OutlinedButton(
                onClick = onTrainWakeWord,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Train Wake Word")
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text("Sign out")
            }
        }
    }
}
