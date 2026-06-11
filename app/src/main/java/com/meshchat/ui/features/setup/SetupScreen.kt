package com.meshchat.ui.features.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.ui.theme.OrbitronFamily
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import com.meshchat.util.generateRoomCode
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onNavigateToChats: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val roomCode by viewModel.roomCode.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    var errorMessage by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            errorMessage = null
        }
    }

    // Validation
    val isNameValid = name.trim().isNotEmpty()
    val isRoomCodeValid = roomCode.trim().length == 5 && roomCode.trim().matches(Regex("^[a-zA-Z0-9]+\$"))
    val isButtonEnabled = isNameValid && isRoomCodeValid && !isLoading

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0A0A0A)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Upper Section: Title and Avatar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Who are you?",
                    fontFamily = OrbitronFamily,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Avatar Initials Preview
                val initials = if (name.trim().isEmpty()) "?" else name.trim().take(1).uppercase()
                // Deterministic gradient color for initials avatar
                val avatarGradient = remember(initials) {
                    val hash = initials.hashCode()
                    val color1 = Color(0xFF00E87A)
                    val color2 = when (hash % 3) {
                        0 -> Color(0xFF00D4FF) // blue
                        1 -> Color(0xFFFFAA00) // orange
                        else -> Color(0xFFFF3355) // red
                    }
                    Brush.radialGradient(listOf(color1, color2))
                }

                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .clip(CircleShape)
                        .background(avatarGradient)
                        .border(2.dp, Color(0xFF00E87A).copy(alpha = 0.8f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        fontSize = 44.sp,
                        fontWeight = FontWeight.Black,
                        color = Color(0xFF0A0A0A)
                    )
                }
            }

            // Middle Section: Inputs
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Name Field
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.updateDisplayName(it) },
                    label = { Text("Your name") },
                    placeholder = { Text("e.g. Alice") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFF00E87A),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                        focusedLabelColor = Color(0xFF00E87A),
                        unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                        focusedPlaceholderColor = Color.White.copy(alpha = 0.3f),
                        unfocusedPlaceholderColor = Color.White.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // Room Code Field
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = roomCode,
                        onValueChange = { viewModel.updateRoomCode(it) },
                        label = { Text("Join a mesh") },
                        placeholder = { Text("5-char code") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { viewModel.updateRoomCode(generateRoomCode()) }) {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = "Generate Room Code",
                                    tint = Color(0xFF00E87A)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00E87A),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.3f),
                            focusedLabelColor = Color(0xFF00E87A),
                            unfocusedLabelColor = Color.White.copy(alpha = 0.5f),
                            focusedPlaceholderColor = Color.White.copy(alpha = 0.3f),
                            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.3f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Enter a room code to pair with a group, or use the generated code to start your own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // Lower Section: Button
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Button(
                    onClick = {
                        viewModel.completeSetup(
                            onSuccess = onNavigateToChats,
                            onError = { errorMessage = it }
                        )
                    },
                    enabled = isButtonEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00E87A),
                        contentColor = Color(0xFF0A0A0A),
                        disabledContainerColor = Color(0xFF00E87A).copy(alpha = 0.3f),
                        disabledContentColor = Color(0xFF0A0A0A).copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            color = Color(0xFF0A0A0A),
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Text(
                            text = "Start Chatting",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
