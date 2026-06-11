package com.meshchat.data.security

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RoomCodeManager(
    private val context: Context,
    private val keyManager: KeyManager
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "meshchat_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val _joinedRooms = MutableStateFlow(
        encryptedPrefs.getStringSet(PREF_JOINED_ROOMS, emptySet()) ?: emptySet()
    )
    val joinedRooms: StateFlow<Set<String>> = _joinedRooms.asStateFlow()

    init {
        // Migration from old single-room system
        val oldWrappedKey = encryptedPrefs.getString(PREF_WRAPPED_MESH_KEY_OLD, null)
        val oldRoomCodeHash = encryptedPrefs.getString(PREF_ROOM_CODE_HASH_OLD, null)
        if (oldWrappedKey != null && oldRoomCodeHash != null) {
            encryptedPrefs.edit()
                .remove(PREF_WRAPPED_MESH_KEY_OLD)
                .remove(PREF_ROOM_CODE_HASH_OLD)
                .apply()
        }

        if (_joinedRooms.value.isEmpty()) {
            Timber.d("No rooms found, initializing default room")
            joinRoom(DEFAULT_ROOM_CODE)
        }
    }

    fun joinRoom(newCode: String) {
        Timber.d("Joining room $newCode")
        
        val salt = ByteArray(16).apply { 
            val md = MessageDigest.getInstance("SHA-256")
            md.digest("MeshChatSalt_v1".toByteArray()).copyInto(this, 0, 0, 16)
        }
        
        val spec = PBEKeySpec(newCode.toCharArray(), salt, 10000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derivedKey = factory.generateSecret(spec).encoded
        
        val wrappedKeyBlob = keyManager.wrapKey(derivedKey)
        val base64Wrapped = Base64.encodeToString(wrappedKeyBlob, Base64.NO_WRAP)
        
        val currentRooms = _joinedRooms.value.toMutableSet()
        currentRooms.add(newCode)
        
        encryptedPrefs.edit()
            .putString("mesh_key_$newCode", base64Wrapped)
            .putStringSet(PREF_JOINED_ROOMS, currentRooms)
            .apply()
            
        _joinedRooms.value = currentRooms
    }

    fun getAllMeshKeys(): Map<String, ByteArray> {
        val keys = mutableMapOf<String, ByteArray>()
        _joinedRooms.value.forEach { code ->
            val base64Wrapped = encryptedPrefs.getString("mesh_key_$code", null)
            if (base64Wrapped != null) {
                try {
                    val wrappedBlob = Base64.decode(base64Wrapped, Base64.DEFAULT)
                    val unwrapped = keyManager.unwrapKey(wrappedBlob)
                    keys[code] = unwrapped
                } catch (e: Exception) {
                    Timber.e(e, "Failed to unwrap mesh key for room $code")
                }
            }
        }
        return keys
    }

    fun getDerivedMeshKey(code: String): ByteArray? {
        val base64Wrapped = encryptedPrefs.getString("mesh_key_$code", null) ?: return null
        return try {
            val wrappedBlob = Base64.decode(base64Wrapped, Base64.DEFAULT)
            keyManager.unwrapKey(wrappedBlob)
        } catch (e: Exception) {
            Timber.e(e, "Failed to unwrap mesh key for room $code")
            null
        }
    }

    companion object {
        private const val PREF_WRAPPED_MESH_KEY_OLD = "wrapped_mesh_key"
        private const val PREF_ROOM_CODE_HASH_OLD = "room_code_hash"
        
        private const val PREF_JOINED_ROOMS = "joined_rooms_set"
        private const val DEFAULT_ROOM_CODE = "MESH0"
    }
}
