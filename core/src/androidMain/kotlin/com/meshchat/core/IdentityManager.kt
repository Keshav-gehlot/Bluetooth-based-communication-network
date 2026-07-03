package com.meshchat.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshchat_identity")

@Singleton
class IdentityManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    private object Keys {
        // Dual-transport IDs (permanent)
        val BT_NODE_ID           = stringPreferencesKey("bt_node_id")
        val WIFI_NODE_ID         = stringPreferencesKey("wifi_node_id")
        // Legacy key kept for migration reads only
        val NODE_ID_LEGACY       = stringPreferencesKey("node_id")
        // Identity fields
        val USERNAME             = stringPreferencesKey("username")
        val AVATAR_SEED          = stringPreferencesKey("avatar_seed")
        val CREATED_AT           = longPreferencesKey("created_at")
        val USERNAME_CLAIMED     = booleanPreferencesKey("username_claimed")
        // Setup / mesh settings
        val IS_SETUP_COMPLETED   = booleanPreferencesKey("is_setup_completed")
        val MAX_HOPS             = intPreferencesKey("max_hops")
        val IDS_ENABLED          = booleanPreferencesKey("ids_enabled")
        val TRANSPORT_MODE       = stringPreferencesKey("transport_mode")
    }

    init {
        // Ensure permanent node IDs are generated exactly once on first launch
        runBlocking {
            val prefs = dataStore.data.first()
            if (prefs[Keys.BT_NODE_ID] == null) {
                dataStore.edit { editPrefs ->
                    val btId   = NodeIdGenerator.generateBtId()
                    val wifiId = NodeIdGenerator.generateWifiId()
                    editPrefs[Keys.BT_NODE_ID]         = btId
                    editPrefs[Keys.WIFI_NODE_ID]        = wifiId
                    editPrefs[Keys.USERNAME]             = ""
                    editPrefs[Keys.AVATAR_SEED]          = btId   // avatarSeed == btNodeId
                    editPrefs[Keys.CREATED_AT]           = System.currentTimeMillis()
                    editPrefs[Keys.USERNAME_CLAIMED]     = false
                    editPrefs[Keys.IS_SETUP_COMPLETED]   = false
                    editPrefs[Keys.MAX_HOPS]             = 4
                    editPrefs[Keys.IDS_ENABLED]          = true
                    editPrefs[Keys.TRANSPORT_MODE]       = TransportMode.BLUETOOTH.name
                    Log.d("IdentityManager", "Generated new dual IDs — BT: ...${btId.takeLast(4)}, WiFi: ...${wifiId.takeLast(4)}")
                }
            }
        }
    }

    // ── Identity flow ──────────────────────────────────────────────────────────
    fun getIdentity(): Flow<NodeIdentity> {
        return dataStore.data.map { prefs ->
            NodeIdentity(
                username        = prefs[Keys.USERNAME]         ?: "",
                btNodeId        = prefs[Keys.BT_NODE_ID]       ?: "",
                wifiNodeId      = prefs[Keys.WIFI_NODE_ID]     ?: "",
                avatarSeed      = prefs[Keys.AVATAR_SEED]      ?: "",
                createdAt       = prefs[Keys.CREATED_AT]       ?: 0L,
                usernameClaimed = prefs[Keys.USERNAME_CLAIMED] ?: false,
            )
        }
    }

    fun getIdentityBlocking(): NodeIdentity = runBlocking { getIdentity().first() }

    // ── Create / claim ─────────────────────────────────────────────────────────
    suspend fun createIdentity(username: String): NodeIdentity {
        val sanitized = username.lowercase().trim()
        dataStore.edit { prefs ->
            prefs[Keys.USERNAME]         = sanitized
            prefs[Keys.USERNAME_CLAIMED] = false
        }
        return getIdentity().first()
    }

    suspend fun markUsernameClaimed() {
        dataStore.edit { prefs ->
            prefs[Keys.USERNAME_CLAIMED] = true
        }
    }

    // ── Transport ID helpers ───────────────────────────────────────────────────
    fun getActiveNodeId(mode: TransportMode): String {
        val prefs = runBlocking { dataStore.data.first() }
        return when (mode) {
            TransportMode.BLUETOOTH -> prefs[Keys.BT_NODE_ID]   ?: ""
            TransportMode.WIFI      -> prefs[Keys.WIFI_NODE_ID] ?: ""
            TransportMode.BOTH      -> prefs[Keys.BT_NODE_ID]   ?: "" // BT is canonical in BOTH
        }
    }

    // ── Display name (legacy compat) ───────────────────────────────────────────
    suspend fun updateDisplayName(name: String) {
        dataStore.edit { prefs ->
            prefs[Keys.USERNAME] = name
        }
    }

    // ── Setup state ────────────────────────────────────────────────────────────
    fun isSetupCompleted(): Boolean = runBlocking {
        dataStore.data.first()[Keys.IS_SETUP_COMPLETED] ?: false
    }

    suspend fun setSetupCompleted(completed: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.IS_SETUP_COMPLETED] = completed }
    }

    suspend fun resetIdentity() {
        dataStore.edit { prefs ->
            // Generate fresh permanent IDs on reset
            val newBtId   = NodeIdGenerator.generateBtId()
            val newWifiId = NodeIdGenerator.generateWifiId()
            prefs[Keys.BT_NODE_ID]         = newBtId
            prefs[Keys.WIFI_NODE_ID]        = newWifiId
            prefs[Keys.USERNAME]             = ""
            prefs[Keys.AVATAR_SEED]          = newBtId
            prefs[Keys.USERNAME_CLAIMED]     = false
            prefs[Keys.IS_SETUP_COMPLETED]   = false
            Log.d("IdentityManager", "Identity reset — new BT: ...${newBtId.takeLast(4)}, WiFi: ...${newWifiId.takeLast(4)}")
        }
    }

    // ── Mesh settings ──────────────────────────────────────────────────────────
    fun getMaxHops(): Flow<Int> = dataStore.data.map { it[Keys.MAX_HOPS] ?: 4 }

    fun getIdsEnabled(): Flow<Boolean> = dataStore.data.map { it[Keys.IDS_ENABLED] ?: true }

    suspend fun setMaxHops(hops: Int) {
        dataStore.edit { prefs -> prefs[Keys.MAX_HOPS] = hops }
    }

    suspend fun setIdsEnabled(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.IDS_ENABLED] = enabled }
    }

    // ── Transport mode persistence ─────────────────────────────────────────────
    fun getTransportMode(): Flow<TransportMode> = dataStore.data.map { prefs ->
        prefs[Keys.TRANSPORT_MODE]?.let { runCatching { TransportMode.valueOf(it) }.getOrNull() }
            ?: TransportMode.BLUETOOTH
    }

    suspend fun saveTransportMode(mode: TransportMode) {
        dataStore.edit { prefs -> prefs[Keys.TRANSPORT_MODE] = mode.name }
    }
}

