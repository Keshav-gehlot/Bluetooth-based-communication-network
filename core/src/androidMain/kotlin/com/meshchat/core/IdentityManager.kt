package com.meshchat.core

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import java.security.KeyPairGenerator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshchat_identity")

@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        val NODE_ID = stringPreferencesKey("node_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val AVATAR_SEED = stringPreferencesKey("avatar_seed")
        val PUBLIC_KEY = stringPreferencesKey("public_key")
        val CREATED_AT = longPreferencesKey("created_at")
        val IS_SETUP_COMPLETED = booleanPreferencesKey("is_setup_completed")
        val MAX_HOPS = intPreferencesKey("max_hops")
        val IDS_ENABLED = booleanPreferencesKey("ids_enabled")
    }

    init {
        // Run blocking initialization to ensure nodeId is generated on first access
        runBlocking {
            val prefs = dataStore.data.first()
            if (prefs[Keys.NODE_ID] == null) {
                dataStore.edit { editPrefs ->
                    val nodeId = UUID.randomUUID().toString()
                    val keyGen = KeyPairGenerator.getInstance("EC")
                    keyGen.initialize(256)
                    val keyPair = keyGen.generateKeyPair()
                    val publicKeyBase64 = Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)

                    editPrefs[Keys.NODE_ID] = nodeId
                    editPrefs[Keys.DISPLAY_NAME] = ""
                    editPrefs[Keys.AVATAR_SEED] = ""
                    editPrefs[Keys.PUBLIC_KEY] = publicKeyBase64
                    editPrefs[Keys.CREATED_AT] = System.currentTimeMillis()
                    editPrefs[Keys.IS_SETUP_COMPLETED] = false
                    editPrefs[Keys.MAX_HOPS] = 4
                    editPrefs[Keys.IDS_ENABLED] = true
                }
            }
        }
    }

    fun getIdentity(): Flow<UserIdentity> {
        return dataStore.data.map { prefs ->
            UserIdentity(
                nodeId = prefs[Keys.NODE_ID] ?: "",
                displayName = prefs[Keys.DISPLAY_NAME] ?: "",
                avatarSeed = prefs[Keys.AVATAR_SEED] ?: "",
                publicKey = prefs[Keys.PUBLIC_KEY] ?: "",
                createdAt = prefs[Keys.CREATED_AT] ?: 0L
            )
        }
    }

    suspend fun updateDisplayName(name: String) {
        dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = name
            prefs[Keys.AVATAR_SEED] = name.lowercase()
        }
    }

    fun isSetupCompleted(): Boolean = runBlocking {
        dataStore.data.first()[Keys.IS_SETUP_COMPLETED] ?: false
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.IS_SETUP_COMPLETED] = completed
        }
    }

    suspend fun resetIdentity() {
        dataStore.edit { prefs ->
            prefs[Keys.DISPLAY_NAME] = ""
            prefs[Keys.AVATAR_SEED] = ""
            prefs[Keys.IS_SETUP_COMPLETED] = false
        }
    }

    fun getMaxHops(): Flow<Int> {
        return dataStore.data.map { prefs ->
            prefs[Keys.MAX_HOPS] ?: 4
        }
    }

    fun getIdsEnabled(): Flow<Boolean> {
        return dataStore.data.map { prefs ->
            prefs[Keys.IDS_ENABLED] ?: true
        }
    }

    suspend fun setMaxHops(hops: Int) {
        dataStore.edit { prefs ->
            prefs[Keys.MAX_HOPS] = hops
        }
    }

    suspend fun setIdsEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.IDS_ENABLED] = enabled
        }
    }
}
