package com.meshchat.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MeshChatColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    surface = Surface,
    surfaceVariant = SurfaceVariant,
    background = Background,
    onBackground = OnBackground,
    secondary = Secondary,
    tertiary = Tertiary,
    error = Error
)

@Composable
@Suppress("UNUSED_PARAMETER")
fun MeshChatTheme(
    darkTheme: Boolean = true, // We only use dark theme as requested
    dynamicColor: Boolean = false, // Dynamic color disabled as requested
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = MeshChatColorScheme,
        typography = Typography,
        content = content
    )
}
