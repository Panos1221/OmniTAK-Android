package soy.engindearing.omnitak.mobile.domain

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
import androidx.core.content.ContextCompat
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.R

/**
 * Foreground service that holds the user-perceivable privilege so
 * Android's Doze / app-standby scheduler doesn't kill the TLS read loop
 * within ~10 seconds of backgrounding.
 *
 * The service does NOT own the [TAKConnection] — that still lives on
 * the application-scoped [ServerManager]. This class only carries the
 * `startForeground` privilege so the OS treats our networking as
 * intentional ongoing work. When [ServerManager.connectionState] flips
 * to Connected we start the service; on Disconnected we stop it.
 *
 * Issue #5 — H!rO reported the connection drops after a few seconds in
 * the background and confirmed disabling battery optimisation works
 * around it, which pointed straight at Doze.
 */
class TAKConnectionService : Service() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val serverName = intent?.getStringExtra(EXTRA_SERVER_NAME) ?: "TAK Server"
        startForeground(NOTIFICATION_ID, buildNotification(serverName), foregroundServiceTypeOrZero())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun foregroundServiceTypeOrZero(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0

    private fun buildNotification(serverName: String): Notification {
        ensureChannel(this)
        val tap = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OmniTAK connected")
            .setContentText("Holding TLS to $serverName")
            .setSmallIcon(R.mipmap.app_icon)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "tak_connection"
        private const val NOTIFICATION_ID = 1001
        private const val EXTRA_SERVER_NAME = "server_name"

        fun start(context: Context, serverName: String) {
            ensureChannel(context)
            val intent = Intent(context, TAKConnectionService::class.java)
                .putExtra(EXTRA_SERVER_NAME, serverName)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TAKConnectionService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "TAK connection",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent indicator while OmniTAK holds an active TAK Server socket."
                    setShowBadge(false)
                },
            )
        }
    }
}
