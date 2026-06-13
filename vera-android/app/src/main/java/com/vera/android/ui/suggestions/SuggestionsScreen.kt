package com.vera.android.ui.suggestions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.vera.android.data.api.Suggestion
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    val api = remember { VeraApi(com.vera.android.data.buildHttpClient()) }
    val scope = rememberCoroutineScope()
    var suggestions by remember { mutableStateOf<List<Suggestion>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        prefs.sessionToken?.let { token ->
            runCatching { suggestions = api.getSuggestions(token).filter { it.status == "new" } }
        }
        loading = false
    }

    fun patch(id: String, action: String) {
        scope.launch {
            prefs.sessionToken?.let { api.patchSuggestion(it, id, action) }
            suggestions = suggestions.filter { it.id != id }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suggestions") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (suggestions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No suggestions right now.")
            }
        } else {
            LazyColumn(contentPadding = padding) {
                items(suggestions, key = { it.id }) { sug ->
                    ListItem(
                        headlineContent = { Text(sug.title) },
                        supportingContent = { Text(sug.reason) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { patch(sug.id, "accepted") }) { Icon(Icons.Default.Check, "Accept") }
                                IconButton(onClick = { patch(sug.id, "snoozed") }) { Icon(Icons.Default.Schedule, "Snooze") }
                                IconButton(onClick = { patch(sug.id, "rejected") }) { Icon(Icons.Default.Close, "Reject") }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
