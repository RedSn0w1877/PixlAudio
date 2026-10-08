package com.theveloper.pixelplay.data.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.local.DownloadOutcome
import com.theveloper.pixelplay.data.ai.local.DownloadedModelManager
import com.theveloper.pixelplay.data.ai.local.ModelFileDownloader
import com.theveloper.pixelplay.data.ai.local.RangedResponse
import com.theveloper.pixelplay.data.ai.local.RangedSource
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the "Use downloaded AI model" file (2.6 GB) as a foreground dataSync job, resuming
 * a partial `.part` with HTTP Range after a network drop, and verifying size + SHA-256 before the
 * file is used ([ModelFileDownloader]).
 *
 * The default OkHttpClient is fine here (Hugging Face, not YouTube), but its 8 s timeouts are
 * not, so it gets a longer read timeout like the app updater's.
 */
@HiltWorker
class LocalModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelManager: DownloadedModelManager,
    okHttpClient: OkHttpClient,
) : CoroutineWorker(appContext, workerParams) {

    private val client = okHttpClient.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val spec = modelManager.spec
        runCatching { setForeground(foregroundInfo(0L, spec.sizeBytes, verifying = false)) }
            .onFailure { Timber.tag(TAG).w(it, "Couldn't promote the model download to the foreground") }

        val source = RangedSource { offset ->
            val request = Request.Builder()
                .url(spec.url)
                .apply { if (offset > 0L) header("Range", "bytes=$offset-") }
                .build()
            val response = client.newCall(request).execute()
            val body = response.body
            if (body == null) {
                response.close()
                throw IOException("Empty response")
            }
            RangedResponse(response.code, body.contentLength(), body.byteStream())
        }

        val downloader = ModelFileDownloader(
            spec = spec,
            source = source,
            partFile = modelManager.partFile,
            finalFile = modelManager.modelFile,
            freeBytes = modelManager::freeBytes,
        )

        var lastNotifiedPercent = -1
        val outcome = downloader.run(
            onProgress = { bytes, total ->
                // ModelFileDownloader calls this at most once per percent.
                runBlocking { setProgress(workDataOf(PROGRESS_BYTES to bytes, PROGRESS_TOTAL to total)) }
                val percent = (bytes * 100 / total).toInt()
                if (percent != lastNotifiedPercent) {
                    lastNotifiedPercent = percent
                    runCatching { runBlocking { setForeground(foregroundInfo(bytes, total, verifying = false)) } }
                }
            },
            onVerifying = {
                runBlocking { setProgress(workDataOf(PROGRESS_VERIFYING to true)) }
                runCatching { runBlocking { setForeground(foregroundInfo(spec.sizeBytes, spec.sizeBytes, verifying = true)) } }
            },
            isStopped = { isStopped },
        )

        modelManager.refreshFile()
        when (outcome) {
            DownloadOutcome.Done -> Result.success()
            DownloadOutcome.Stopped -> Result.retry()
            is DownloadOutcome.Failed -> {
                Timber.tag(TAG).w("Model download failed: %s (%s)", outcome.reason, outcome.detail)
                if (outcome.retryable && runAttemptCount < MAX_ATTEMPTS) {
                    Result.retry()
                } else {
                    Result.failure(workDataOf(OUTPUT_FAILURE to outcome.reason.name))
                }
            }
        }
    }

    private fun foregroundInfo(bytes: Long, total: Long, verifying: Boolean): ForegroundInfo {
        val context = applicationContext
        ensureChannel(context)
        val title = context.getString(R.string.ai_model_download_notification_title)
        val text = if (verifying) {
            context.getString(R.string.ai_model_download_notification_verifying)
        } else {
            context.getString(
                R.string.ai_model_download_progress,
                Formatter.formatShortFileSize(context, bytes),
                Formatter.formatShortFileSize(context, total),
            )
        }
        val percent = if (total > 0L) (bytes * 100 / total).toInt().coerceIn(0, 100) else 0
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.monochrome_player)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(100, percent, verifying)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ai_model_download_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val INPUT_ALLOW_METERED = "allow_metered"
        const val PROGRESS_BYTES = "bytes"
        const val PROGRESS_TOTAL = "total"
        const val PROGRESS_VERIFYING = "verifying"
        const val OUTPUT_FAILURE = "failure"
        private const val CHANNEL_ID = "ai_model_download"
        private const val NOTIFICATION_ID = 0x41_49_4D // "AIM"
        private const val MAX_ATTEMPTS = 8
        private const val TAG = "LocalModelDownload"
    }
}
