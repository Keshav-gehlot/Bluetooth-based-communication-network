package com.meshchat.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.meshchat.core.TransportMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralised permission resolution for dual-transport Nearby Connections.
 *
 * Handles API-level branching:
 * - BT pre-31: BLUETOOTH, BLUETOOTH_ADMIN, ACCESS_FINE_LOCATION
 * - BT API 31+: BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT, BLUETOOTH_SCAN
 * - WiFi all:  ACCESS_FINE_LOCATION (pre-33), CHANGE_NETWORK_STATE
 * - WiFi 33+:  NEARBY_WIFI_DEVICES
 */
@Singleton
class PermissionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun getRequiredPermissions(mode: TransportMode): List<String> = buildList {
        when (mode) {
            TransportMode.BLUETOOTH -> addAll(bluetoothPermissions())
            TransportMode.WIFI      -> addAll(wifiPermissions())
            TransportMode.BOTH      -> {
                addAll(bluetoothPermissions())
                addAll(wifiPermissions())
            }
        }
    }.distinct()

    fun arePermissionsGranted(mode: TransportMode): Boolean =
        getRequiredPermissions(mode).all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }

    private fun bluetoothPermissions(): List<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        listOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
    } else {
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }

    private fun wifiPermissions(): List<String> = buildList {
        add(Manifest.permission.CHANGE_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    companion object {
        /**
         * User-readable rationale for each transport mode.
         */
        fun rationaleFor(mode: TransportMode): String = when (mode) {
            TransportMode.BLUETOOTH ->
                "Bluetooth permissions are required to discover and connect to nearby mesh peers."
            TransportMode.WIFI ->
                "Wi-Fi Direct permissions are required for longer-range, higher-speed mesh connections."
            TransportMode.BOTH ->
                "Both Bluetooth and Wi-Fi permissions are required to use dual-radio mesh mode."
        }
    }
}
