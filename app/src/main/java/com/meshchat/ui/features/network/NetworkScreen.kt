package com.meshchat.ui.features.network

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.domain.model.Peer
import com.meshchat.ui.components.EmptyState
import com.meshchat.ui.components.ErrorState
import com.meshchat.ui.components.LoadingState
import com.meshchat.ui.core.ScreenUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Mesh Network", style = MaterialTheme.typography.titleLarge) }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (val currentState = state) {
                is ScreenUiState.Loading -> LoadingState()
                is ScreenUiState.Error -> ErrorState(message = currentState.message, onRetry = {})
                is ScreenUiState.Empty -> EmptyState(message = "Network state unknown")
                is ScreenUiState.Success -> {
                    val data = currentState.data
                    val peers = data.peers
                    val onlinePeers = peers.count { it.isOnline }
                    
                    Column(modifier = Modifier.fillMaxSize()) {
                        // Network Stats & Room Code Header
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = onlinePeers.toString(),
                                    style = MaterialTheme.typography.displayLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text("Peers Online", style = MaterialTheme.typography.titleLarge)
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                // Room Code Chip
                                Surface(
                                    color = MaterialTheme.colorScheme.background,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .clickable {
                                            clipboardManager.setText(AnnotatedString(data.joinedRooms.joinToString()))
                                        }
                                        .minimumInteractiveComponentSize()
                                        .semantics { contentDescription = "Copy room code" }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Joined Rooms: ${data.joinedRooms.joinToString()}", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }

                        // Peer List
                        if (peers.isEmpty()) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text("No peers discovered yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(peers) { peer ->
                                    PeerCard(peer = peer)
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
fun PeerCard(peer: Peer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = "Peer ${peer.displayName}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = peer.displayName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Node ID: ${peer.nodeId.take(8)}...",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!peer.isOnline) {
                    Text(
                        text = "Last seen: ${formatTime(peer.lastSeen)}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (peer.isOnline) {
                Badge(containerColor = if (peer.hopDistance == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary) {
                    Text("${peer.hopDistance} hop${if(peer.hopDistance > 1) "s" else ""}")
                }
            } else {
                Badge(containerColor = MaterialTheme.colorScheme.surface) {
                    Text("Offline")
                }
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    if (timeMs == 0L) return "Never"
    return SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(timeMs))
}
