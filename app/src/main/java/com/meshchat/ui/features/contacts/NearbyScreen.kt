package com.meshchat.ui.features.contacts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.ui.components.ErrorState
import com.meshchat.ui.components.LoadingState
import com.meshchat.ui.core.ScreenUiState
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    onNavigateToChat: (String, String, String) -> Unit,
    viewModel: NearbyViewModel = hiltViewModel()
) {
    val stateUi by viewModel.state.collectAsStateWithLifecycle()
    var showShareDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Nearby", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    if (stateUi is ScreenUiState.Success) {
                        val data = (stateUi as ScreenUiState.Success<NearbyUiState>).data
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(data.ownAvatarColor))
                                .clickable { showShareDialog = true }
                                .semantics { contentDescription = "Show profile share QR code" },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = data.ownDisplayName.take(1).uppercase(),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (val currentState = stateUi) {
                is ScreenUiState.Loading -> LoadingState()
                is ScreenUiState.Error -> ErrorState(
                    message = currentState.message,
                    onRetry = {}
                )
                is ScreenUiState.Empty -> RadarSweepEmptyState()
                is ScreenUiState.Success -> {
                    val data = currentState.data
                    
                    if (showShareDialog) {
                        ShareProfileDialog(
                            displayName = data.ownDisplayName,
                            nodeId = data.ownNodeId,
                            joinedRooms = data.ownJoinedRooms,
                            onDismiss = { showShareDialog = false }
                        )
                    }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Own presence card pinned at the top
                        OwnPresenceCard(
                            displayName = data.ownDisplayName,
                            nodeId = data.ownNodeId,
                            joinedRooms = data.ownJoinedRooms,
                            avatarColor = data.ownAvatarColor,
                            onClick = { showShareDialog = true }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (data.peers.isEmpty()) {
                            Box(
                                modifier = Modifier.weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                RadarSweepEmptyState()
                            }
                        } else {
                            // Peer List
                            // Group online peers first, sorted alphabetically, followed by offline peers sorted by last seen
                            val sortedPeers = remember(data.peers) {
                                val online = data.peers.filter { it.isOnline }
                                    .sortedBy { it.displayName.lowercase() }
                                val offline = data.peers.filter { !it.isOnline }
                                    .sortedByDescending { it.lastSeen }
                                online + offline
                            }

                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .weight(1f)
                            ) {
                                items(
                                    items = sortedPeers,
                                    key = { it.nodeId }
                                ) { peer ->
                                    PeerRow(
                                        peer = peer,
                                        onClick = {
                                            onNavigateToChat(peer.nodeId, peer.nodeId, peer.displayName)
                                        },
                                        modifier = Modifier.animateItem()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OwnPresenceCard(
    displayName: String,
    nodeId: String,
    joinedRooms: Set<String>,
    avatarColor: Long,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick)
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(avatarColor)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = displayName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    SuggestionChip(
                        onClick = {},
                        label = { Text("You", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(24.dp),
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            labelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        border = null
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Rooms: ${joinedRooms.joinToString()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "ID: ${nodeId.take(8).uppercase()}",
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            IconButton(onClick = onClick) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share Profile code",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun PeerRow(
    peer: PeerUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cardAlpha = if (peer.isOnline) 1f else 0.5f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
            .alpha(cardAlpha),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (peer.isOnline) Color(peer.avatarColor) else Color.Gray),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = if (peer.isOnline) MaterialTheme.colorScheme.onPrimary else Color.LightGray,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !peer.isOnline -> Color.Gray
                                    peer.hopDistance == 1 -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.tertiary
                                }
                            )
                    )

                    Text(
                        text = when {
                            !peer.isOnline -> "Offline (last seen ${formatLastSeen(peer.lastSeen)})"
                            peer.hopDistance == 1 -> "1 hop away"
                            else -> "${peer.hopDistance} hops away"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun RadarSweepEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        val transition = rememberInfiniteTransition(label = "radar")
        
        val wave1 by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2500, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave1"
        )
        
        val wave2 by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2500, easing = LinearEasing, delayMillis = 833),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave2"
        )

        val wave3 by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2500, easing = LinearEasing, delayMillis = 1666),
                repeatMode = RepeatMode.Restart
            ),
            label = "wave3"
        )

        val radarColor = MaterialTheme.colorScheme.primary

        Box(
            modifier = Modifier.size(200.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = center
                val maxRadius = size.minDimension / 2f

                listOf(wave1, wave2, wave3).forEach { progress ->
                    val radius = maxRadius * progress
                    val alpha = (1f - progress).coerceIn(0f, 1f)
                    drawCircle(
                        color = radarColor,
                        radius = radius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx()),
                        alpha = alpha
                    )
                }

                drawCircle(
                    color = radarColor,
                    radius = 6.dp.toPx(),
                    center = center
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = "Searching for people nearby...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun ShareProfileDialog(
    displayName: String,
    nodeId: String,
    joinedRooms: Set<String>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val qrArt = remember(nodeId, joinedRooms) { generateQrText(nodeId, joinedRooms.joinToString()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Share Profile",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // QR Block Art Representation
                Box(
                    modifier = Modifier
                        .background(Color.Black, shape = RoundedCornerShape(12.dp))
                        .padding(16.dp)
                ) {
                    Text(
                        text = qrArt,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        ),
                        lineHeight = MaterialTheme.typography.labelSmall.fontSize * 0.75f,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(12.dp)
                ) {
                    Text(
                        text = "ROOM CODE",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = joinedRooms.joinToString(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "NODE ID",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = nodeId,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val textToCopy = "MeshChat Profile\nName: $displayName\nRooms: ${joinedRooms.joinToString()}\nID: $nodeId"
                        val clip = ClipData.newPlainText("MeshChat Profile", textToCopy)
                        clipboard.setPrimaryClip(clip)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Share Info")
                }
            }
        }
    }
}

private fun generateQrText(nodeId: String, roomCode: String): String {
    val combined = "$nodeId:$roomCode"
    val hash = combined.hashCode()
    val sb = StringBuilder()
    for (i in 0 until 8) {
        for (j in 0 until 8) {
            val bit = (hash ushr (i * 8 + j)) and 1
            if (bit == 1) {
                sb.append("██")
            } else {
                sb.append("  ")
            }
        }
        sb.append("\n")
    }
    return sb.toString().trimEnd()
}

private fun formatLastSeen(timeMs: Long): String {
    if (timeMs == 0L) return "Never"
    val diff = System.currentTimeMillis() - timeMs
    return when {
        diff < 60000 -> "just now"
        diff < 3600000 -> "${diff / 60000}m ago"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
    }
}
