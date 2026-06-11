package com.meshchat.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.meshchat.MainActivity

data class MeshWidgetState(
    val onlineCount: Int = 0,
    val lastMessagePreview: String = "No messages yet",
    val isActive: Boolean = false
)

class MeshStatusWidget : GlanceAppWidget() {

    // A simple in-memory state for demonstration. 
    // In a real app, this should be read from DataStore/Room or passed from WorkManager via GlanceStateDefinition.
    companion object {
        var currentState = MeshWidgetState()
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            MeshStatusWidgetContent(state = currentState)
        }
    }
}

@Composable
fun MeshStatusWidgetContent(state: MeshWidgetState) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF111411)))
                .padding(12.dp)
                .cornerRadius(16.dp)
                .appWidgetBackground()
                .clickable(actionStartActivity<MainActivity>())
        ) {
            val statusColor = if (state.isActive) ColorProvider(Color(0xFF00E87A)) else ColorProvider(Color(0xFF1E241E))
            
            Text(
                text = "MeshChat", 
                style = TextStyle(color = ColorProvider(Color(0xFF00E87A)))
            )
            
            Text(
                text = if (state.isActive) "● ${state.onlineCount} nodes nearby" else "Searching...",
                style = TextStyle(color = statusColor)
            )
            
            Text(
                text = state.lastMessagePreview,
                style = TextStyle(color = ColorProvider(Color(0xFFF0F7F0)))
            )
        }
    }
}
