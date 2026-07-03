package com.meshchat.ui.features.setup

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshchat.core.TransportMode
import com.meshchat.ui.theme.OrbitronFamily

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onNavigateToChats: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewIdentity by viewModel.previewIdentity.collectAsStateWithLifecycle()

    // 3-step pager: 0 = Transport picker, 1 = Username, 2 = Claim result
    var step by remember { mutableStateOf(0) }
    var showRationaleSheet by remember { mutableStateOf(false) }
    var showWarningBanner by remember { mutableStateOf(false) }

    val permissionManager = viewModel.permissionManager
    val requiredPermissions = permissionManager.getRequiredPermissions(viewModel.selectedMode)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            step = 1
        } else {
            showWarningBanner = true
            step = 1
        }
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is SetupUiState.Checking,
            is SetupUiState.Claimed,
            is SetupUiState.Conflict,
            is SetupUiState.NoMesh -> step = 2
            else -> Unit
        }
    }

    Scaffold(
        containerColor = Color(0xFF0A0A0A),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Step progress indicator
            SetupStepIndicator(
                currentStep = step,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 20.dp)
            )

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    slideInHorizontally { if (targetState > initialState) it else -it } togetherWith
                        slideOutHorizontally { if (targetState > initialState) -it else it }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 80.dp),
                label = "setup_step"
            ) { currentStep ->
                when (currentStep) {
                    0 -> TransportPickerStep(
                        selectedMode = viewModel.selectedMode,
                        onModeSelected = { viewModel.onModeSelected(it) },
                        onNext = {
                            if (permissionManager.arePermissionsGranted(viewModel.selectedMode)) {
                                step = 1
                            } else {
                                showRationaleSheet = true
                            }
                        },
                    )
                    1 -> UsernameStep(
                        username = viewModel.username,
                        onUsernameChanged = { viewModel.onUsernameChanged(it) },
                        selectedMode = viewModel.selectedMode,
                        btNodeId = previewIdentity?.btNodeId ?: "BT-••••-••••",
                        wifiNodeId = previewIdentity?.wifiNodeId ?: "WIFI-••••-••••",
                        isUsernameValid = viewModel.isUsernameValid,
                        validationMessage = viewModel.usernameValidationMessage,
                        showWarningBanner = showWarningBanner && !permissionManager.arePermissionsGranted(viewModel.selectedMode),
                        onConfirm = { viewModel.onConfirm() },
                    )
                    2 -> ClaimResultStep(
                        state = uiState,
                        username = viewModel.username,
                        onRetry = {
                            viewModel.onRetryUsername()
                            step = 1
                        },
                        onContinue = { onNavigateToChats() },
                    )
                }
            }

            // ── Permission Rationale Bottom Sheet ─────────────────────────────────
            if (showRationaleSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showRationaleSheet = false },
                    containerColor = Color(0xFF141414),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        val title = when (viewModel.selectedMode) {
                            TransportMode.BLUETOOTH -> "MeshChat needs Bluetooth access"
                            TransportMode.WIFI -> "MeshChat needs Wi-Fi access"
                            TransportMode.BOTH -> "MeshChat needs Bluetooth & Wi-Fi access"
                        }
                        val desc = com.meshchat.ui.PermissionManager.rationaleFor(viewModel.selectedMode)
                        val icon = when (viewModel.selectedMode) {
                            TransportMode.BLUETOOTH -> "🔵"
                            TransportMode.WIFI -> "📶"
                            TransportMode.BOTH -> "◉"
                        }

                        Text(icon, fontSize = 48.sp)
                        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(desc, color = Color(0xFF888888), fontSize = 14.sp, textAlign = TextAlign.Center)

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                showRationaleSheet = false
                                permissionLauncher.launch(requiredPermissions.toTypedArray())
                            },
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF))
                        ) {
                            Text("Grant Access", fontWeight = FontWeight.Bold)
                        }

                        TextButton(
                            onClick = {
                                showRationaleSheet = false
                                showWarningBanner = true
                                step = 1
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Not now", color = Color(0xFF888888))
                        }
                    }
                }
            }
        }
    }
}

// ── Step indicators ────────────────────────────────────────────────────────────

@Composable
private fun SetupStepIndicator(currentStep: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val active = index == currentStep
            val done = index < currentStep
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done   -> Color(0xFF6C63FF)
                            active -> Color(0xFF6C63FF)
                            else   -> Color(0xFF333333)
                        }
                    )
            )
        }
    }
}

// ── Step 1: Transport picker ───────────────────────────────────────────────────

