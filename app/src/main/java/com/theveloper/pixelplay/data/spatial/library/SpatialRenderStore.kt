package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudDigest
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.cloudstudio.CloudLibraryHost
import com.theveloper.pixelplay.data.spatial.SpatialLibraryEvent
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's own copies of Spatial audio renders (cloud-audio-spec §6.2 "Local store"):
 * `filesDir/cloud_audio/<videoId>/sp-<profile>.flac` and its manifest `sp-<profile>.json`, nothing else. The file plus a
 * valid manifest is the index: there is no database row, and a render counts only for the current library version.
 *
 * - A render comes in only through [installRender]: its manifest must be the one asked for (§3.1) and the file's size
 *   and SHA-256 must match it. The FLAC is moved in first and the manifest last, so a manifest on disk always stands
 *   next to its complete file.
 * - Reading the index back at start checks each manifest and the file's size (hashing every render on every start
 *   would read gigabytes); the SHA-256 was checked when the render came in.
 * - The storage budget (`cloudAudio.storageMB`) removes the least-recently-played songs first, all of a song's renders
 *   together, and never the song played last nor the one just stored. Every removal is announced as
 *   [SpatialLibraryEvent.Removed], so a player on one of them goes back to the stream.
 * - [localRender] is on the player's load path: it answers from memory, or before the first index with one read of
 *   the song's manifest and a size check of its file. No network, no database.
 *
 * `cloud_audio/` is kept out of Android backups and device transfers (`backup_rules.xml`,
 * `data_extraction_rules.xml`).
 */
