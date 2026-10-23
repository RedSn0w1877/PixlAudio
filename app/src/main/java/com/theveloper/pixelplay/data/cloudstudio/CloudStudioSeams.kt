package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope

// The seams of Cloud Studio's orchestrator ([CloudStudioEngine], design §7.1 and §7.6 "tests with fakes"): the app
// around it (library, instrumental store, permanent downloads), the upload preparer, the job store,
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

/**
 * The app around the orchestrator: songs, their audio and the instrumental store. (Lyrics are no longer the cloud's,
 * 2026-10-23: BiniLyrics and the other catalogs bring word timing, so nothing here reads or writes the lyrics store.)
 */
interface CloudStudioHost {
    suspend fun song(id: String): CloudSong?
    /** Every song in the library (the queue's "Add" filters). */
    suspend fun librarySongs(): List<CloudSong>
    /**
     * A decodable source for the song (a path or `content://` URI). A streamed song is downloaded permanently first.
     * Throws with a sentence the queue can show when there is none.
     */
    suspend fun audioSource(song: CloudSong): String
    /**
     * The song's audio only if it is already on this phone, with no download of any kind (null otherwise): what an
     * automatic job prepares from, so Smart auto-prepare never starts a fresh YouTube download. The default says no.
     */
    suspend fun cachedAudioSource(song: CloudSong): String? = null
    /** The YouTube video a streamed song plays (a Spotify song's match), null for local songs. Re-checked on import. */
    suspend fun streamIdentity(song: CloudSong): String?
    suspend fun hasInstrumental(songId: String): Boolean
    /** Moves a verified result into the instrumental store atomically (and drops the other cloud variant). */
    suspend fun installInstrumental(staged: File, songId: String, flac: Boolean)
    fun instrumentalImported(songId: String)

    /**
     * Keeps a Sing's verified preview clip, which starts at [clipStartMs] of the song, as a transient file (the caches,
     * never the instrumental store, so nothing counts it as the song's instrumental). The default drops it.
     */
    suspend fun installClip(staged: File, songId: String, clipStartMs: Long, flac: Boolean) {
        staged.delete()
    }

    /** The song's full instrumental is in: its preview clips go (one still playing goes once the player lets go). */
    suspend fun discardClips(songId: String) {}
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
    /** The source was HE-AAC, below 32 kHz, or muxed video (a low-bitrate stream): shown on the queue row. */
    val lowQualitySource: Boolean = false,
)

/** Preparing failed; [isDecodeFailure] means trying again won't help. */
class CloudPrepareException(message: String, val isDecodeFailure: Boolean = false) : Exception(message)

/** Prepares a song's audio for upload ([LiveCloudAudioPreparer]). */
interface CloudAudioPreparing {
    /**
     * Writes the upload of [source]: an AAC-LC MP4 as it is, everything else decoded to FLAC. [forceDecode] decodes
     * even an AAC-LC source (a streamed song's download, a FLAC redo), so the worker sees the samples this phone plays.
     */
    suspend fun prepare(source: String, jobKey: String, forceDecode: Boolean): CloudPreparedAudio

    /**
     * Writes a Sing preview clip's upload: [source]'s decoded samples from [startMs] to [endMs] (song time, clamped to
     * the song), as 16-bit stereo FLAC. The samples are counted the way [prepare] counts a forced decode, so the clip is
     * exactly that stretch of the song's decode; the frames returned are the clip's own (its import check).
     */
    suspend fun prepareClip(source: String, jobKey: String, startMs: Long, endMs: Long): CloudPreparedAudio =
        throw CloudPrepareException("Preview clips can't be prepared here.", isDecodeFailure = true)

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
    val quality: CloudSeparationQuality = CloudSeparationQuality.STANDARD,
    /** "Use mobile data" (off: uploads and downloads wait for an unmetered network). */
    val useCellular: Boolean = false,
    val pricePerSecondMicroUsd: Long = CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD,
    val monthlyCapMicroUsd: Long = CloudCost.DEFAULT_MONTHLY_CAP_MICRO_USD,
    /** The endpoint's own limits from its last selftest (null until one ran). */
    val workerCaps: CloudWorkerCaps? = null,
    /** "Use my own keys": the fields instead of PixlAudio's built-in keys (only matters while [builtInAvailable]). */
    val useOwnKeys: Boolean = false,
    /** This build carries built-in keys that open (or haven't been opened yet). Set by [CloudStudioSettings], never saved. */
    val builtInAvailable: Boolean = false,
    /**
     * "Prepare songs ahead in the cloud" (Settings › Sing): the one way anything is sent without a tap. Off until the
     * person turns it on; it only ever runs on Wi-Fi, on a charger, inside [autoPrepareMonthlyBudgetMicroUsd].
     */
    val autoPrepareEnabled: Boolean = false,
    /** Its own share of the month, inside the shared cap (the slider: $0.00–$3.00 in $0.25 steps). */
    val autoPrepareMonthlyBudgetMicroUsd: Long = CloudAutoPrepareDefaults.BUDGET_MICRO_USD,
    /** Most songs it starts in a day. */
    val autoPrepareDailyCap: Int = CloudAutoPrepareDefaults.DAILY_CAP,
) {
    /** Whose keys jobs go out with now. */
    val keySource: CloudKeySource get() = CloudKeyChoice.source(useOwnKeys, builtInAvailable)

    val usesBuiltInKeys: Boolean get() = keySource == CloudKeySource.BUILT_IN

    /** How many jobs may run at RunPod at once with these keys ([CloudKeyChoice.parallelWorkers]). */
    val parallelWorkers: Int get() = CloudKeyChoice.parallelWorkers(keySource)

    /** The cap the money guards use: the field's, but never above $3 a month with the built-in keys. */
    val effectiveMonthlyCapMicroUsd: Long get() = CloudKeyChoice.effectiveMonthlyCap(monthlyCapMicroUsd, keySource)

    /** The GPU price the estimates and the cap use: the field's, but never below the endpoint's own with the built-in keys. */
    val effectivePricePerSecondMicroUsd: Long get() = CloudKeyChoice.effectivePricePerSecond(pricePerSecondMicroUsd, keySource)

    /** The automatic share of the month: what the slider says, but never more than the shared cap. */
    val effectiveAutoBudgetMicroUsd: Long
        get() = minOf(autoPrepareMonthlyBudgetMicroUsd.coerceAtLeast(0), effectiveMonthlyCapMicroUsd)
}

