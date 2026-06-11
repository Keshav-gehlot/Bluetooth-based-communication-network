package com.meshchat.worker

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.meshchat.domain.repository.ChatRepository
import com.meshchat.domain.repository.PeerRepository
import com.meshchat.widget.MeshStatusWidget
import com.meshchat.widget.MeshWidgetState
import kotlinx.coroutines.flow.first
import timber.log.Timber

// Ideally inject repositories via HiltWorker, but keeping simple for this mock-up
class MeshWidgetUpdaterWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            // In a fully wired Hilt setup, we would inject PeerRepository and ChatRepository.
            // For now, we update the static state with a dummy/fetch action.
            
            val state = MeshWidgetState(
                onlineCount = 2, // Mocked 
                lastMessagePreview = "Incoming message...", // Mocked
                isActive = true
            )
            
            MeshStatusWidget.currentState = state
            MeshStatusWidget().updateAll(applicationContext)
            
            Timber.d("Widget updated successfully.")
            Result.success()
        } catch (e: Exception) {
            Timber.e(e, "Failed to update widget")
            Result.failure()
        }
    }
}
