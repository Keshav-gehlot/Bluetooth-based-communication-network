package com.meshchat.data.media

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.meshchat.core.MediaType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaFileStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Temp dir for in-progress transfers
    private val tempDir: File
        get() = File(context.cacheDir, "media_incoming").also { it.mkdirs() }

    // Permanent dirs
    private val imageDir: File
        get() = File(context.filesDir, "media/images").also { it.mkdirs() }
    private val videoDir: File
        get() = File(context.filesDir, "media/videos").also { it.mkdirs() }

    fun createTempFile(transferId: String): File {
        return File(tempDir, "${transferId}.tmp")
            .also { if (it.exists()) it.delete() }
    }

    fun deleteTempFile(transferId: String) {
        File(tempDir, "${transferId}.tmp").delete()
    }

    suspend fun savePermanent(
        transferId: String,
        data: ByteArray,
        mediaType: MediaType,
        filename: String,
    ): String = withContext(Dispatchers.IO) {
        // Sanitize filename — strip all path separators
        val safeName = filename
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .take(64)

        val dir = if (mediaType == MediaType.IMAGE) imageDir else videoDir
        val dest = File(dir, safeName)

        // Atomic write: write to .tmp first, then rename
        val tmp = File(dir, "${safeName}.writing")
        tmp.writeBytes(data)
        tmp.renameTo(dest)  // atomic on same filesystem

        dest.absolutePath
    }

    // FileProvider URI for sharing outside app (optional future feature)
    fun getFileProviderUri(filePath: String): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            File(filePath)
        )
    }

    // Cleanup: delete transfers older than 7 days from temp dir
    suspend fun cleanupStale() = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L
        tempDir.listFiles()
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }
    }

    // Get all stored media for a conversation
    fun getMediaForConversation(conversationId: String): List<File> {
        return (imageDir.listFiles() ?: emptyArray<File>()).toList() +
                (videoDir.listFiles() ?: emptyArray<File>()).toList()
    }
}