/** The defaults of Smart auto-prepare, here so the snapshot and the pure ranker share one set. */
object CloudAutoPrepareDefaults {
    const val BUDGET_MICRO_USD: Long = 1_000_000
    const val MAX_BUDGET_MICRO_USD: Long = 3_000_000
    const val DAILY_CAP = 12
    const val MIN_DAILY_CAP = 1
    const val MAX_DAILY_CAP = 30

    /** The slider's range ($0.00–$3.00): nothing else may store a value outside it, whoever calls the settings. */
    fun clampBudget(microUsd: Long): Long = microUsd.coerceIn(0, MAX_BUDGET_MICRO_USD)

    /** The stepper's range (1–30 songs a day). */
    fun clampDailyCap(songs: Int): Int = songs.coerceIn(MIN_DAILY_CAP, MAX_DAILY_CAP)
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
    /**
     * What jobs taken off the list spent in the month starting at [monthStartMs] (0 for any other month), so Remove
     * and Clear finished don't free room under the monthly cap.
     */
    fun removedSpendMicroUsd(monthStartMs: Long): Long = 0L
    fun addRemovedSpend(microUsd: Long, monthStartMs: Long) {}

    // The automatic jobs' ledger. It lives here, apart from the job list, so removing finished jobs ("Clear finished")
    // can't reset the daily count or the month's automatic spend.

    /** What automatic jobs taken off the list spent in the month starting at [monthStartMs] (0 for any other month). */
    fun removedAutoSpendMicroUsd(monthStartMs: Long): Long = 0L
    fun addRemovedAutoSpend(microUsd: Long, monthStartMs: Long) {}
    /** Automatic jobs started in the day starting at [dayStartMs] (0 for any other day). */
    fun autoDayCount(dayStartMs: Long): Int = 0
    fun addAutoDayCount(count: Int, dayStartMs: Long) {}
}

/** WorkManager ([CloudStudioScheduler]): the background passes that make "process later" work. */
interface CloudWorkScheduler {
    /** A full pass soon, on any network (at most one waits behind a running one). */
    fun requestPass()
    /**
     * A full pass right now for a priority job (a Sing): expedited work where the system allows it, so the song's
     * prepare and upload don't wait for a batch window. The default is a plain [requestPass].
     */
    fun requestUrgentPass() = requestPass()
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
    /** Start of the day containing a time (the daily cap on automatic jobs counts the phone's own days). */
    val dayStartMs: (Long) -> Long = { CloudBudget.dayStartMs(it, java.time.ZoneId.systemDefault()) },
    val newJobKey: () -> String = { java.util.UUID.randomUUID().toString().lowercase() },
    /** The app's own keys, used while the person hasn't chosen their own ([CloudKeyChoice]). */
    val builtIn: CloudBuiltInConfig = NoBuiltInCloudConfig,
    /**
     * Conditions for an automatic job's own work (preparing and uploading): on a charger, battery not low, on an
     * unmetered network. While false the phone-step picker leaves automatic jobs alone, so a pass never wakes the
     * phone on battery for them. Everything else (the person's jobs, a Sing) is unaffected.
     */
    val autoWorkAllowed: () -> Boolean = { true },
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
