package com.meshchat.ui.features.splash

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.meshchat.ui.theme.OrbitronFamily
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SplashScreen(
    onNavigateToSetup: () -> Unit,
    onNavigateToChats: () -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        delay(2000)
        if (viewModel.isFirstLaunch()) {
            onNavigateToSetup()
        } else {
            onNavigateToChats()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            // Pulse & Rotate Canvas Graphic
            PulsingMeshNodeGraphic()

            Spacer(modifier = Modifier.height(48.dp))

            // Title
            Text(
                text = "MeshChat",
                fontFamily = OrbitronFamily,
                fontWeight = FontWeight.Black,
                fontSize = 42.sp,
                color = Color(0xFF00E87A),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Subtitle
            Text(
                text = "No internet. No servers. Just people.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun PulsingMeshNodeGraphic(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mesh_pulse")

    // Pulse size and alpha
    val pulseScale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    // Continuous smooth rotation
    val rotationAngle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(12000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotationAngle"
    )

    val greenColor = Color(0xFF00E87A)

    Canvas(
        modifier = modifier
            .size(180.dp)
    ) {
        val center = center
        val maxOrbitRadius = size.minDimension * 0.35f
        val currentOrbitRadius = maxOrbitRadius * pulseScale

        // Draw connections and orbiting nodes
        for (i in 0 until 5) {
            val angleDegrees = (i * 72.0) + rotationAngle
            val angleRad = Math.toRadians(angleDegrees).toFloat()
            val orbitX = center.x + currentOrbitRadius * cos(angleRad)
            val orbitY = center.y + currentOrbitRadius * sin(angleRad)
            val orbitPoint = Offset(orbitX, orbitY)

            // Draw line from center to orbit point
            drawLine(
                color = greenColor.copy(alpha = 0.5f * pulseScale),
                start = center,
                end = orbitPoint,
                strokeWidth = 2.dp.toPx()
            )

            // Draw orbit circle
            drawCircle(
                color = greenColor,
                radius = 7.dp.toPx() * pulseScale,
                center = orbitPoint
            )
        }

        // Draw central circle
        drawCircle(
            color = greenColor,
            radius = 14.dp.toPx() * pulseScale,
            center = center
        )
    }
}
