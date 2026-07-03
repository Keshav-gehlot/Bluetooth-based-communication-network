package com.meshchat.ui.features.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import coil.compose.AsyncImage
import com.meshchat.ui.features.media.ImageViewerScreen
import com.meshchat.ui.features.media.VideoPlayerScreen
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.domain.model.Message
import com.meshchat.domain.model.MessageStatus
import com.meshchat.ui.components.ErrorState
import com.meshchat.ui.components.LoadingState
import com.meshchat.ui.core.ScreenUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateBack: () -> Unit,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var activeViewerMedia by remember { mutableStateOf<Message?>(null) }
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val displayPeerName = viewModel.peerName

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onImagePicked(uri)
        }
    }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.onVideoPicked(uri)
        }
    }

    val context = LocalContext.current
    val recordAudioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.widget.Toast.makeText(context, "Microphone permission granted! Hold to talk.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required for PTT", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // Collect send errors and show as Snackbar
    LaunchedEffect(Unit) {
        viewModel.sendError.collect { errorMessage ->
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    BackHandler(enabled = true) {
        onNavigateBack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .semantics { contentDescription = "${displayPeerName}'s avatar" },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = displayPeerName.take(1).uppercase(),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.titleLarge
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(displayPeerName, style = MaterialTheme.typography.titleLarge)
                                if (state is ScreenUiState.Success) {
                                    val s = (state as ScreenUiState.Success<ChatUiState>).data
                                    val statusText = if (s.peerOnline) "Online via ${s.peerHops} hop(s)" else "Offline"
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (s.peerOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack, modifier = Modifier.minimumInteractiveComponentSize()) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Navigate back")
                        }
                    }
                )
                // Encryption Chip
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("AES-256-GCM End-to-End Encrypted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        },
        bottomBar = {
            val voiceSession by viewModel.voiceSession.collectAsStateWithLifecycle()
            val localContext = LocalContext.current
            var showAttachmentMenu by remember { mutableStateOf(false) }

            Column {
                // Voice PTT Active Overlay
                val voiceSessionState = voiceSession?.state
                if (voiceSessionState is com.meshchat.domain.model.VoiceSessionState.Transmitting ||
                    voiceSessionState is com.meshchat.domain.model.VoiceSessionState.Receiving) {
                    
                    val isOutgoing = voiceSessionState is com.meshchat.domain.model.VoiceSessionState.Transmitting
                    val barColor = if (isOutgoing) MaterialTheme.colorScheme.errorContainer else Color(0xFFE8F5E9)
                    val barTextColor = if (isOutgoing) MaterialTheme.colorScheme.onErrorContainer else Color(0xFF2E7D32)
                    val dotColor = if (isOutgoing) Color.Red else Color.Green
                    
                    // Pulsing animation for the dot
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1000, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )

                    // Timer calculation
                    var seconds by remember { mutableStateOf(0) }
                    LaunchedEffect(voiceSessionState) {
                        seconds = 0
                        while (isActive) {
                            delay(1000)
                            seconds++
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(barColor)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(dotColor.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isOutgoing) "TRANSMITTING" else "${voiceSession?.senderUsername ?: "Peer"} speaking...",
                                color = barTextColor,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Animate 8 random waveform bars
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            repeat(8) { _ ->
                                var barHeight by remember { mutableStateOf(10f) }
                                LaunchedEffect(voiceSessionState) {
                                    while (isActive) {
                                        barHeight = (5..35).random().toFloat()
                                        delay((50..150).random().toLong())
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(barHeight.dp)
                                        .background(barTextColor.copy(alpha = 0.8f))
                                )
                            }
                        }

                        Text(
                            text = String.format("%02d:%02d", seconds / 60, seconds % 60),
                            color = barTextColor,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Input Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { showAttachmentMenu = true },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Attach file")
                        DropdownMenu(
                            expanded = showAttachmentMenu,
                            onDismissRequest = { showAttachmentMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Send Image") },
                                onClick = {
                                    showAttachmentMenu = false
                                    imagePickerLauncher.launch("image/*")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Send Video") },
                                onClick = {
                                    showAttachmentMenu = false
                                    videoPickerLauncher.launch("video/*")
                                }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message...") },
                        maxLines = 4
                    )
                    Spacer(modifier = Modifier.width(8.dp))

                    if (inputText.isBlank()) {
                        // Mic (PTT) Button when input text is empty
                        var isPressing by remember { mutableStateOf(false) }
                        val isReceiving = voiceSessionState is com.meshchat.domain.model.VoiceSessionState.Receiving
                        IconButton(
                            onClick = {
                                if (isReceiving) {
                                    android.widget.Toast.makeText(context, "Channel busy", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Hold to talk", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isReceiving,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .background(
                                    if (isPressing) MaterialTheme.colorScheme.errorContainer 
                                    else if (isReceiving) MaterialTheme.colorScheme.surfaceVariant 
                                    else MaterialTheme.colorScheme.primaryContainer,
                                    CircleShape
                                )
                                .then(
                                    if (!isReceiving) {
                                        Modifier.pointerInput(Unit) {
                                            detectTapGestures(
                                                onLongPress = {
                                                    if (androidx.core.content.ContextCompat.checkSelfPermission(
                                                            context,
                                                            android.Manifest.permission.RECORD_AUDIO
                                                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                                    ) {
                                                        isPressing = true
                                                        viewModel.onPttDown()
                                                    } else {
                                                        recordAudioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                                                    }
                                                },
                                                onPress = {
                                                    try {
                                                        awaitRelease()
                                                    } finally {
                                                        if (isPressing) {
                                                            isPressing = false
                                                            viewModel.onPttUp()
                                                        }
                                                    }
                                                }
                                            )
                                        }
                                    } else Modifier
                                )
                        ) {
                            Icon(
                                imageVector = if (isReceiving) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = "Hold to talk",
                                tint = if (isPressing) MaterialTheme.colorScheme.onErrorContainer 
                                       else if (isReceiving) MaterialTheme.colorScheme.onSurfaceVariant 
                                       else MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    } else {
                        // Send Button when text is present
                        IconButton(
                            onClick = {
                                viewModel.sendMessage(inputText)
                                inputText = ""
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (val currentState = state) {
                is ScreenUiState.Loading -> LoadingState()
                is ScreenUiState.Error -> ErrorState(message = currentState.message, onRetry = {})
                is ScreenUiState.Empty -> { /* Empty conversation - just show empty list */ }
                is ScreenUiState.Success -> {
                    val messages = currentState.data.messages
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        items(messages) { msg ->
                            MessageBubble(
                                message = msg,
                                onCancelTransfer = { viewModel.onCancelTransfer(it) },
                                onClickMedia = { activeViewerMedia = it }
                            )
                        }
                        if (currentState.data.isTyping) {
                            item { TypingIndicator() }
                        }
                    }
                }
            }

            if (activeViewerMedia != null) {
                val media = activeViewerMedia!!
                val path = media.mediaLocalPath ?: ""
                if (media.mediaType == com.meshchat.core.MediaType.IMAGE) {
                    ImageViewerScreen(localUri = path, onClose = { activeViewerMedia = null })
                } else if (media.mediaType == com.meshchat.core.MediaType.VIDEO) {
                    VideoPlayerScreen(localUri = path, onClose = { activeViewerMedia = null })
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: Message,
    onCancelTransfer: (String) -> Unit = {},
    onClickMedia: (Message) -> Unit = {}
) {
    val isOutgoing = message.isOutgoing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOutgoing) 16.dp else 4.dp,
                        bottomEnd = if (isOutgoing) 4.dp else 16.dp
                    )
                )
                .then(
                    if (isOutgoing) Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp
                    )) else Modifier
                )
                .padding(12.dp)
                .semantics {
                    stateDescription = "Status: ${message.status.name}"
                }
        ) {
            if (message.mediaTransferId != null) {
                MediaBubbleContent(
                    message = message,
                    onCancelTransfer = onCancelTransfer,
                    onClick = { onClickMedia(message) }
                )
            } else if (message.text.startsWith("[Voice Message]")) {
                VoiceMessageBubble(
                    text = message.text,
                    isOutgoing = isOutgoing
                )
            } else {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(
                    text = "Via ${message.hopCount} hops",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isOutgoing) {
                    Spacer(modifier = Modifier.width(4.dp))
                    when (message.status) {
                        MessageStatus.SENT -> Icon(Icons.Filled.Done, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        MessageStatus.DELIVERED -> Icon(Icons.Filled.DoneAll, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.secondary)
                        else -> { /* sending/failed */ }
                    }
                }
            }
        }
    }
}

@Composable
fun MediaBubbleContent(
    message: Message,
    onCancelTransfer: (String) -> Unit,
    onClick: () -> Unit
) {
    val thumbnailBitmap = remember(message.mediaThumbnailBase64) {
        if (!message.mediaThumbnailBase64.isNullOrEmpty()) {
            try {
                val bytes = android.util.Base64.decode(message.mediaThumbnailBase64, android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.DarkGray)
            .then(
                if (message.mediaStatus == "COMPLETE" && !message.mediaLocalPath.isNullOrEmpty()) {
                    Modifier.clickable { onClick() }
                } else {
                    Modifier
                }
            )
    ) {
        if (!message.mediaLocalPath.isNullOrEmpty()) {
            AsyncImage(
                model = message.mediaLocalPath,
                contentDescription = "Media attachment",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (thumbnailBitmap != null) {
            Image(
                bitmap = thumbnailBitmap,
                contentDescription = "Media thumbnail",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        if (message.mediaType == com.meshchat.core.MediaType.VIDEO) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play video",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        when (message.mediaStatus) {
            "PENDING", "PROGRESS", "VERIFYING" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (message.mediaStatus == "VERIFYING") "Verifying..." else "Transferring...",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium
                        )

                        if (message.isOutgoing && message.mediaStatus != "VERIFYING") {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = { onCancelTransfer(message.id) },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                            ) {
                                Text("Cancel", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            "FAILED" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Red.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Failed",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Transfer Failed",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            "CANCELLED" -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Gray.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Cancelled",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun VoiceMessageBubble(
    text: String,
    isOutgoing: Boolean
) {
    val context = LocalContext.current
    val durationMs = remember(text) {
        text.removePrefix("[Voice Message] ").trim().toLongOrNull() ?: 0L
    }

    fun formatDuration(ms: Long): String {
        val totalSecs = ms / 1000
        val minutes = totalSecs / 60
        val seconds = totalSecs % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(8.dp)
            .widthIn(max = 220.dp)
            .clickable {
                android.widget.Toast.makeText(context, "Voice messages can't be replayed", android.widget.Toast.LENGTH_SHORT).show()
            }
    ) {
        Icon(
            imageVector = Icons.Filled.Mic,
            contentDescription = "Voice message",
            tint = if (isOutgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(8.dp))
        
        // Waveform placeholder: static decorative bars
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.weight(1f)
        ) {
            repeat(12) { i ->
                val h = (4 + (i * 7 % 16)).dp
                Box(
                    Modifier
                        .width(2.dp)
                        .height(h)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            }
        }
        
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "alpha"
    )
    Row(
        modifier = Modifier
            .padding(8.dp)
            .semantics { contentDescription = "Typing indicator" }
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
        Spacer(modifier = Modifier.width(4.dp))
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)))
    }
}
