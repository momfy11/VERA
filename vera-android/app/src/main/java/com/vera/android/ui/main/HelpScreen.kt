package com.vera.android.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val BgColor = Color(0xFF0A0A12)
private val SurfaceColor = Color(0xFF13131F)
private val OrangeAccent = Color(0xFFFF6D00)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFF888899)

private data class Tip(val title: String, val body: String)

private val TIPS = listOf(
    Tip("Voice mode", "Tap the orange mic button and speak. VERA listens and responds via voice. Use headphones to avoid echo."),
    Tip("Wake word", "Say 'Hey VERA' or 'VERA' to activate — works even with screen off as long as the app has run once."),
    Tip("Reminders", "Say 'Remind me to call mom at 6pm' — VERA will speak the reminder aloud when the time comes."),
    Tip("Media control", "Say 'Play Spotify', 'Pause', 'Next song', 'Volume up' — works on any media app without their API."),
    Tip("Launch apps", "Say 'Open Instagram', 'Open Maps to Stockholm' — VERA launches the app directly."),
    Tip("Calendar", "Say 'What's on my calendar today?' or 'Add meeting Tuesday 3pm' — requires Google account connected."),
    Tip("Email", "Say 'Read my latest emails' or 'Send email to [name]' — requires Google account."),
    Tip("Web search", "Say 'Search for...' or just ask a question — VERA searches if she doesn't know the answer."),
    Tip("Weather", "Say 'What's the weather?' or 'Will it rain tomorrow?' — uses your GPS location."),
    Tip("Memory", "VERA learns your preferences automatically. View and delete stored memories in Settings → Memories."),
    Tip("Suggestions", "VERA may proactively suggest things based on your patterns. Check the lightbulb icon."),
    Tip("Google account", "Connect Google in Settings to unlock Calendar and Gmail features."),
    Tip("Approvals", "For emails and calendar changes VERA will ask you to confirm before sending — tap Approve or Reject."),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = { Text("Tips & Help", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back", tint = TextPrimary) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceColor),
            )
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            item {
                Text(
                    "What VERA can do",
                    color = OrangeAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(TIPS) { tip ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(tip.title, color = OrangeAccent, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(tip.body, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}
