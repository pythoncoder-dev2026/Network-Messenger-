package com.network.messenger.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.network.messenger.domain.model.PermissionStatus

object PermissionHelper {

    fun requiredRuntimePermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        add(Manifest.permission.RECORD_AUDIO)
    }

    fun nearbyPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun hasNearbyPermissions(context: Context): Boolean =
        nearbyPermissions().all { isGranted(context, it) }

    fun hasMicrophonePermission(context: Context): Boolean =
        isGranted(context, Manifest.permission.RECORD_AUDIO)

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun inspectAll(context: Context): List<PermissionStatus> {
        return requiredRuntimePermissions().map { perm ->
            val (label, requiredFor) = when (perm) {
                Manifest.permission.BLUETOOTH_SCAN -> "Bluetooth Scan" to "Nearby Connections discovery"
                Manifest.permission.BLUETOOTH_ADVERTISE -> "Bluetooth Advertise" to "Nearby Connections advertising"
                Manifest.permission.BLUETOOTH_CONNECT -> "Bluetooth Connect" to "Nearby Connections links"
                Manifest.permission.NEARBY_WIFI_DEVICES -> "Nearby Wi-Fi Devices" to "Nearby & Wi-Fi Direct discovery"
                Manifest.permission.ACCESS_FINE_LOCATION -> "Fine Location (API ≤ 32)" to "BT/Wi-Fi peer discovery"
                Manifest.permission.RECORD_AUDIO -> "Microphone" to "Push-to-Talk voice"
                Manifest.permission.POST_NOTIFICATIONS -> "Notifications" to "Foreground service status"
                else -> perm.substringAfterLast('.') to "Mesh operations"
            }
            PermissionStatus(
                name = perm,
                label = label,
                granted = isGranted(context, perm),
                requiredFor = requiredFor
            )
        }
    }
}
