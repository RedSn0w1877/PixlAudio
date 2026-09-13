package com.theveloper.pixelplay.data.worker

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

const val AUTO_STUDIO_WORK_TAG = "automatic_studio"
internal const val AUTO_LYRICS_TAG = "automatic_studio_lyrics"
internal const val AUTO_INSTRUMENTAL_TAG = "automatic_studio_instrumental"
internal const val AUTO_SONG_TAG_PREFIX = "automatic_studio_song_"
internal const val INPUT_AUTOMATIC_STUDIO = "automatic_studio"
internal const val OUTPUT_AUTOMATIC_DEFERRED = "automatic_studio_deferred"

/** Shared by the coordinator and workers, so a restarted background process defaults to idle. */
@Singleton
class AutomaticStudioEnvironment @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile private var appVisible = false
    @Volatile private var lyricsEnabled = false
    @Volatile private var instrumentalsEnabled = false

    internal fun setAppVisible(visible: Boolean) { appVisible = visible }
    internal fun setEnabled(lyrics: Boolean, instrumentals: Boolean) {
        lyricsEnabled = lyrics
        instrumentalsEnabled = instrumentals
    }

    internal fun blockedReason(kind: AutomaticStudioKind): String? {
        // WorkManager may recreate this process while PixelPlay is not visible. Eligibility is
        // therefore based on persisted feature flags and device conditions, never UI visibility.
        if (PlaybackActivityTracker.isPlaybackActive) return "Waiting until playback is idle"
        if (kind == AutomaticStudioKind.LYRICS && !lyricsEnabled) return "Automatic lyrics are off"
        if (kind == AutomaticStudioKind.INSTRUMENTAL && !instrumentalsEnabled) return "Automatic instrumentals are off"
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        val networkAvailable = if (kind == AutomaticStudioKind.INSTRUMENTAL) true else {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val capabilities = connectivity?.let { manager -> manager.activeNetwork?.let(manager::getNetworkCapabilities) }
            capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        return AutomaticStudioPolicy.blockedReason(AutomaticStudioPolicy.Conditions(
            appVisible, lyricsEnabled, instrumentalsEnabled,
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1,
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_MODERATE,
            context.filesDir.usableSpace,
            networkAvailable
        ), kind)
    }

    internal suspend fun runQuietly(kind: AutomaticStudioKind, block: suspend () -> ListenableWorker.Result): ListenableWorker.Result {
        return try {
            withTimeoutOrNull(AutomaticStudioPolicy.MAX_WORK_DURATION_MS) {
                coroutineScope {
                    blockedReason(kind)?.let { throw Deferred(it) }
                    val task = async { block() }
                    val watchdog = launch {
                        while (isActive) {
                            delay(3_000)
                            blockedReason(kind)?.let { throw Deferred(it) }
                        }
                    }
                    try {
                        val result = task.await()
                        // A failed request can finish before the three-second watchdog
                        // notices lost connectivity. Do not turn that into a catalog-miss
                        // cooldown; keep a completed, persisted lyric update successful.
                        val lyricsSaved = result is ListenableWorker.Result.Success &&
                            result.outputData.getBoolean(TaisStudioWorker.OUTPUT_LYRICS_UPDATED, false)
                        if (kind == AutomaticStudioKind.LYRICS && !lyricsSaved) {
                            blockedReason(kind)?.let { throw Deferred(it) }
                        }
                        result
                    } finally { watchdog.cancel() }
                }
            } ?: deferredResult("Taking a break; automatic processing reached its time budget")
        } catch (deferred: Deferred) {
            deferredResult(deferred.message.orEmpty())
        }
    }

    private class Deferred(message: String) : Exception(message)

    internal companion object {
        fun deferredResult(reason: String): ListenableWorker.Result = ListenableWorker.Result.success(workDataOf(
            OUTPUT_AUTOMATIC_DEFERRED to true,
            TaisStudioWorker.OUTPUT_DETAIL to reason
        ))

        /** No remote URL/proxy and no implicit download are permitted for unattended DSP. */
        suspend fun localAudio(song: Song, cache: AudioCacheManager): String? {
            song.spotifyId?.let { return cache.getPlayableFile(it)?.absolutePath }
            val uri = Uri.parse(song.contentUriString)
            return when (uri.scheme?.lowercase()) {
                // A document provider may download its content on read. Only MediaStore
                // audio is known to be local without contacting another app or network.
                "content" -> song.contentUriString.takeIf { uri.authority == "media" }
                "file" -> uri.path?.takeIf { File(it).isFile }
                null -> song.contentUriString.takeIf { it.isNotBlank() && File(it).isFile }
                    ?: song.path.takeIf { it.isNotBlank() && File(it).isFile }
                else -> null
            }
        }
    }
}
