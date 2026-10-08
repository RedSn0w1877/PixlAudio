package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.model.LyricsDoc
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope

// The seams of Cloud Studio's orchestrator ([CloudStudioEngine], design §7.1 and §7.6 "tests with fakes"): the app
// around it (library, lyrics store, instrumental store, permanent downloads), the upload preparer, the job store,
// settings and keys, and WorkManager. The live implementations are in this package (`Live*`, `CloudStudioModule`);
// unit tests use fakes, so nothing here touches Android, the network or the disk unless a test asks it to.

/** A library song as Cloud Studio sees it (the live host maps the app's `Song` to this). */
data class CloudSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    /** Plays from Spotify/YouTube: downloaded permanently before it is prepared (owner decision). */
    val isStreamed: Boolean,
    /** A local file or content URI, or a stream this phone can download. */
    val hasAudioSource: Boolean,
)

/** What the library knows about a song's lyrics, for selection and for the request's `lyrics` block. */
data class CloudLyricsFacts(
    val state: CloudLyricsState,
    val lines: List<CloudLyricsInputLine>? = null,
    val hasLineTimes: Boolean = false,
    /** The lyrics' own idea of the song's length (a catalog's duration), when known. */
    val referenceDurationMs: Long? = null,
    val language: String? = null,
) {
    companion object {
        val NONE = CloudLyricsFacts(CloudLyricsState.NONE)
    }
}

/** What became of a lyrics import. */
enum class CloudLyricsSaveOutcome {
    SAVED,
    /** The stored lyrics are as good or better (catalog word timing arrived meanwhile). */
    KEPT_BETTER,
    /** The person synced this song themselves and didn't ask to replace it. */
    KEPT_USER_SYNCED,
    UNUSABLE,
}

/** The app around the orchestrator: songs, their audio, the lyrics store and the instrumental store. */
interface CloudStudioHost {
    suspend fun song(id: String): CloudSong?
    /** Every song in the library (the queue's "Add" filters). */
    suspend fun librarySongs(): List<CloudSong>
    /**
     * A decodable source for the song (a path or `content://` URI). A streamed song is downloaded permanently first.
     * Throws with a sentence the queue can show when there is none.
     */
    suspend fun audioSource(song: CloudSong): String
    /** The YouTube video a streamed song plays (a Spotify song's match), null for local songs. Re-checked on import. */
    suspend fun streamIdentity(song: CloudSong): String?
    suspend fun lyricsFacts(song: CloudSong): CloudLyricsFacts
    suspend fun hasInstrumental(songId: String): Boolean
    /** Moves a verified result into the instrumental store atomically (and drops the other cloud variant). */
    suspend fun installInstrumental(staged: File, songId: String, flac: Boolean)
    suspend fun saveLyrics(doc: LyricsDoc, song: CloudSong, replaceUserSynced: Boolean): CloudLyricsSaveOutcome
    fun instrumentalImported(songId: String)
    fun lyricsImported(songId: String)
}

/** A prepared upload: the file and the facts the import checks later. */
data class CloudPreparedAudio(
    val file: File,
    val ext: String,
    val bytes: Long,
    /** Lower-case hex. */
    val sha256: String,
    val durationMs: Long,
    /** Decoded sample frames per channel, as written. */
    val frames: Long,
    val sampleRate: Int,
)

/** Preparing failed; [isDecodeFailure] means trying again won't help. */
class CloudPrepareException(message: String, val isDecodeFailure: Boolean = false) : Exception(message)

/** Prepares a song's audio for upload ([LiveCloudAudioPreparer]). */
interface CloudAudioPreparing {
    /**
     * Decodes [source] and writes the upload. [forceDecode] is the iOS seam for streamed songs; on Android every
     * upload is FLAC of the decoded samples anyway, so it only documents intent (and lets tests check it).
     */
    suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio
    /** The prepared file of a job, if it is still on disk (an upload restarted after the app was stopped). */
    fun uploadFile(jobKey: String, ext: String): File?
    fun removeUpload(jobKey: String)
}

/** The job list on disk ([CloudJobStore]). */
interface CloudJobPersistence {
    suspend fun load(): List<CloudJobRecord>
    suspend fun save(jobs: List<CloudJobRecord>)
}

