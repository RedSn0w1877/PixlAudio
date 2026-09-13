package com.theveloper.pixelplay.data.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.theveloper.pixelplay.R

internal object AppUpdateNotifications {
    /** Handled by MainActivity: opening the app first lets it start Android's install screen. */
    const val ACTION_OPEN_UPDATE = "com.theveloper.pixelplay.ACTION_APP_UPDATE"

    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4210
    // Por nombre de clase: referenciar MainActivity::class exigiría opt-in a UnstableApi de Media3.
    private const val MAIN_ACTIVITY_CLASS = "com.theveloper.pixelplay.MainActivity"

    fun showUpdate(context: Context, update: AvailableUpdate, downloaded: Boolean) {
        val title = context.getString(
            if (downloaded) R.string.app_update_notification_ready_title
            else R.string.app_update_notification_available_title,
            update.versionName,
        )
        val text = context.getString(
            if (downloaded) R.string.app_update_notification_ready_text
            else R.string.app_update_notification_available_text,
        )
        post(context, title, text, ACTION_OPEN_UPDATE, android.R.drawable.stat_sys_download_done)
    }

    fun showUpdated(context: Context, versionName: String) {
        post(
            context,
            context.getString(R.string.app_update_notification_updated_title, versionName),
            context.getString(R.string.app_update_notification_updated_text),
            action = null,
            icon = android.R.drawable.stat_sys_download_done,
        )
    }

    @SuppressLint("MissingPermission") // Guarded by areNotificationsEnabled().
    private fun post(context: Context, title: String, text: String, action: String?, icon: Int) {
        val notifications = NotificationManagerCompat.from(context)
        if (!notifications.areNotificationsEnabled()) return
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_update_notification_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
        val openApp = Intent()
            .setClassName(context.packageName, MAIN_ACTIVITY_CLASS)
            .setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        notifications.notify(NOTIFICATION_ID, notification)
    }
}
