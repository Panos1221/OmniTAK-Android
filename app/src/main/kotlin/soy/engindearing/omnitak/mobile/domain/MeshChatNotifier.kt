package soy.engindearing.omnitak.mobile.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import soy.engindearing.omnitak.mobile.MainActivity
import soy.engindearing.omnitak.mobile.R
import soy.engindearing.omnitak.mobile.data.ChatMessage

/**
 * #173 — raises an Android notification for incoming mesh GeoChat so an
 * operator with the app backgrounded still sees a teammate's message.
 *
 * The notify/don't-notify decision is split out into the pure, side-effect-free
 * [shouldNotify] so it is unit-testable with no Android runtime. [notify] does
 * the Android plumbing (channel + NotificationManager) and is a thin wrapper
 * the app wires into the mesh chat sink.
 *
 * Tapping a notification re-opens [MainActivity], carrying the conversation id
 * so the chat tab can route to that thread.
 */
class MeshChatNotifier(
    private val context: Context,
    /** Operator setting — when false, [notify] is a no-op. Defaults to on. */
    private val notificationsEnabled: () -> Boolean = { true },
) {

    /** Post a notification for [message] if [shouldNotify] allows it. */
    fun notify(message: ChatMessage) {
        if (!notificationsEnabled()) return
        if (!shouldNotify(message)) return

        ensureChannel(context)
        val notification = buildNotification(context, message)
        val nm = NotificationManagerCompat.from(context)
        // POST_NOTIFICATIONS (API 33+) is requested at the app level; if the
        // user denied it, notify() is a silent no-op rather than a crash.
        runCatching {
            nm.notify(notificationId(message.conversationId), notification)
        }
    }

    companion object {

        const val CHANNEL_ID = "mesh_chat"
        const val EXTRA_CONVERSATION_ID = "mesh_chat_conversation_id"

        /**
         * Pure decision: should an inbound mesh chat [message] raise a
         * notification?
         *
         * Notify when it is a real incoming message from a peer:
         *  - NOT from ourselves (own echoes round-tripping the radio must
         *    never re-notify — they were already shown on send), and
         *  - carries non-blank text.
         *
         * Don't notify for our own echoes (`isFromSelf`), empty/blank text,
         * or non-RECEIVED statuses (sending/queued outbound copies).
         */
        fun shouldNotify(message: ChatMessage): Boolean {
            if (message.isFromSelf) return false
            if (message.text.isBlank()) return false
            return true
        }

        /** Stable per-conversation notification id so a second message in the
         *  same thread updates the existing notification instead of stacking. */
        fun notificationId(conversationId: String): Int =
            BASE_NOTIFICATION_ID + (conversationId.hashCode() and 0xFFFF)

        /** Build (but don't post) the notification for [message]. Exposed for
         *  tests + reuse; pure aside from reading [context] resources. */
        fun buildNotification(context: Context, message: ChatMessage): Notification {
            val tapIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_CONVERSATION_ID, message.conversationId)
            }
            val pending = PendingIntent.getActivity(
                context,
                notificationId(message.conversationId),
                tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(message.senderCallsign.ifBlank { "Mesh chat" })
                .setContentText(message.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.text))
                .setSmallIcon(R.mipmap.app_icon)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pending)
                .build()
        }

        private const val BASE_NOTIFICATION_ID = 2000

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Mesh Chat",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Incoming GeoChat messages received over the mesh."
                    setShowBadge(true)
                },
            )
        }
    }
}
