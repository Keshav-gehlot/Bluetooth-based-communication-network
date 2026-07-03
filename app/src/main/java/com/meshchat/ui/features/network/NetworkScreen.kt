package com.meshchat.ui.features.network

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.core.TransportMode
import com.meshchat.domain.model.NodeIdentity
import com.meshchat.domain.model.Peer
import com.meshchat.ui.components.EmptyState
import com.meshchat.ui.components.ErrorState
import com.meshchat.ui.components.LoadingState
import com.meshchat.ui.core.ScreenUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    viewModel: NetworkViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transportMode by viewModel.transportMode.collectAsStateWithLifecycle()
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                        // ── Network Stats header ────────────────────────────────────────────
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                            }
                        }

                        // ── Transport switcher ──────────────────────────────────────────────
                        TransportSwitcher(
                            currentMode = transportMode,
                            onModeSelected = { mode ->
                                if (viewModel.permissionManager.arePermissionsGranted(mode)) {
                                    viewModel.switchTransport(mode)
                                } else {
                                    scope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Grant permission to use $mode",
                                            actionLabel = "Settings",
                                            duration = SnackbarDuration.Long
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            val intent = android.content.Intent(
                                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                android.net.Uri.fromParts("package", context.packageName, null)
                                            ).apply {
                                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(intent)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )

                        // ── Dual node ID card ───────────────────────────────────────────────
                        DualNodeIdCard(
                            identity = data.identity,
                            activeMode = transportMode,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            onCopy = { id -> clipboardManager.setText(AnnotatedString(id)) },
                        )

                        // ── Peer List ───────────────────────────────────────────────────────
                        if (peers.isEmpty()) {
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                Text("No peers discovered yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.weight(1f)) {
                                items(peers) { peer ->
                                    PeerCard(peer = peer, activeMode = transportMode)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Transport Switcher ─────────────────────────────────────────────────────────

@Composable
private fun TransportSwitcher(
    currentMode: TransportMode,
    onModeSelected: (TransportMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = Color(0xFF6C63FF)
    val modes = listOf(
        TransportMode.BLUETOOTH to "🔵 BT",
        TransportMode.WIFI to "📶 WiFi",
        TransportMode.BOTH to "◉ Both",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF181818)),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        modes.forEach { (mode, label) ->
            val isSelected = currentMode == mode
            val bgColor by animateColorAsState(
                if (isSelected) accent else Color.Transparent,
                animationSpec = tween(300), label = "seg_$mode"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(bgColor)
                    .clickable { onModeSelected(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (isSelected) Color.White else Color(0xFF888888),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

// ── Dual Node ID Card ──────────────────────────────────────────────────────────

@Composable
private fun DualNodeIdCard(
    identity: NodeIdentity,
    activeMode: TransportMode,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit,
) {
    val monoFamily = FontFamily.Monospace
    val accent = Color(0xFF6C63FF)

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Username row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("@${identity.username}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (identity.usernameClaimed) Color(0xFF0D3D2D) else Color(0xFF2D2400),
                ) {
                    Text(
                        if (identity.usernameClaimed) "✓ claimed" else "⏳ pending",
                        color = if (identity.usernameClaimed) Color(0xFF00C896) else Color(0xFFFFAA00),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            }
            Divider(color = Color(0xFF222222))
            // BT ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val btActive = activeMode != TransportMode.WIFI
                Text("🔵 BT ", color = if (btActive) Color.White else Color(0xFF444444), fontSize = 12.sp)
                Text(
                    identity.btNodeId, fontFamily = monoFamily, fontSize = 12.sp,
                    color = if (btActive) accent else Color(0xFF333333),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(
                    Icons.Filled.ContentCopy, contentDescription = "Copy BT ID",
                    tint = if (btActive) Color(0xFF666666) else Color(0xFF333333),
                    modifier = Modifier.size(16.dp).clickable { onCopy(identity.btNodeId) }
                )
            }
            // WiFi ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                val wifiActive = activeMode != TransportMode.BLUETOOTH
                Text("📶 WiFi", color = if (wifiActive) Color.White else Color(0xFF444444), fontSize = 12.sp)
                Text(
                    identity.wifiNodeId, fontFamily = monoFamily, fontSize = 12.sp,
                    color = if (wifiActive) accent else Color(0xFF333333),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Icon(
                    Icons.Filled.ContentCopy, contentDescription = "Copy WiFi ID",
                    tint = if (wifiActive) Color(0xFF666666) else Color(0xFF333333),
                    modifier = Modifier.size(16.dp).clickable { onCopy(identity.wifiNodeId) }
                )
            }
        }
    }
}

@Composable
fun PeerCard(peer: Peer, activeMode: TransportMode = TransportMode.BLUETOOTH) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
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
                val transportLabel = when (activeMode) {
                    TransportMode.BLUETOOTH -> "🔵 Direct"
                    TransportMode.WIFI -> "📶 WiFi"
                    TransportMode.BOTH -> "🔵📶"
                }
                Text(
                    text = "$transportLabel · ${peer.hopDistance} hop${if (peer.hopDistance > 1) "s" else ""}",
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
                    Text("${peer.hopDistance} hop${if (peer.hopDistance > 1) "s" else ""}")
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