/** The non-secret settings, as one snapshot (read where they are needed, never cached across passes). */
data class CloudSettingsSnapshot(
    /** "Send songs to my RunPod account": nothing leaves the phone while this is off. */
    val enabled: Boolean = false,
    val endpointId: String = "",
    /** As typed: `https://<account-id>.r2.cloudflarestorage.com` or the bare account ID. */
    val r2Endpoint: String = "",
    val bucket: String = CloudConfig.DEFAULT_BUCKET,
    val wantsInstrumental: Boolean = true,
    val wantsLyrics: Boolean = true,
    /** "Write lyrics when none are found (AI transcription)". */
    val transcribeWhenMissing: Boolean = true,
    val quality: CloudSeparationQuality = CloudSeparationQuality.STANDARD,
    /** "Use mobile data" (off: uploads and downloads wait for an unmetered network). */
    val useCellular: Boolean = false,
    val pricePerSecondMicroUsd: Long = CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD,
    val monthlyCapMicroUsd: Long = CloudCost.DEFAULT_MONTHLY_CAP_MICRO_USD,
    /** The endpoint's own limits from its last selftest (null until one ran). */
    val workerCaps: CloudWorkerCaps? = null,
) {
    val selectionOptions: CloudSelectionOptions
        get() = CloudSelectionOptions(wantsInstrumental, wantsLyrics, transcribeWhenMissing)
}

/** The three keys. `toString` never prints them. */
class CloudSecrets(val runpodKey: String = "", val accessKeyId: String = "", val secretAccessKey: String = "") {
    val isEmpty: Boolean get() = runpodKey.isBlank() && accessKeyId.isBlank() && secretAccessKey.isBlank()

    override fun equals(other: Any?): Boolean = other is CloudSecrets && other.runpodKey == runpodKey &&
        other.accessKeyId == accessKeyId && other.secretAccessKey == secretAccessKey

    override fun hashCode(): Int = listOf(runpodKey, accessKeyId, secretAccessKey).hashCode()

    override fun toString(): String = "CloudSecrets(…)"

    companion object {
        val EMPTY = CloudSecrets()
    }
}

/** The keys as read from secure storage, and what went wrong reading them. */
data class CloudSecretsState(
    val secrets: CloudSecrets,
    /** Keys were saved before but secure storage has none now (cleared data, a restored backup, a new device). */
    val keysMissing: Boolean = false,
    /** The Keystore-backed store couldn't be opened, so keys can't be read or saved (never a plaintext fallback). */
    val storageUnavailable: Boolean = false,
)

/** Settings and keys ([CloudStudioSettings]). */
interface CloudStudioSettingsSource {
    fun snapshot(): CloudSettingsSnapshot
    /** Reads the keys (off the main thread). */
    suspend fun secrets(): CloudSecretsState
    /** Keeps the endpoint's limits from a selftest (forgotten when the Endpoint ID changes). */
    fun saveWorkerCaps(caps: CloudWorkerCaps?)
}

/** WorkManager ([CloudStudioScheduler]): the background passes that make "process later" work. */
interface CloudWorkScheduler {
    /** A full pass soon, on any network (at most one waits behind a running one). */
    fun requestPass()
    /** A full pass once an unmetered network is there (transfers waiting for Wi-Fi). */
    fun requestUnmeteredPass()
    /** The periodic background watch (every 15 minutes) while jobs are in flight. */
    fun ensureWatch()
    fun cancelWatch()
}

/** Everything [CloudStudioEngine] talks to. */
class CloudStudioDependencies(
    val settings: CloudStudioSettingsSource,
    val store: CloudJobPersistence,
    val host: CloudStudioHost,
    val preparer: CloudAudioPreparing,
    val transfers: CloudTransfers,
    val makeRunPod: (CloudConfigInput) -> RunPodJobsApi?,
    val makeObjects: (CloudConfigInput) -> CloudObjectStoring?,
    val scheduler: CloudWorkScheduler,
    /** The foreground poll loop runs here. */
    val scope: CoroutineScope,
    /** `client.build` ("0.7.6-beta2 (11)"). */
    val build: String,
    /** Downloads land here before they are checked and moved. */
    val stagingDir: File,
    /** The active network is unmetered (Wi-Fi, Ethernet). */
    val isUnmetered: () -> Boolean,
    val nowMs: () -> Long = System::currentTimeMillis,
    /** Start of the month containing a time (the phone's own calendar in the app). */
    val monthStartMs: (Long) -> Long = { CloudBudget.monthStartMs(it, java.time.ZoneId.systemDefault()) },
    val newJobKey: () -> String = { java.util.UUID.randomUUID().toString().lowercase() },
)

/** SHA-256 and size of files and buffers (the import checks, design §7.5 step 1). */
object CloudDigest {
    fun sha256Hex(data: ByteArray): String = S3Signer.hex(MessageDigest.getInstance("SHA-256").digest(data))

    /** (lower-case hex SHA-256, byte count), streaming the file. */
    fun file(file: File): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
        }
        return S3Signer.hex(digest.digest()) to total
    }
}
