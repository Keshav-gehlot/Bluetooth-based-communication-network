package com.meshchat.ui.features.settings

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshchat.ui.core.ScreenUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onResetIdentity: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.state.collectAsState()

    var showNameDialog by remember { mutableStateOf(false) }
    var showRoomDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when (val state = uiState) {
            is ScreenUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading...")
                }
            }
            is ScreenUiState.Error -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                }
            }
            is ScreenUiState.Empty -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data available")
                }
            }
            is ScreenUiState.Success -> {
                val data = state.data
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(60.dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primary),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = data.identity.displayName.take(1).uppercase(),
                                            color = MaterialTheme.colorScheme.onPrimary,
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Display Name", style = MaterialTheme.typography.bodySmall)
                                        Text(data.identity.displayName, style = MaterialTheme.typography.bodyLarge)
                                    }
                                    Button(onClick = { showNameDialog = true }) {
                                        Text("Edit")
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Joined Rooms", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                data.joinedRooms.forEach { roomCode ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Room: $roomCode", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                    }
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { showRoomDialog = true },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Join New Room")
                                }
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Mesh Network", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Enable Node IDs", style = MaterialTheme.typography.bodyLarge)
                                    Switch(
                                        checked = data.idsEnabled,
                                        onCheckedChange = { viewModel.toggleIds(it) }
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Max Hops: ${data.maxHops}", style = MaterialTheme.typography.bodyLarge)
                                Slider(
                                    value = data.maxHops.toFloat(),
                                    onValueChange = { viewModel.updateMaxHops(it.toInt()) },
                                    valueRange = 1f..10f,
                                    steps = 8
                                )
                            }
                        }
                    }

                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text("Danger Zone", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.height(16.dp))
                                
                                Button(
                                    onClick = { viewModel.clearAllMessages() },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Clear All Messages")
                                }
                                
                                Spacer(modifier = Modifier.height(8.dp))
                                
                                Button(
                                    onClick = { showResetDialog = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                ) {
                                    Text("Reset Identity & Setup")
                                }
                            }
                        }
                    }
                    
                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }

                if (showNameDialog) {
                    var newName by remember { mutableStateOf(data.identity.displayName) }
                    AlertDialog(
                        onDismissRequest = { showNameDialog = false },
                        title = { Text("Update Display Name") },
                        text = {
                            OutlinedTextField(
                                value = newName,
                                onValueChange = { if (it.length <= 24) newName = it },
                                singleLine = true
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.updateDisplayName(newName)
                                showNameDialog = false
                            }) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNameDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (showRoomDialog) {
                    var newRoomCode by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showRoomDialog = false },
                        title = { Text("Join Room") },
                        text = {
                            Column {
                                OutlinedTextField(
                                    value = newRoomCode,
                                    onValueChange = {
                                        if (it.length <= 5 && it.matches(Regex("^[a-zA-Z0-9]*\$"))) {
                                            newRoomCode = it.uppercase()
                                        }
                                    },
                                    label = { Text("Room Code") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                TextButton(onClick = { 
                                    newRoomCode = (1..5).map { ('A'..'Z').random() }.joinToString("")
                                }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Generate")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Generate Random")
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    if (newRoomCode.length == 5) {
                                        viewModel.joinRoom(newRoomCode)
                                        showRoomDialog = false
                                    }
                                },
                                enabled = newRoomCode.length == 5
                            ) {
                                Text("Join")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showRoomDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }

                if (showResetDialog) {
                    AlertDialog(
                        onDismissRequest = { showResetDialog = false },
                        title = { Text("Reset Identity?") },
                        text = { Text("This will permanently delete your identity, message history, and joined rooms. You will be sent back to the setup screen.") },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    viewModel.resetIdentity()
                                    onResetIdentity()
                                    showResetDialog = false
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Reset")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showResetDialog = false }) {
                                Text("Cancel")
                            }
                        }
                    )
                }
            }
        }
    }
}
