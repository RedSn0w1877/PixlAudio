package com.theveloper.pixelplay.data.tais

import android.content.Context
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which songs already have a rendered instrumental sitting in `tais_stems`.
 *
 * There's no `is_instrumentalized` column anywhere — [StemSeparatorWorker]/[BsRoformerRenderWorker]
 * just drop `<songId>_instrumental.wav` / `<songId>_hq_roformer_inst.wav` into [stemsDirectory], and
 * [com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel.bestAvailableInstrumentalPath]
 * checks for those files directly. Adding a real DB column means a schema bump + migration on a
 * database that's intentionally stayed at version 1 since the upstream migration chain was
 * dropped (see CLAUDE.md) — not something to take on just for a library filter. This mirrors that
 * same file-existence check instead, cached in memory and refreshed whenever a render finishes.
 *
 * Storage lives under [Context.getFilesDir], not [Context.getCacheDir]. It used to be cacheDir,
 * which is exactly why processed songs kept "losing" their instrumental for no visible reason —
 * Android is free to purge cacheDir under storage pressure (and a manual Settings > Storage >
 * "Clear Cache" wipes it outright), and a rendered `.wav` stem is large enough to be a prime
 * eviction target. filesDir only goes away on uninstall or an explicit "Clear Data".
 */
@Singleton
class TaisInstrumentalIndex @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _instrumentalizedSongIds = MutableStateFlow<Set<String>>(emptySet())
    val instrumentalizedSongIds: StateFlow<Set<String>> = _instrumentalizedSongIds.asStateFlow()
    private val refreshLock = Mutex()

    /** Re-scans [stemsDirectory]. Call after a render completes, at Library-tab-open time, and once at startup. */
    suspend fun refresh() = refreshLock.withLock {
        _instrumentalizedSongIds.value = withContext(Dispatchers.IO) {
            migrateLegacyCacheDirIfNeeded()
            val stemsDir = stemsDirectory(context)
            // Failed migrations remain playable and visible while a later refresh retries.
            val files = stemsDir.listFiles().orEmpty().toList() +
                File(context.cacheDir, "tais_stems").listFiles().orEmpty().toList()
            listOf(stemsDir, File(context.cacheDir, "tais_stems")).forEach { directory ->
                aliasIndexes[directory.absolutePath] = buildAliasIndex(directory)
            }
            buildSet {
                files.forEach { file ->
                    val songId = stemSongId(file) ?: return@forEach
                    if (!isCompleteStem(file)) return@forEach
                    add(songId)
                    add(canonicalSongId(songId))
                }
            }
        }
    }

    /**
     * One-time move of any stems that were rendered before this file moved from cacheDir to
     * filesDir, so users who already processed songs don't lose them on the next app update.
     * Whatever cacheDir eviction hasn't already claimed gets copied over; the legacy directory
     * is then removed so this only does real work once.
     */
    private fun migrateLegacyCacheDirIfNeeded() {
        val legacyDir = File(context.cacheDir, "tais_stems")
        val legacyFiles = legacyDir.listFiles() ?: return
        val targetDir = stemsDirectory(context).apply { mkdirs() }
        legacyFiles.forEach { file ->
            val isStem = file.name.endsWith(INSTRUMENTAL_SUFFIX) || file.name.endsWith(ROFORMER_SUFFIX)
            if (!isStem) return@forEach
            val dest = File(targetDir, file.name)
            if (!isCompleteStem(file)) return@forEach
            if (!isCompleteStem(dest)) {
                runCatching { RetainedAudioFiles.copyAtomically(file, dest) }
                    .onFailure { Timber.tag(TAG).w(it, "Failed migrating legacy stem %s", file.name) }
            }
            // Never delete the surviving source after a failed copy (including low storage).
            if (isCompleteStem(dest)) file.delete()
        }
        // Only remove an empty directory; other stem outputs and failed migrations stay intact.
        if (legacyDir.listFiles()?.isEmpty() == true) legacyDir.delete()
    }

    companion object {
        private const val TAG = "TaisInstrumentalIndex"
        private const val INSTRUMENTAL_SUFFIX = "_instrumental.wav"
        private const val ROFORMER_SUFFIX = "_hq_roformer_inst.wav"
        private const val SPOTIFY_ALIAS_PREFIX = "spotify_"
        private val aliasIndexes = ConcurrentHashMap<String, Map<String, Set<String>>>()

        /** Single source of truth for where rendered instrumental stems live — see class doc for why filesDir. */
        fun stemsDirectory(context: Context): File = File(context.filesDir, "tais_stems")

        fun bestAvailableFile(context: Context, songId: String): File? {
            val directories = listOf(stemsDirectory(context), File(context.cacheDir, "tais_stems"))
            val canonicalId = canonicalSongId(songId)
            val compatibleIds = linkedSetOf(canonicalId, songId)
            directories.forEach { directory ->
                val aliases = aliasIndexes.getOrPut(directory.absolutePath) { buildAliasIndex(directory) }
                compatibleIds.addAll(aliases[canonicalId].orEmpty())
            }
            return sequenceOf(ROFORMER_SUFFIX, INSTRUMENTAL_SUFFIX)
                .flatMap { suffix -> directories.asSequence().flatMap { directory ->
                    compatibleIds.asSequence().map { File(directory, "$it$suffix") }
                } }
                .firstOrNull(::isCompleteStem)
        }

        /** Saved streaming aliases and unified Room rows identify the same source track. */
        fun canonicalSongId(songId: String): String =
            songId.takeIf { it.startsWith(SPOTIFY_ALIAS_PREFIX) }
                ?.removePrefix(SPOTIFY_ALIAS_PREFIX)?.takeIf(String::isNotBlank)
                ?.let { SpotifyRepository.unifiedSongId(it).toString() } ?: songId

        private fun stemSongId(file: File): String? = when {
            file.name.endsWith(INSTRUMENTAL_SUFFIX) -> file.name.removeSuffix(INSTRUMENTAL_SUFFIX)
            file.name.endsWith(ROFORMER_SUFFIX) -> file.name.removeSuffix(ROFORMER_SUFFIX)
            else -> null
        }

        // Only filenames are scanned, once per directory/startup or render refresh. This avoids
        // repeatedly scanning the whole stem library when a numeric ID needs a legacy alias.
        private fun buildAliasIndex(directory: File): Map<String, Set<String>> =
            directory.listFiles().orEmpty().asSequence().mapNotNull(::stemSongId)
                .filter { it.startsWith(SPOTIFY_ALIAS_PREFIX) }
                .groupBy(::canonicalSongId).mapValues { (_, aliases) -> aliases.toSet() }

        /** Empty/partial native WAV writes must never hide a previous complete render. */
        fun isCompleteStem(file: File): Boolean {
            if (!file.isFile || file.length() <= 44L) return false
            return runCatching {
                file.inputStream().use { input ->
                    val header = ByteArray(12)
                    if (input.read(header) != header.size) return@use false
                    val riff = String(header, 0, 4, Charsets.US_ASCII)
                    if (riff != "RIFF") return@use true // Hosted backends may return encoded audio.
                    val declaredBytes = (4..7).fold(0L) { length, index ->
                        length or ((header[index].toLong() and 0xffL) shl ((index - 4) * 8))
                    }
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE" && declaredBytes + 8L <= file.length()
                }
            }.getOrDefault(false)
        }
    }
}
