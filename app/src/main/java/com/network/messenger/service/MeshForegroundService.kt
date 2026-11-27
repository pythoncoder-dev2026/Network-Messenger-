package com.network.messenger.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.network.messenger.MainActivity
import com.network.messenger.R
import com.network.messenger.data.repository.LogRepository
import com.network.messenger.domain.model.LogCategory
import com.network.messenger.mesh.MeshRouter
import com.network.messenger.util.PermissionHelper
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground service keeping encrypted mesh transports, multi-hop relay, and store-and-forward
 * active while the screen is off. Dynamically picks Android 14+ foregroundServiceType flags
 * based on currently granted permissions to avoid SecurityExceptions.
 */
@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject lateinit var meshRouter: MeshRouter
    @Inject lateinit var logRepository: LogRepository

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        meshRouter.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            logRepository.info(LogCategory.SYSTEM, "Background mesh foreground service stopped")
            return START_NOT_STICKY
        }

        val notification = buildNotification()
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                PermissionHelper.hasNearbyPermissions(this)
            ) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            }
            type
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
            logRepository.info(LogCategory.SYSTEM, "Mesh foreground service active (screen-off relay enabled)")
        } catch (e: Exception) {
            logRepository.warn(LogCategory.SYSTEM, "Foreground service start deferred: ${e.message}")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_mesh),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_mesh_desc)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_mesh_title))
            .setContentText(getString(R.string.notification_mesh_running))
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "network_messenger_mesh_service"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.network.messenger.action.START_MESH_SERVICE"
        const val ACTION_STOP = "com.network.messenger.action.STOP_MESH_SERVICE"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { context.startForegroundService(intent) }
            } else {
                runCatching { context.startService(intent) }
            }
        }
    }
}