@Composable
private fun TransportPickerStep(
    selectedMode: TransportMode,
    onModeSelected: (TransportMode) -> Unit,
    onNext: () -> Unit,
) {
    val accent = Color(0xFF6C63FF)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Choose Transport",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OrbitronFamily,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "How should this device connect to the mesh?",
            color = Color(0xFF888888),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(32.dp))

        // Transport cards
        listOf(
            TransportOption(TransportMode.BLUETOOTH, "🔵", "Bluetooth",
                "P2P Star · Short range · Power saving"),
            TransportOption(TransportMode.WIFI, "📶", "Wi-Fi Direct",
                "P2P Cluster · Long range · High speed"),
            TransportOption(TransportMode.BOTH, "◉", "Both Radios",
                "Parallel · Maximum coverage · More battery"),
        ).forEach { (mode, emoji, label, desc) ->
            val isSelected = selectedMode == mode
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clickable { onModeSelected(mode) }
                    .then(
                        if (isSelected) Modifier.border(
                            1.5.dp, accent, RoundedCornerShape(16.dp)
                        ) else Modifier
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) Color(0xFF1A1840) else Color(0xFF181818),
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(emoji, fontSize = 28.sp)
                    Column(Modifier.weight(1f)) {
                        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(desc, color = Color(0xFF888888), fontSize = 12.sp)
                    }
                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = accent)
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            Text("Next →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Step 2: Username ───────────────────────────────────────────────────────────

@Composable
private fun UsernameStep(
    username: String,
    onUsernameChanged: (String) -> Unit,
    selectedMode: TransportMode,
    btNodeId: String,
    wifiNodeId: String,
    isUsernameValid: Boolean,
    validationMessage: String,
    showWarningBanner: Boolean,
    onConfirm: () -> Unit,
) {
    val accent = Color(0xFF6C63FF)
    val monoFamily = FontFamily.Monospace

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            "Choose Your Name",
            color = Color.White,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = OrbitronFamily,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "This will be your permanent mesh identity.",
            color = Color(0xFF888888),
            fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (showWarningBanner) {
            Surface(
                color = Color(0xFF3E2723),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8A65))
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color(0xFFFF8A65)
                    )
                    Text(
                        text = "Permissions not granted. Connection features will be disabled until access is granted in system settings.",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Username input
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChanged,
            label = { Text("@username", color = Color(0xFF888888)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            isError = username.isNotEmpty() && !isUsernameValid,
            supportingText = {
                if (validationMessage.isNotEmpty()) {
                    Text(validationMessage, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = Color(0xFF333333),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = accent,
            ),
        )
        Spacer(Modifier.height(20.dp))

        // Node ID preview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF141414)),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Your permanent node IDs", color = Color(0xFF666666), fontSize = 12.sp)
                IdRow(
                    label = "🔵 BT",
                    id = btNodeId,
                    active = selectedMode != TransportMode.WIFI,
                    monoFamily = monoFamily,
                )
                IdRow(
                    label = "📶 WiFi",
                    id = wifiNodeId,
                    active = selectedMode != TransportMode.BLUETOOTH,
                    monoFamily = monoFamily,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onConfirm,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = isUsernameValid,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = accent),
        ) {
            Text("Claim Name on Mesh", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IdRow(label: String, id: String, active: Boolean, monoFamily: FontFamily) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, color = if (active) Color.White else Color(0xFF444444), fontSize = 13.sp)
        Text(
            id,
            color = if (active) Color(0xFF6C63FF) else Color(0xFF333333),
            fontFamily = monoFamily,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Step 3: Claim result ───────────────────────────────────────────────────────

@Composable
private fun ClaimResultStep(
    state: SetupUiState,
    username: String,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.95f, targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            is SetupUiState.Checking -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(64.dp).scale(scale),
                    color = Color(0xFF6C63FF),
                    strokeWidth = 3.dp,
                )
                Spacer(Modifier.height(24.dp))
                Text("Checking mesh...", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text("Listening for conflicts on @$username", color = Color(0xFF666666), fontSize = 13.sp)
            }
            is SetupUiState.Claimed -> {
                Text("✓", color = Color(0xFF00C896), fontSize = 72.sp)
                Spacer(Modifier.height(16.dp))
                Text("@$username is yours!", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("No conflicts detected on mesh.", color = Color(0xFF666666), fontSize = 13.sp)
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                ) {
                    Text("Start Chatting →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            is SetupUiState.Conflict -> {
                Text("✗", color = Color(0xFFFF4D4D), fontSize = 72.sp)
                Spacer(Modifier.height(16.dp))
                Text("@${state.username} is taken", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Another node on this mesh already has this name.", color = Color(0xFF666666), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                OutlinedButton(
                    onClick = onRetry,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = ButtonDefaults.outlinedButtonBorder.copy(),
                ) {
                    Text("← Choose Another Name", color = Color(0xFF6C63FF), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
            is SetupUiState.NoMesh -> {
                Text("⚠", color = Color(0xFFFFAA00), fontSize = 64.sp)
                Spacer(Modifier.height(16.dp))
                Text("No mesh detected", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("Your name was saved locally.\nIt will be verified when you join a mesh.", color = Color(0xFF666666), fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6C63FF)),
                ) {
                    Text("Continue Anyway →", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
            else -> Unit
        }
    }
}

private data class TransportOption(
    val mode: TransportMode,
    val emoji: String,
    val label: String,
    val desc: String
)
