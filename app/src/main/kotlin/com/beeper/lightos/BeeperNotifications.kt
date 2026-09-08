package com.beeper.lightos

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Plain Android notifications.
 *
 * LightOS does not (yet) claim to show notifications from third-party tools, so this
 * exists to find out what actually happens on the device. Every step is logged under
 * [TAG]; `adb logcat -s BeeperNotifications` tells you whether the post was refused,
 * silently accepted, or accepted and shown.
 */
object BeeperNotifications {
    private const val TAG = "BeeperNotifications"
    // A channel's sound and vibration are fixed once Android has seen it, so a
    // change of heart needs a new id rather than a new Builder call.
    private const val CHANNEL_ID = "beeper_messages_v2"

    /** Result of a post attempt, so the UI can say something more useful than nothing. */
    enum class Result { POSTED, NO_PERMISSION, DISABLED, REFUSED }

    private var nextId = 1000

    fun post(context: android.content.Context, title: String, text: String): Result {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Messages")
                .setDescription("New chat messages")
                // LightOS draws nothing for a tool's notification, so sound and
                // vibration are the whole of the alert.
                .setVibrationEnabled(true)
                .setVibrationPattern(longArrayOf(0, 250, 150, 250))
                .build()
        )

        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted, nothing posted")
            return Result.NO_PERMISSION
        }
        if (!manager.areNotificationsEnabled()) {
            Log.w(TAG, "notifications disabled for this tool, posting anyway to see what happens")
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        return try {
            val id = nextId++
            manager.notify(id, notification)
            Log.d(TAG, "notify($id) accepted: \"$title\" / \"$text\", enabled=${manager.areNotificationsEnabled()}")
            if (manager.areNotificationsEnabled()) Result.POSTED else Result.DISABLED
        } catch (e: SecurityException) {
            Log.e(TAG, "notify() refused", e)
            Result.REFUSED
        }
    }
}
