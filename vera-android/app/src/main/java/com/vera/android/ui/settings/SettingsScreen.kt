package com.vera.android.ui.settings

import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vera.android.data.api.GoogleStatus
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit, onTrainWakeWord: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    val api = remember { VeraApi() }
    val scope = rememberCoroutineScope()

    var ttsEnabled by remember { mutableStateOf(prefs.ttsEnabled) }
    var ttsRate by remember { mutableFloatStateOf(prefs.ttsRate) }

    var googleStatus by remember { mutableStateOf<GoogleStatus?>(null) }
    var googleLoading by remember { mutableStateOf(false) }
    var googleError by remember { mutableStateOf<String?>(null) }

    // Load Google status on first composition
    LaunchedEffect(Unit) {
        prefs.sessionToken?.let { token ->
            runCatching { googleStatus = api.getGoogleStatus(token) }
        }
    }

    fun connectGmail() {
        scope.launch {
            googleLoading = true
            googleError = null
            val token = prefs.sessionToken ?: return@launch
            runCatching {
                val url = api.getGoogleAuthUrl(token)
                CustomTabsIntent.Builder().build().launchUrl(context, url.toUri())
                // Poll for completion (up to 3 min, every 3 s)
                repeat(60) {
                    delay(3_000)
                    val status = api.getGoogleStatus(token)
                    googleStatus = status
                    if (status.connected || (!status.in_progress && status.error != null)) return@repeat
                }
            }.onFailure { googleError = it.message }
            googleLoading = false
        }
    }

    fun disconnectGmail() {
        scope.launch {
            googleLoading = true
            val token = prefs.sessionToken ?: return@launch
            runCatching {
                val req = okhttp3.Request.Builder()
                    .url("https://vera-app.hopto.org/api/google/disconnect")
                    .post(okhttp3.RequestBody.create(null, ByteArray(0)))
                    .header("X-Session-Token", token)
                    .build()
                com.vera.android.data.buildHttpClient().newCall(req).execute().close()
                googleStatus = api.getGoogleStatus(token)
            }.onFailure { googleError = it.message }
            googleLoading = false
        }
    }

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

            // Gmail section
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))
            Text("Gmail / Google", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))

            when {
                googleLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(if (googleStatus?.connected == true) "Disconnecting…" else "Connecting…",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                googleStatus?.connected == true -> {
                    Text("Connected: ${googleStatus?.email ?: "Google account"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { disconnectGmail() }, Modifier.fillMaxWidth()) {
                        Text("Disconnect Gmail")
                    }
                }
                else -> {
                    googleError?.let {
                        Text("Error: $it", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                    }
                    OutlinedButton(onClick = { connectGmail() }, Modifier.fillMaxWidth()) {
                        Text("Connect Gmail")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider()
            Spacer(Modifier.height(16.dp))

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
