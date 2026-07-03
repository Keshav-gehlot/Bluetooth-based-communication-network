package com.meshchat.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Base64
import com.meshchat.core.MediaChunkPayload
import com.meshchat.core.MediaLimits
import com.meshchat.core.MediaType
import com.meshchat.core.PlatformCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

import dagger.hilt.android.qualifiers.ApplicationContext

@Singleton
class MediaProcessor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val platformCrypto = PlatformCrypto()

    // Step 1 — Compress image
    suspend fun compressImage(uri: Uri): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = if (Build.VERSION.SDK_INT >= 28) {
                    val src = ImageDecoder.createSource(context.contentResolver, uri)
                    ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                    }
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
                }

                // Scale down keeping aspect ratio
                val scaled = scaleBitmap(bitmap, MediaLimits.IMAGE_MAX_DIMENSION)

                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
        }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        val scale = maxDimension.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(
            bitmap, (w * scale).toInt(), (h * scale).toInt(), true
        )
    }

    // Step 2 — Generate thumbnail (always JPEG, max 8KB)
    suspend fun generateThumbnail(
        data: ByteArray,
        mediaType: MediaType
    ): String = withContext(Dispatchers.IO) {
        val bitmap = when (mediaType) {
            MediaType.IMAGE -> {
                val bmp = BitmapFactory.decodeByteArray(data, 0, data.size)
                scaleBitmap(bmp, 200)  // 200px thumbnail
            }
            MediaType.VIDEO -> {
                // Extract first frame
                val tmpFile = File(context.cacheDir, "thumb_tmp.mp4")
                tmpFile.writeBytes(data)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(tmpFile.absolutePath)
                    val frame = retriever.getFrameAtTime(0) ?: Bitmap.createBitmap(
                        200, 200, Bitmap.Config.ARGB_8888
                    )
                    scaleBitmap(frame, 200)
                } finally {
                    retriever.release()
                    tmpFile.delete()
                }
            }
        }
        val out = ByteArrayOutputStream()
        var quality = 85
        do {
            out.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            quality -= 10
        } while (out.size() > MediaLimits.THUMBNAIL_MAX_BYTES && quality > 20)
        Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // Step 3 — Validate video duration
    suspend fun validateVideo(uri: Uri): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, uri)
                    val durationMs = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION
                    )?.toLongOrNull() ?: 0L

                    if (durationMs > MediaLimits.VIDEO_MAX_SECONDS * 1000L) {
                        throw IllegalArgumentException(
                            "Video too long: ${durationMs/1000}s max ${MediaLimits.VIDEO_MAX_SECONDS}s"
                        )
                    }
                } finally {
                    retriever.release()
                }
                context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            }
        }

    // Step 4 — Chunk + encrypt file bytes
    fun chunkAndEncrypt(
        data: ByteArray,
        chunkSize: Int,
        key: ByteArray,
        transferId: String = UUID.randomUUID().toString()
    ): List<MediaChunkPayload> {
        val totalChunks = ceil(data.size.toDouble() / chunkSize).toInt()

        return data.toList()
            .chunked(chunkSize)
            .mapIndexed { index, chunkBytes ->
                val raw = chunkBytes.toByteArray()
                val encrypted = platformCrypto.encrypt(raw, key)
                val hmac = platformCrypto.hmacSign(raw, key)
                MediaChunkPayload(
                    transferId = transferId,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    data = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                    chunkHmac = Base64.encodeToString(hmac, Base64.NO_WRAP),
                )
            }
    }

    // Decrypt chunk bytes
    fun decryptChunk(
        payload: MediaChunkPayload,
        key: ByteArray
    ): ByteArray? {
        val encrypted = Base64.decode(payload.data, Base64.NO_WRAP)
        val decrypted = platformCrypto.decrypt(encrypted, key)
        val hmac = Base64.decode(payload.chunkHmac, Base64.NO_WRAP)
        val verify = platformCrypto.hmacVerify(decrypted, hmac, key)
        return if (verify) decrypted else null
    }

    // Step 5 — SHA-256 checksum for whole-file verification
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data)
            .joinToString("") { "%02x".format(it) }
    }
}
