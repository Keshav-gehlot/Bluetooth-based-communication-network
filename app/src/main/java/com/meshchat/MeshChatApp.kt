package com.meshchat

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.meshchat.util.SecureDebugTree
import com.meshchat.worker.MeshWidgetUpdaterWorker
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class MeshChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        if (BuildConfig.DEBUG) {
            Timber.plant(SecureDebugTree())
        } else {
            // In a real app, this might be a Crashlytics tree or no-op
            // The prompt says "In production builds: use no-op Timber tree"
            // We do nothing, so no tree is planted, hence no-op.
        }

        setupWidgetWorker()
    }

    private fun setupWidgetWorker() {
        val widgetWorkRequest = PeriodicWorkRequestBuilder<MeshWidgetUpdaterWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "MeshWidgetUpdater",
            ExistingPeriodicWorkPolicy.UPDATE,
            widgetWorkRequest
        )
    }
}
