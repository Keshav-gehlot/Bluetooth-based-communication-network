package com.meshchat.ui.features.chats

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.domain.model.Conversation
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
fun ChatsScreen(
    onNavigateToChat: (String, String, String) -> Unit,
    viewModel: ChatsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Chats", style = MaterialTheme.typography.titleLarge)
                        if (state is ScreenUiState.Success) {
                            val onlineCount = (state as ScreenUiState.Success<ChatsUiState>).data.onlinePeers.count { it.isOnline }
                            if (onlineCount > 0) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Badge(containerColor = MaterialTheme.colorScheme.primary) {
                                    Text(onlineCount.toString())
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (val currentState = state) {
                is ScreenUiState.Loading -> LoadingState()
                is ScreenUiState.Empty -> EmptyState(message = "No peers nearby yet")
                is ScreenUiState.Error -> ErrorState(message = currentState.message, onRetry = { /* Retry logic if any */ })
                is ScreenUiState.Success -> {
                    val conversations = currentState.data.conversations
                    val peers = currentState.data.onlinePeers
                    
                    if (conversations.isEmpty()) {
                        EmptyState(message = "No messages yet.\nFind someone in Network!")
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(conversations) { convo ->
                                val peer = peers.find { it.nodeId == convo.peerId }
                                ConversationCard(
                                    conversation = convo,
                                    peer = peer,
                                    onClick = { onNavigateToChat(convo.id, convo.peerId, convo.peerName) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConversationCard(
    conversation: Conversation,
    peer: Peer?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Conversation with ${conversation.peerName}" },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .semantics { contentDescription = "${conversation.peerName}'s avatar" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = conversation.peerName.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleLarge
                )
                // Online Dot
                if (peer != null) {
                    val dotColor = when {
                        !peer.isOnline -> MaterialTheme.colorScheme.surfaceVariant
                        peer.hopDistance == 1 -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.tertiary
                    }
                    val borderModifier = if (!peer.isOnline) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape) else Modifier
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .align(Alignment.BottomEnd)
                            .clip(CircleShape)
                            .background(dotColor)
                            .then(borderModifier)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatTime(conversation.lastMessageTime),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = conversation.lastMessage,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (conversation.unreadCount > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.secondary) {
                            Text(conversation.unreadCount.toString())
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(timeMs: Long): String {
    if (timeMs == 0L) return ""
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timeMs))
}
