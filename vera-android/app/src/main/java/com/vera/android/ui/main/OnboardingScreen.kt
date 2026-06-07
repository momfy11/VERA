package com.vera.android.ui.main

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var micGranted by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(false) }
    var notifGranted by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        micGranted = perms[Manifest.permission.RECORD_AUDIO] == true
    }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { perms ->
        locationGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifGranted = granted
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Set up VERA", style = MaterialTheme.typography.headlineMedium)
        Text("Grant permissions so VERA can assist you fully.", style = MaterialTheme.typography.bodyMedium)

        Spacer(Modifier.height(32.dp))

        PermissionRow(
            icon = Icons.Default.Mic,
            title = "Microphone",
            subtitle = "Voice input and wake word",
            granted = micGranted,
            onGrant = { micLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        )

        PermissionRow(
            icon = Icons.Default.LocationOn,
            title = "Location (always)",
            subtitle = "VERA knows where you are at all times",
            granted = locationGranted,
            onGrant = {
                locationLauncher.launch(arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ))
            }
        )

        PermissionRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            subtitle = "VERA can alert you proactively",
            granted = notifGranted,
            onGrant = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        )

        PermissionRow(
            icon = Icons.Default.MusicNote,
            title = "Media control",
            subtitle = "Control Spotify, YouTube Music, etc. without APIs",
            granted = false,
            isManual = true,
            onGrant = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        )

        PermissionRow(
            icon = Icons.Default.PhoneAndroid,
            title = "App control (Accessibility)",
            subtitle = "VERA can interact with any app on your behalf",
            granted = false,
            isManual = true,
            onGrant = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )

        Spacer(Modifier.height(32.dp))

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Continue")
        }
        TextButton(onClick = onDone) { Text("Skip for now") }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    isManual: Boolean = false,
    onGrant: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Icon(Icons.Default.CheckCircle, "Granted", tint = MaterialTheme.colorScheme.primary)
        } else {
            TextButton(onClick = onGrant) { Text(if (isManual) "Open Settings" else "Allow") }
        }
    }
}
