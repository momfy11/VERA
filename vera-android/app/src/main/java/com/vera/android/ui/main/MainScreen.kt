package com.vera.android.ui.main

import android.Manifest
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vera.android.audio.VoiceState
import com.vera.android.viewmodel.ChatMessage
import com.vera.android.viewmodel.MainViewModel
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

private val BgColor = Color(0xFF0A0A12)
private val SurfaceColor = Color(0xFF13131F)
private val UserBubble = Color(0xFF6750A4)
private val AssistantBubble = Color(0xFF1C1C2E)
private val OrangeAccent = Color(0xFFFF6D00)
private val TextPrimary = Color(0xFFF0F0F0)
private val TextSecondary = Color(0xFF888899)

// ── Image helpers ─────────────────────────────────────────────────────────────

private fun scaleBitmapBytes(bytes: ByteArray, maxDim: Int = 1024): ByteArray {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    val sample = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxDim)
    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: return bytes
    val scale = minOf(1f, maxDim.toFloat() / maxOf(bmp.width, bmp.height))
    val out = if (scale < 1f)
        Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
    else bmp
    return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
}

private fun uriToBase64(context: android.content.Context, uri: Uri): Pair<String, String>? =
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        Pair(Base64.encodeToString(scaleBitmapBytes(bytes), Base64.NO_WRAP), "image/jpeg")
    }.getOrNull()

private fun bitmapToBase64(bitmap: Bitmap): Pair<String, String> {
    val baos = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
    return Pair(Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP), "image/jpeg")
}

private fun pasteImageFromClipboard(context: android.content.Context): Pair<String, String>? {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = cm.primaryClip ?: return null
    for (i in 0 until clip.itemCount) {
        val uri = clip.getItemAt(i).uri ?: continue
        if (context.contentResolver.getType(uri)?.startsWith("image/") == true)
            return uriToBase64(context, uri)
    }
    return null
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    vm: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenMemories: () -> Unit,
    onOpenSuggestions: () -> Unit,
    onOpenHelp: () -> Unit,
) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var inputText by remember { mutableStateOf("") }
    var pendingImage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showAttachMenu by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { pendingImage = uriToBase64(context, it) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { pendingImage = bitmapToBase64(it) }
    }
    val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) cameraLauncher.launch(null)
    }

    LaunchedEffect(ui.messages.size) {
        if (ui.messages.isNotEmpty()) scope.launch { listState.animateScrollToItem(ui.messages.size - 1) }
    }

    fun doSend() {
        val img = pendingImage
        if (inputText.isBlank() && img == null) return
        vm.sendMessage(inputText, img?.first, img?.second)
        inputText = ""
        pendingImage = null
    }

    // ── Attach menu dialog ────────────────────────────────────────────────
    if (showAttachMenu) {
        AlertDialog(
            onDismissRequest = { showAttachMenu = false },
            containerColor = SurfaceColor,
            titleContentColor = OrangeAccent,
            textContentColor = TextPrimary,
            title = { Text("Attach image", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = { galleryLauncher.launch("image/*"); showAttachMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Choose from gallery", color = TextPrimary) }
                    TextButton(
                        onClick = { cameraPermLauncher.launch(Manifest.permission.CAMERA); showAttachMenu = false },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Take photo", color = TextPrimary) }
                    TextButton(
                        onClick = {
                            pasteImageFromClipboard(context)?.let { pendingImage = it }
                            showAttachMenu = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Paste from clipboard", color = TextPrimary) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAttachMenu = false }) { Text("Cancel", color = TextSecondary) }
            },
        )
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

                // ── Pending image preview ─────────────────────────────────
                AnimatedVisibility(visible = pendingImage != null) {
                    pendingImage?.let { (b64, _) ->
                        val previewBitmap = remember(b64) {
                            runCatching {
                                val bytes = Base64.decode(b64, Base64.DEFAULT)
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                            }.getOrNull()
                        }
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            previewBitmap?.let { bmp ->
                                Image(
                                    bitmap = bmp,
                                    contentDescription = "Selected image",
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            }
                            IconButton(onClick = { pendingImage = null }) {
                                Icon(Icons.Default.Close, "Remove image", tint = TextSecondary)
                            }
                        }
                    }
                }

                // ── Input row ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(
                        onClick = { showAttachMenu = true },
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(Icons.Default.AddPhotoAlternate, "Attach image", tint = TextSecondary)
                    }

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
                        keyboardActions = KeyboardActions(onSend = { doSend() }),
                        trailingIcon = {
                            if (inputText.isNotBlank() || pendingImage != null) {
                                IconButton(onClick = { doSend() }) {
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
                .clip(
                    RoundedCornerShape(
                        topStart = 18.dp, topEnd = 18.dp,
                        bottomStart = if (isUser) 18.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 18.dp,
                    )
                )
                .background(if (isUser) UserBubble else AssistantBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                msg.imageBase64?.let { b64 ->
                    val imageBitmap = remember(b64) {
                        runCatching {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                    imageBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp,
                            contentDescription = "Image",
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 200.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                if (msg.text.isNotBlank()) {
                    Text(msg.text, color = TextPrimary, fontSize = 15.sp, lineHeight = 22.sp)
                }
            }
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
                    "Control any media & launch apps",
                    "Set reminders — spoken aloud",
                    "Remember your preferences",
                    "Understand images you share",
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
