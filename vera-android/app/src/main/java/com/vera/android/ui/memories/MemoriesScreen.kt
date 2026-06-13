package com.vera.android.ui.memories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vera.android.data.api.MemoryItem
import com.vera.android.data.api.VeraApi
import com.vera.android.data.prefs.SecurePrefs
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { SecurePrefs(context) }
    val api = remember { VeraApi(com.vera.android.data.buildHttpClient()) }
    val scope = rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<MemoryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        prefs.sessionToken?.let { token ->
            runCatching { memories = api.getMemories(token) }
        }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { padding ->
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(contentPadding = padding) {
                items(memories, key = { it.id }) { mem ->
                    ListItem(
                        headlineContent = { Text(mem.text) },
                        supportingContent = { Text("${mem.kind} · ${mem.ts.take(10)}") },
                        trailingContent = {
                            IconButton(onClick = {
                                scope.launch {
                                    prefs.sessionToken?.let { api.deleteMemory(it, mem.id) }
                                    memories = memories.filter { it.id != mem.id }
                                }
                            }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
