package com.theveloper.pixelplay.data.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.theveloper.pixelplay.R

/**
 * Shared foreground-notification plumbing for TAIS's long-running (minutes), memory-heavy
 * background jobs ([TaisStudioWorker], [StemSeparatorWorker]).
 *
 * Running as a genuine foreground service — not just a bare `WorkManager` job — matters here
 * beyond UX: Android clamps a plain background process's heap *growth target* hard (a 256MB
 * clamp was observed in practice on-device, down from a 346MB baseline), and stem separation
 * legitimately needs more than that for a whole song's decoded PCM, STFT accumulator buffers and
 * a loaded TFLite interpreter all resident at once — confirmed root cause of an `OutOfMemoryError`
 * inside [com.theveloper.pixelplay.data.tais.stems.TaisStemSeparator] before this existed.
 * Promoting the process to foreground importance while this runs is what keeps that ceiling from
 * killing it, on top of giving the user an actual progress notification for a multi-minute job.
 */
internal object TaisForegroundNotifications {
    const val CHANNEL_ID = "tais_studio_channel"
    private const val CHANNEL_NAME = "TAIS Studio"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress for on-device AI stem separation and lyric alignment"
        }
        manager.createNotificationChannel(channel)
    }

    fun foregroundInfo(
        context: Context,
        notificationId: Int,
        title: String,
        text: String,
        progress: Int,
        indeterminate: Boolean = false
    ): ForegroundInfo {
        ensureChannel(context)
        // Android 16's Live Updates (Notification.ProgressStyle) give this a real segmented
        // progress bar — download/separate/align each its own colored section — instead of a
        // plain bar. Only available API 36+; everything else keeps the determinate
        // NotificationCompat progress bar this always had.
        val notification = if (Build.VERSION.SDK_INT >= 36) {
            buildLiveUpdateNotification(context, title, text, progress)
        } else {
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.monochrome_player)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, progress, indeterminate)
                .build()
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    // Matches TaisStudioWorker's stage percentages: download 0-10, separate 10-60, align 60-100.
    private const val SEGMENT_DOWNLOAD = 10
    private const val SEGMENT_SEPARATE = 50
    private const val SEGMENT_ALIGN = 40

    @RequiresApi(36)
    private fun buildLiveUpdateNotification(context: Context, title: String, text: String, progress: Int): Notification {
        val style = Notification.ProgressStyle()
            .setStyledByProgress(false)
            .setProgressSegments(
                listOf(
                    Notification.ProgressStyle.Segment(SEGMENT_DOWNLOAD).setColor(Color.parseColor("#FFB300")),
                    Notification.ProgressStyle.Segment(SEGMENT_SEPARATE).setColor(Color.parseColor("#FF7043")),
                    Notification.ProgressStyle.Segment(SEGMENT_ALIGN).setColor(Color.parseColor("#8E24AA"))
                )
            )
            .setProgress(progress.coerceIn(0, 100))

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setStyle(style)
            .build()
    }

    const val TAIS_STUDIO_NOTIFICATION_ID = 4201
    const val STEM_SEPARATOR_NOTIFICATION_ID = 4202
    const val BS_ROFORMER_NOTIFICATION_ID = 4203
}
