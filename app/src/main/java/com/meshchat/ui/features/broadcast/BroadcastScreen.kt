package com.meshchat.ui.features.broadcast

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.ui.components.EmptyState
import com.meshchat.ui.components.ErrorState
import com.meshchat.ui.components.LoadingState
import com.meshchat.ui.core.ScreenUiState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Mic
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BroadcastScreen(
    viewModel: BroadcastViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var inputText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    var showInfoBanner by rememberSaveable { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    val context = LocalContext.current
    val recordAudioPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            android.widget.Toast.makeText(context, "Microphone permission granted! Hold to talk.", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Microphone permission is required for PTT", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.sendError.collect { errorMessage ->
            snackbarHostState.showSnackbar(errorMessage)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { 
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        when (val currentState = state) {
                            is ScreenUiState.Success -> {
                                var expanded by remember { mutableStateOf(false) }
                                Box {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.clickable { expanded = true }
                                    ) {
                                        Text(
                                            "Room: ${currentState.data.activeRoomId}", 
                                            style = MaterialTheme.typography.titleLarge
                                        )
                                        Icon(Icons.Default.ArrowDropDown, contentDescription = "Switch Room")
                                    }
                                    
                                    DropdownMenu(
                                        expanded = expanded,
                                        onDismissRequest = { expanded = false }
                                    ) {
                                        currentState.data.availableRooms.forEach { room ->
                                            DropdownMenuItem(
                                                text = { Text("Room: $room") },
                                                onClick = {
                                                    viewModel.setActiveRoom(room)
                                                    expanded = false
                                                }
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "● ${currentState.data.onlinePeerCount} online",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            else -> {
                                Text("Mesh Rooms", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            )
        },
        bottomBar = {
            val voiceSession by viewModel.voiceSession.collectAsStateWithLifecycle()
            val isChannelBusy by viewModel.isChannelBusy.collectAsStateWithLifecycle()
            val context = LocalContext.current

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
                                text = if (isOutgoing) "TRANSMITTING BROADCAST" else "${voiceSession?.senderUsername ?: "Peer"} speaking...",
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
                            repeat(8) { i ->
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Send to everyone...") },
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
                        IconButton(
                            onClick = {
                                viewModel.sendBroadcast(inputText)
                                inputText = ""
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Filled.CellTower, contentDescription = "Broadcast to all", tint = MaterialTheme.colorScheme.onPrimary)
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
                is ScreenUiState.Success -> {
                    val messages = currentState.data.messages
                    val totalOnline = currentState.data.onlinePeerCount
                    
                    LaunchedEffect(messages.size) {
                        if (messages.isNotEmpty()) {
                            listState.animateScrollToItem(messages.size - 1)
                        }
                    }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        AnimatedVisibility(visible = showInfoBanner) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Messages here are sent to all connected nodes over the mesh. Range depends on how many devices are nearby.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { showInfoBanner = false }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Dismiss banner")
                                }
                            }
                        }

                        if (messages.isEmpty()) {
                            EmptyState(message = "No broadcasts yet in this room.\nBe the first!")
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(8.dp)
                            ) {
                                items(messages) { msg ->
                                    BroadcastCard(message = msg, totalOnline = totalOnline)
                                }
                            }
                        }
                    }
                }
                else -> {}
            }
        }
    }
}

@Composable
fun BroadcastCard(message: BroadcastMessage, totalOnline: Int) {
    val isOutgoing = message.isOutgoing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            if (isOutgoing) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = if (isOutgoing) RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp) else RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
                    .semantics {
                        stateDescription = "Broadcast from ${message.senderName}"
                    }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(message.senderAvatarColor)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = message.senderName.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message.senderName,
                            style = MaterialTheme.typography.titleLarge.copy(fontSize = 16.sp),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(message.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (message.text.startsWith("[Voice Message]")) {
                    VoiceMessageBubble(
                        text = message.text,
                        isOutgoing = message.isOutgoing
                    )
                } else {
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    if (isOutgoing) {
                        Icon(
                            Icons.Filled.WifiTethering,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Received by ${message.receivedByCount} of ${totalOnline} nodes",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