class SpatialRenderStore(
    private val root: File,
    /** `cloudAudio.storageMB` in bytes, read when renders arrive or the budget is enforced. */
    private val budgetBytes: suspend () -> Long,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
) : CloudLibraryHost {

    private class Entry(val render: SpatialRender, val manifestFile: File, val bytes: Long, @Volatile var lastPlayedMs: Long)

    private val lock = Mutex()
    private val entries = ConcurrentHashMap<SpatialRenderKey, Entry>()
    @Volatile private var indexed = false
    @Volatile private var indexStarted = false
    /** The render the player started last: never evicted (§3.6 of the map: "never evict the most recently played"). */
    @Volatile private var lastPlayedKey: SpatialRenderKey? = null
    /**
     * The song whose audio [removeAll] left on disk for the player: no render any more (no manifest, not in the index),
     * only the file(s) it may still be reading. Deleted once another song's render plays, or by the next start's index.
     */
    @Volatile private var leftoverVideoId: String? = null

    private val _renders = MutableStateFlow<Map<SpatialRenderKey, SpatialRender>>(emptyMap())
    /** Every verified render on the phone, as it changes (complete once [ensureIndexed] has run). */
    val renders: StateFlow<Map<SpatialRenderKey, SpatialRender>> = _renders.asStateFlow()

    private val _usage = MutableStateFlow(SpatialStoreUsage())
    /** "Songs ready: N · X GB on this phone" (§6.5). */
    val usage: StateFlow<SpatialStoreUsage> = _usage.asStateFlow()

    private val _events = MutableSharedFlow<SpatialLibraryEvent>(extraBufferCapacity = 64)
    /** Renders that arrived, and files that left (§6.3). Hot, no replay. */
    val events: SharedFlow<SpatialLibraryEvent> = _events.asSharedFlow()

    // ─── Reading ────────────────────────────────────────────────────────────────────────────

    /** Reads the store's index from disk once (and clears what an interrupted install left behind). */
    suspend fun ensureIndexed() {
        if (indexed) return
        withContext(Dispatchers.IO) { lock.withLock { indexLocked() } }
    }

    /**
     * The verified render of [profile] for [videoId], or null. From memory once indexed; before that, one read of the
     * song's manifest and a size check of its file (and the index is read in the background).
     */
    fun localRender(videoId: String, profile: SpatialProfile): SpatialRender? {
        if (!CloudKeys.isValidVideoId(videoId)) return null
        if (indexed) return entries[SpatialRenderKey(videoId, profile)]?.render?.takeIf { it.file.isFile }
        startIndexing()
        return readEntry(File(root, videoId), profile)?.render
    }

    override fun hasRender(videoId: String, profileId: String): Boolean {
        val profile = SpatialProfile.fromId(profileId) ?: return false
        return localRender(videoId, profile) != null
    }

    // ─── Writing ────────────────────────────────────────────────────────────────────────────

    /**
     * Takes a downloaded render (§6.2 steps 2 and 4): checks [manifest] against what was asked for and [staged]
     * against the manifest, then moves both in, enforces the budget and announces it. Null when stored (or already
     * there), else why it was refused. [staged] is gone afterwards either way.
     */
    override suspend fun installRender(videoId: String, profileId: String, staged: File, manifest: ByteArray): String? =
        withContext(Dispatchers.IO) {
            val refusal = try {
                install(videoId, profileId, staged, manifest)
            } catch (error: IOException) {
                error.message ?: "Couldn't store the render."
            } finally {
                staged.delete()
            }
            refusal
        }

    private suspend fun install(videoId: String, profileId: String, staged: File, manifestBytes: ByteArray): String? {
        val profile = SpatialProfile.fromId(profileId) ?: return "An unknown profile."
        if (!CloudKeys.isValidVideoId(videoId)) return "Not a YouTube video id."
        val manifest = SpatialManifests.decode(manifestBytes) ?: return "The render's manifest couldn't be read."
        SpatialManifests.refusal(manifest, videoId, profile)?.let { return it }
        if (!staged.isFile) return "The downloaded render is missing."
        val (sha, bytes) = CloudDigest.file(staged)
        if (!SpatialManifests.fileMatches(manifest, bytes, sha)) return "The downloaded render was damaged."
        val key = SpatialRenderKey(videoId, profile)
        var removed: List<String> = emptyList()
        val render = lock.withLock {
            indexLocked()
            val existing = entries[key]
            if (existing != null && existing.render.file.isFile) return null // another download got here first
            val directory = File(root, videoId).apply { mkdirs() }
            val audio = audioFile(directory, profile)
            val manifestFile = manifestFile(directory, profile)
            // The manifest goes away first and comes back last: it never stands next to a half-written file.
            manifestFile.delete()
            moveInto(staged, audio)
            writeAtomically(manifestFile, manifestBytes)
            val time = nowMs()
            manifestFile.setLastModified(time)
            val stored = SpatialManifests.render(manifest, profile, audio)
            entries[key] = Entry(stored, manifestFile, audio.length() + manifestFile.length(), lastPlayedMs = time)
            removed = enforceBudgetLocked(protect = setOf(key))
            publish()
            stored
        }
        for (videoGone in removed) _events.emit(SpatialLibraryEvent.Removed(videoGone))
        _events.emit(SpatialLibraryEvent.RenderReady(render))
        return null
    }

    /** The player started the render of [profile] for [videoId] (least-recently-played go first, §6.1). */
    fun markPlayed(videoId: String, profile: SpatialProfile) {
        val key = SpatialRenderKey(videoId, profile)
        lastPlayedKey = key
        val leftover = leftoverVideoId
        if (leftover != null && leftover != videoId) {
            // Another song's render plays: the player is done with what "Remove downloaded renders" left for it.
            leftoverVideoId = null
            scope.launch(Dispatchers.IO) { lock.withLock { deleteLeftoverLocked(leftover) } }
        }
        val entry = entries[key] ?: return
        val time = nowMs()
        entry.lastPlayedMs = time
        // The manifest's time is the played time that survives a restart (no database, §6.2).
        scope.launch(Dispatchers.IO) { entry.manifestFile.setLastModified(time) }
    }

    /**
     * §6.5 "Remove downloaded renders": every render of the store, never anything in the shared library. The song played
     * last may still be playing from its render: the main player re-opens the file it started on at every seek outside
     * its buffer (a missing file there is a playback error, or a skip). So that song's audio stays on disk, as no render
     * any more (its manifests go now, so neither this index nor the next start's counts it), until another song's render
     * plays or the app starts again. Like the budget's eviction, it goes by song, so it also covers the main player's
     * render after a mid-song swap marked another profile played.
     */
    suspend fun removeAll() {
        withContext(Dispatchers.IO) {
            lock.withLock {
                entries.clear()
                val playing = lastPlayedKey?.videoId?.takeIf { File(root, it).isDirectory }
                for (child in root.listFiles().orEmpty()) {
                    if (child.name != playing) child.deleteRecursively()
                }
                if (playing != null) {
                    val directory = File(root, playing)
                    for (file in directory.listFiles().orEmpty()) {
                        if (!file.name.endsWith(AUDIO_SUFFIX)) file.deleteRecursively() // manifests first: no render left
                    }
                    if (directory.list().isNullOrEmpty()) directory.delete()
                }
                if (root.list().isNullOrEmpty()) root.delete()
                leftoverVideoId = playing?.takeIf { File(root, it).isDirectory }
                indexed = true
                publish()
            }
        }
        _events.emit(SpatialLibraryEvent.Removed(null))
    }

    /** Deletes what [removeAll] kept of [videoId] for the player, but never a render stored for it again since. */
    private fun deleteLeftoverLocked(videoId: String) {
        val directory = File(root, videoId)
        for (profile in SpatialProfile.entries) {
            if (entries.containsKey(SpatialRenderKey(videoId, profile))) continue
            audioFile(directory, profile).delete()
            manifestFile(directory, profile).delete()
        }
        if (directory.list().isNullOrEmpty()) directory.delete()
    }

    /** Brings the store under its budget now (the budget was lowered). */
    suspend fun trimToBudget() {
        val removed = withContext(Dispatchers.IO) {
            lock.withLock {
                indexLocked()
                enforceBudgetLocked(protect = emptySet()).also { publish() }
            }
        }
        for (videoId in removed) _events.emit(SpatialLibraryEvent.Removed(videoId))
    }

    // ─── Inside the lock ────────────────────────────────────────────────────────────────────

    private fun startIndexing() {
        if (indexStarted) return
        indexStarted = true
        scope.launch { ensureIndexed() }
    }

    private fun indexLocked() {
        if (indexed) return
        indexStarted = true
        entries.clear()
        for (directory in root.listFiles().orEmpty()) {
            if (!directory.isDirectory || !CloudKeys.isValidVideoId(directory.name)) {
                directory.deleteRecursively()
                continue
            }
            for (file in directory.listFiles().orEmpty()) if (file.name.endsWith(TMP_SUFFIX)) file.delete()
            for (profile in SpatialProfile.entries) {
                val entry = readEntry(directory, profile)
                if (entry != null) {
                    entries[SpatialRenderKey(directory.name, profile)] = entry
                } else {
                    // What an interrupted install or a render of another library version left: not a render any more.
                    audioFile(directory, profile).delete()
                    manifestFile(directory, profile).delete()
                }
            }
            if (directory.list().isNullOrEmpty()) directory.delete()
        }
        indexed = true
        publish()
    }

    /**
     * Removes the least-recently-played songs (all their renders) until the store fits its budget. The songs of
     * [protect] and of the render played last stay. Returns the video ids removed.
     */
    private suspend fun enforceBudgetLocked(protect: Set<SpatialRenderKey>): List<String> {
        val budget = budgetBytes().coerceAtLeast(0)
        var total = entries.values.sumOf { it.bytes }
        if (total <= budget) return emptyList()
        val kept = (protect + listOfNotNull(lastPlayedKey)).mapTo(HashSet()) { it.videoId }
        val candidates = SpatialEviction.order(
            entries.map { (key, entry) -> SpatialEviction.Item(key.videoId, entry.bytes, entry.lastPlayedMs) },
            kept,
        )
        val removed = mutableListOf<String>()
        for (videoId in candidates) {
            if (total <= budget) break
            for (key in entries.keys.filter { it.videoId == videoId }) {
                total -= entries.remove(key)?.bytes ?: 0L
            }
            File(root, videoId).deleteRecursively()
            removed += videoId
        }
        return removed
    }

    private fun readEntry(directory: File, profile: SpatialProfile): Entry? {
        val manifestFile = manifestFile(directory, profile)
        val audio = audioFile(directory, profile)
        if (!manifestFile.isFile || !audio.isFile) return null
        if (manifestFile.length() > SpatialManifests.MAX_MANIFEST_BYTES) return null
        val bytes = try {
            manifestFile.readBytes()
        } catch (error: IOException) {
            return null
        }
        val manifest = SpatialManifests.decode(bytes) ?: return null
        if (SpatialManifests.refusal(manifest, directory.name, profile) != null) return null
        val size = audio.length()
        if (size != manifest.audio.bytes) return null
        return Entry(SpatialManifests.render(manifest, profile, audio), manifestFile, size + manifestFile.length(),
            manifestFile.lastModified())
    }

    private fun publish() {
        _renders.value = entries.mapValues { it.value.render }
        _usage.value = SpatialStoreUsage(
            songs = entries.keys.mapTo(HashSet()) { it.videoId }.size,
            bytes = entries.values.sumOf { it.bytes },
        )
    }

    private fun moveInto(staged: File, target: File) {
        val temporary = File(target.parentFile, target.name + TMP_SUFFIX)
        temporary.delete()
        if (!staged.renameTo(temporary)) {
            staged.inputStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output, 1 shl 16)
                    output.fd.sync()
                }
            }
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Couldn't store the render.")
        }
    }

    private fun writeAtomically(target: File, data: ByteArray) {
        val temporary = File(target.parentFile, target.name + TMP_SUFFIX)
        FileOutputStream(temporary).use { output ->
            output.write(data)
            output.fd.sync()
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            throw IOException("Couldn't store the render's manifest.")
        }
    }

    companion object {
        /** The store's folder name under `filesDir` (§6.2). */
        const val DIRECTORY = "cloud_audio"
        private const val TMP_SUFFIX = ".tmp"
        private const val AUDIO_SUFFIX = ".flac"

        fun audioFile(directory: File, profile: SpatialProfile): File = File(directory, "sp-${profile.id}$AUDIO_SUFFIX")
        fun manifestFile(directory: File, profile: SpatialProfile): File = File(directory, "sp-${profile.id}.json")
    }
}

/** What the local store holds: songs with at least one render, and bytes of audio and manifests (§6.5). */
data class SpatialStoreUsage(val songs: Int = 0, val bytes: Long = 0L)

/** The eviction order of the storage budget (pure, so it is tested). */
object SpatialEviction {
    /** One render: its song, its size and when it was last played (or stored). */
    data class Item(val videoId: String, val bytes: Long, val lastPlayedMs: Long)

    /**
     * The songs in the order they leave: least-recently-played first, a song's time being its most recent render's,
     * ties broken by the video id so the order is stable. The songs of [kept] never leave.
     */
    fun order(items: List<Item>, kept: Set<String>): List<String> =
        items.groupBy { it.videoId }
            .filterKeys { it !in kept }
            .map { (videoId, renders) -> videoId to renders.maxOf { it.lastPlayedMs } }
            .sortedWith(compareBy<Pair<String, Long>> { it.second }.thenBy { it.first })
            .map { it.first }
}
