package com.vera.android.ui.settings

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import com.vera.android.audio.VeraForegroundService
import com.vera.android.data.api.VeraApi
import com.vera.android.data.buildHttpClient
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SAMPLE_RATE = 16000
private const val RECORD_SECONDS = 2
private const val RECORD_BYTES = SAMPLE_RATE * RECORD_SECONDS * 2  // int16 = 2 bytes
private const val TARGET_TEMPLATES = 10

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordTrainingScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { SecurePrefs(context) }
    val api = remember { VeraApi(buildHttpClient()) }

    var templateCount by remember { mutableIntStateOf(0) }
    var countdown by remember { mutableIntStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        val token = prefs.sessionToken ?: return@LaunchedEffect
        templateCount = api.getWakeTemplateCount(token)
        statusText = when {
            templateCount >= TARGET_TEMPLATES -> "Wake word fully trained (${templateCount} samples)."
            templateCount > 0 -> "${templateCount}/${TARGET_TEMPLATES} samples recorded."
            else -> "No samples yet. Record ${TARGET_TEMPLATES} to train your voice."
        }
        isLoading = false
    }

    fun recordAndUpload() {
        val token = prefs.sessionToken ?: run { statusText = "Not logged in."; return }
        scope.launch {
            // Countdown 3-2-1
            for (i in 3 downTo 1) {
                countdown = i
                delay(1000)
            }
            countdown = 0

            // Pause wake word service so it releases the mic
            context.startService(
                Intent(context, VeraForegroundService::class.java).setAction(VeraForegroundService.ACTION_PAUSE_WAKE)
            )

            isRecording = true
            statusText = "Recording... say \"Hey VERA\""

            val pcm = withContext(Dispatchers.IO) {
                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val ar = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, RECORD_BYTES),
                )
                ar.startRecording()
                val buf = ByteArray(RECORD_BYTES)
                var pos = 0
                val tmp = ByteArray(minBuf)
                while (pos < RECORD_BYTES) {
                    val read = ar.read(tmp, 0, minOf(tmp.size, RECORD_BYTES - pos))
                    if (read > 0) { tmp.copyInto(buf, pos, 0, read); pos += read }
                }
                ar.stop()
                ar.release()
                buf
            }

            isRecording = false
            statusText = "Uploading sample..."

            val ok = withContext(Dispatchers.IO) { api.uploadWakeSample(token, pcm) }

            // Resume wake word service
            context.startService(
                Intent(context, VeraForegroundService::class.java).setAction(VeraForegroundService.ACTION_RESUME_WAKE)
            )

            if (ok) {
                templateCount = api.getWakeTemplateCount(token)
                statusText = when {
                    templateCount >= TARGET_TEMPLATES -> "Done! Wake word fully trained."
                    else -> "Sample ${templateCount}/${TARGET_TEMPLATES} saved. Keep going!"
                }
            } else {
                statusText = "Upload failed — check connection or train openwakeword models on server."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wake Word Training") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // Progress card
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Voice Templates", fontWeight = FontWeight.Bold)
                    if (isLoading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { (templateCount.toFloat() / TARGET_TEMPLATES).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "${templateCount} / ${TARGET_TEMPLATES} samples",
                            fontSize = 14.sp,
                            color = if (templateCount >= TARGET_TEMPLATES)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (templateCount >= TARGET_TEMPLATES) {
                            Text(
                                "Active — using your voice for detection",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            // Recording card
            if (!isLoading) {
                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Countdown big number
                        AnimatedVisibility(visible = countdown > 0) {
                            Text(
                                "$countdown",
                                fontSize = 72.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }

                        // Mic pulse indicator when recording
                        if (isRecording) {
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Default.Mic, "Recording", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
                            }
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }

                        if (statusText.isNotBlank()) {
                            Text(statusText, fontSize = 14.sp)
                        }

                        Button(
                            onClick = { recordAndUpload() },
                            enabled = !isRecording && countdown == 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                when {
                                    templateCount == 0 -> "Record Sample 1"
                                    templateCount < TARGET_TEMPLATES -> "Record Sample ${templateCount + 1}"
                                    else -> "Record Extra Sample"
                                }
                            )
                        }

                        if (templateCount > 0) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        val token = prefs.sessionToken ?: return@launch
                                        api.clearWakeEnrollment(token)
                                        templateCount = 0
                                        statusText = "All templates cleared. Record ${TARGET_TEMPLATES} new samples."
                                    }
                                },
                                enabled = !isRecording && countdown == 0,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            ) {
                                Text("Clear All Templates")
                            }
                        }
                    }
                }
            }

            // Instructions
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("How it works", fontWeight = FontWeight.Bold)
                    listOf(
                        "Record 10 samples of your wake phrase",
                        "Speak naturally — try different tones",
                        "Server builds a voice template from your recordings",
                        "Detection matches YOUR voice, not just the words",
                        "Works much better than generic speech recognition",
                    ).forEach {
                        Text("• $it", fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp))
                    }
                }
            }
        }
    }
}
