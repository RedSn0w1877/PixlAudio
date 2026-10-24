package com.theveloper.pixelplay.data.history

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.ImportedTasteSnapshot
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyAuthManager
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import com.theveloper.pixelplay.data.spotify.SpotifyResult
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.auth.YouTubeAuthManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

private val Context.listeningHistoryPrefs by preferencesDataStore("listening_history")

/** What the Settings rows and the Listening history screen show. */
data class ListeningHistoryStatus(
    val totalPlays: Int = 0,
    val spotifyPlays: Int = 0,
    val youTubePlays: Int = 0,
    val artists: Int = 0,
    val hasSpotifyExport: Boolean = false,
    val lastSyncedMs: Long = 0L,
    /** The switch value (default on); the sync also needs the account to be signed in. */
    val spotifyEnabled: Boolean = true,
    val youTubeEnabled: Boolean = true,
    val spotifySignedIn: Boolean = false,
    val youTubeSignedIn: Boolean = false,
    /** Spotify was linked before the history scopes existed (or refused them): reconnect once. */
    val spotifyNeedsReconnect: Boolean = false,
    val syncing: Boolean = false,
    val homeCardDismissed: Boolean = true,
    val importProgress: HistoryImportState = HistoryImportState.Idle,
)

sealed interface HistoryImportState {
    data object Idle : HistoryImportState
    data class Running(val filesRead: Int, val playsRead: Int, val currentFile: String?) : HistoryImportState
    data class Done(val added: Int, val duplicates: Int, val artists: Int) : HistoryImportState
    data class Failed(val message: String) : HistoryImportState
}

/**
 * Deep listening-history import: automatic (Spotify Web API first, YouTube Music's history page when signed
 * in) and from the data-export files people download from Spotify and Google.
 *
 * Plays are stored in their own database ([ListeningHistoryDatabase]) — everything stays on the phone — and
 * folded into the learner's inputs ([MusicTasteRepository.saveImportedTaste]) with the source weights and
 * time decay of [ListeningHistoryWeights]. The insights API ([ListeningInsights]) reads both this history and
 * the app's own plays.
 */
@Singleton
class ListeningHistoryRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: ListeningHistoryDao,
    private val tasteRepository: MusicTasteRepository,
    private val musicRepository: MusicRepository,
    private val spotifyDao: SpotifyDao,
    private val playbackStats: PlaybackStatsRepository,
    private val spotifyAuth: SpotifyAuthManager,
    private val spotifyRepository: dagger.Lazy<SpotifyRepository>,
    private val innerTube: dagger.Lazy<InnerTubeClient>,
    private val youTubeAuth: YouTubeAuthManager,
) : ListeningInsights {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefsStore = context.listeningHistoryPrefs
    private val prefs = prefsStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    private val syncMutex = Mutex()
    private val rebuildMutex = Mutex()

    private data class Counts(val total: Int = 0, val spotify: Int = 0, val youTube: Int = 0, val artists: Int = 0, val export: Boolean = false)

    private val counts = MutableStateFlow(Counts())
    private val syncing = MutableStateFlow(false)
    private val spotifyScopeTrouble = MutableStateFlow(false)
    private val importState = MutableStateFlow<HistoryImportState>(HistoryImportState.Idle)
    private var importJob: Job? = null

    private data class PrefValues(
        val spotifyEnabled: Boolean,
        val youTubeEnabled: Boolean,
        val lastSyncedMs: Long,
        val homeCardDismissed: Boolean,
    )

    private val prefValues = prefs.map { p ->
        PrefValues(
            spotifyEnabled = p[KEY_SPOTIFY_ENABLED] ?: true,
            youTubeEnabled = p[KEY_YOUTUBE_ENABLED] ?: true,
            lastSyncedMs = p[KEY_LAST_SYNC] ?: 0L,
            homeCardDismissed = p[KEY_HOME_CARD_DISMISSED] ?: false,
        )
    }

    // Built on first collection, on IO: both sessions live in EncryptedSharedPreferences, whose first read is
    // costly and must not happen while this singleton is constructed (it can be built on the main thread).
    private val accounts = flow {
        emitAll(
            combine(
                spotifyAuth.isLoggedIn,
                spotifyAuth.grantedScopes,
                spotifyScopeTrouble,
                youTubeAuth.isSignedIn,
            ) { spotifyIn, _, trouble, youTubeIn ->
                val missing = spotifyAuth.missingScopes(SpotifyAuthManager.HISTORY_SCOPES)
                Triple(spotifyIn, spotifyIn && (trouble || !missing.isNullOrEmpty()), youTubeIn && youTubeAuth.cookie != null)
            }
        )
    }.flowOn(Dispatchers.IO)

    val status: StateFlow<ListeningHistoryStatus> = combine(
        prefValues, counts, accounts, syncing, importState
    ) { p, c, account, isSyncing, importing ->
        ListeningHistoryStatus(
            totalPlays = c.total,
            spotifyPlays = c.spotify,
            youTubePlays = c.youTube,
            artists = c.artists,
            hasSpotifyExport = c.export,
            lastSyncedMs = p.lastSyncedMs,
            spotifyEnabled = p.spotifyEnabled,
            youTubeEnabled = p.youTubeEnabled,
            spotifySignedIn = account.first,
            spotifyNeedsReconnect = account.second,
            youTubeSignedIn = account.third,
            syncing = isSyncing,
            homeCardDismissed = p.homeCardDismissed,
            importProgress = importing,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), ListeningHistoryStatus())

    init {
        scope.launch { refreshCounts() }
    }

    // ─── Settings ──────────────────────────────────────────────────────

    suspend fun setSpotifyEnabled(enabled: Boolean) {
        prefsStore.edit { it[KEY_SPOTIFY_ENABLED] = enabled }
        if (enabled) scope.launch { sync(force = true) }
    }

    suspend fun setYouTubeEnabled(enabled: Boolean) {
        prefsStore.edit { it[KEY_YOUTUBE_ENABLED] = enabled }
        if (enabled) scope.launch { sync(force = true) }
    }

    suspend fun dismissHomeCard() {
        prefsStore.edit { it[KEY_HOME_CARD_DISMISSED] = true }
    }

    fun clearImportResult() {
        if (importState.value !is HistoryImportState.Running) importState.value = HistoryImportState.Idle
    }

    /** Deletes every imported play and Spotify seed, and takes their contribution out of learning. */
    suspend fun clearImported() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            dao.clearPlays()
            dao.clearSeeds()
            tasteRepository.clearImportedTaste()
            prefsStore.edit { it.remove(KEY_LAST_SYNC) }
            importState.value = HistoryImportState.Idle
            refreshCounts()
        }
    }

    private suspend fun refreshCounts() {
        runCatching {
            Counts(
                total = dao.count(),
                spotify = dao.countBySources(listOf(HistorySource.SPOTIFY_API.key, HistorySource.SPOTIFY_EXPORT.key)),
                youTube = dao.countBySources(listOf(HistorySource.YTMUSIC.key, HistorySource.TAKEOUT.key)),
                artists = dao.distinctArtists(),
                export = dao.countByFormat(HistoryFormat.SPOTIFY_EXTENDED) + dao.countByFormat(HistoryFormat.SPOTIFY_BASIC) > 0,
            )
        }.onSuccess { counts.value = it }
            .onFailure { Timber.w(it, "Listening history counts unavailable") }
    }

    // ─── Automatic sync ────────────────────────────────────────────────

    /** On app start and from the background syncs: at most every [SYNC_INTERVAL_MS]; otherwise only keeps learning current. */
    suspend fun syncIfDue() {
        val last = prefs.first()[KEY_LAST_SYNC] ?: 0L
        if (System.currentTimeMillis() - last >= SYNC_INTERVAL_MS) {
            sync(force = true)
        } else {
            rebuildTasteIfStale()
        }
    }

    /**
     * Pulls Spotify's recent plays, top lists and followed artists, then YouTube Music's history page, then
     * rebuilds the imported contribution to learning. Failures are quiet: they only mean less data.
     */
    suspend fun sync(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!syncMutex.tryLock()) return@withContext
        try {
            val p = prefs.first()
            val now = System.currentTimeMillis()
            if (!force && now - (p[KEY_LAST_SYNC] ?: 0L) < SYNC_INTERVAL_MS) return@withContext
            syncing.value = true
            var added = 0
            if ((p[KEY_SPOTIFY_ENABLED] ?: true) && spotifyAuth.isLoggedIn.value) {
                added += runCatching { syncSpotify() }
                    .onFailure { if (it is CancellationException) throw it; Timber.w(it, "Spotify history sync failed") }
                    .getOrDefault(0)
            }
            if ((p[KEY_YOUTUBE_ENABLED] ?: true) && youTubeAuth.cookie != null) {
                added += runCatching { syncYouTubeMusic() }
                    .onFailure { if (it is CancellationException) throw it; Timber.w(it, "YouTube Music history sync failed") }
                    .getOrDefault(0)
            }
            runCatching {
                dao.deleteApiPlaysCoveredByExport()
                dao.deleteYouTubeMusicPlaysCoveredByTakeout()
            }
            prefsStore.edit { it[KEY_LAST_SYNC] = now }
            Timber.i("Listening history sync: %d new plays", added)
            rebuildTaste()
            refreshCounts()
        } finally {
            syncing.value = false
            syncMutex.unlock()
        }
    }

    private suspend fun syncSpotify(): Int {
        val spotify = spotifyRepository.get()
        // Null = the grant is not known yet (sessions from before it was stored): try, and learn from the answer.
        val missing = spotifyAuth.missingScopes(SpotifyAuthManager.HISTORY_SCOPES).orEmpty()
        var trouble = false
        var added = 0

        fun forbidden() {
            val grant = spotifyAuth.missingScopes(SpotifyAuthManager.HISTORY_SCOPES)
            // With every scope verifiably granted, a 403 is Spotify refusing this endpoint for the app, not the login.
            if (grant == null || grant.isNotEmpty()) trouble = true
        }

        if ("user-read-recently-played" !in missing) {
            when (val result = spotify.fetchRecentlyPlayed()) {
                is SpotifyResult.Ok -> {
                    val plays = result.body.items.orEmpty().mapNotNull { item ->
                        val track = item.track ?: return@mapNotNull null
                        val id = track.id?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        if (track.isLocal == true || (track.type != null && track.type != "track")) return@mapNotNull null
                        val playedAt = ListeningHistoryParser.parseIso(item.playedAt) ?: return@mapNotNull null
                        val title = track.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val artist = track.artists?.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        ImportedPlay(
                            source = HistorySource.SPOTIFY_API,
                            format = HistoryFormat.SPOTIFY_API,
                            playedAtMs = playedAt,
                            trackName = title,
                            artistName = artist,
                            albumName = track.album?.name,
                            spotifyTrackId = id,
                            durationMs = track.durationMs,
                        )
                    }
                    added += insert(plays).first
                }
                is SpotifyResult.Forbidden -> forbidden()
                is SpotifyResult.Unauthorized -> return added
                else -> Unit
            }
        } else {
            trouble = true
        }

        val now = System.currentTimeMillis()
        if ("user-top-read" !in missing) {
            for (range in TIME_RANGES) {
                when (val result = spotify.fetchTopArtists(range)) {
                    is SpotifyResult.Ok -> {
                        val items = result.body.items.orEmpty().filter { !it.name.isNullOrBlank() }
                        dao.replaceSeeds(SeedKind.TOP_ARTIST.key, range, items.mapIndexed { index, artist ->
                            TasteSeedEntity(
                                seedKey = "${SeedKind.TOP_ARTIST.key}|$range|${artist.id ?: index}",
                                kind = SeedKind.TOP_ARTIST.key, timeRange = range, position = index, listSize = items.size,
                                artistName = artist.name.orEmpty(), trackName = null, spotifyId = artist.id,
                                genres = artist.genres?.joinToString(","), updatedAt = now,
                            )
                        })
                    }
                    is SpotifyResult.Forbidden -> forbidden()
                    is SpotifyResult.Unauthorized -> return added
                    else -> Unit
                }
                when (val result = spotify.fetchTopTracks(range)) {
                    is SpotifyResult.Ok -> {
                        val items = result.body.items.orEmpty().filter { !it.id.isNullOrBlank() && !it.name.isNullOrBlank() }
                        dao.replaceSeeds(SeedKind.TOP_TRACK.key, range, items.mapIndexed { index, track ->
                            TasteSeedEntity(
                                seedKey = "${SeedKind.TOP_TRACK.key}|$range|${track.id}",
                                kind = SeedKind.TOP_TRACK.key, timeRange = range, position = index, listSize = items.size,
                                artistName = track.artists?.firstOrNull()?.name.orEmpty(), trackName = track.name,
                                spotifyId = track.id, genres = null, updatedAt = now,
                            )
                        })
                    }
                    is SpotifyResult.Forbidden -> forbidden()
                    is SpotifyResult.Unauthorized -> return added
                    else -> Unit
                }
            }
        } else {
            trouble = true
        }

        if ("user-follow-read" !in missing) {
            val followed = mutableListOf<com.theveloper.pixelplay.data.network.spotify.SpotifyArtistFull>()
            var after: String? = null
            var complete = false
            var pages = 0
            while (pages < MAX_FOLLOWED_PAGES) {
                pages++
                when (val result = spotify.fetchFollowedArtists(after)) {
                    is SpotifyResult.Ok -> {
                        val page = result.body.artists
                        followed += page?.items.orEmpty().filter { !it.name.isNullOrBlank() }
                        after = page?.cursors?.after
                        if (after.isNullOrBlank() || page?.next.isNullOrBlank()) {
                            complete = true
                            break
                        }
                    }
                    is SpotifyResult.Forbidden -> {
                        forbidden()
                        break
                    }
                    else -> break
                }
            }
            // A partial list must not replace a complete one from an earlier sync.
            if (complete || pages >= MAX_FOLLOWED_PAGES) {
                dao.replaceSeeds(SeedKind.FOLLOWED_ARTIST.key, "", followed.mapIndexed { index, artist ->
                    TasteSeedEntity(
                        seedKey = "${SeedKind.FOLLOWED_ARTIST.key}||${artist.id ?: index}",
                        kind = SeedKind.FOLLOWED_ARTIST.key, timeRange = "", position = index, listSize = followed.size,
                        artistName = artist.name.orEmpty(), trackName = null, spotifyId = artist.id,
                        genres = artist.genres?.joinToString(","), updatedAt = now,
                    )
                })
            }
        } else {
            trouble = true
        }

        spotifyScopeTrouble.value = trouble
        prefsStore.edit { it[KEY_LAST_SPOTIFY_SYNC] = now }
        return added
    }

    /**
     * YouTube Music's history page knows only the day ("Today", "Yesterday") or a rough period. A play on an
     * exact day is keyed by video + day, so the same play seen again tomorrow under "Yesterday" is not counted
     * twice; older shelves add a video only the first time it is ever seen.
     */
    private suspend fun syncYouTubeMusic(): Int {
        val shelves = innerTube.get().fetchMusicHistory() ?: return 0
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val plays = mutableListOf<ImportedPlay>()
        for (shelf in shelves) {
            val shelfDay = YouTubeHistoryDates.dayFor(shelf.label, today)
            val playedAt = if (shelfDay.day == today) now else shelfDay.day.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
            for (item in shelf.items) {
                // The page lists a video once, at its latest play.
                if (!seen.add(item.videoId)) continue
                if (item.title.isBlank() || item.artist.isBlank()) continue
                if (!shelfDay.exact && dao.hasVideoFrom(HistorySource.YTMUSIC.key, item.videoId)) continue
                val dayKey = shelfDay.day.toEpochDay()
                plays += ImportedPlay(
                    source = HistorySource.YTMUSIC,
                    format = HistoryFormat.YTMUSIC_API,
                    playedAtMs = playedAt,
                    trackName = item.title,
                    artistName = item.artist,
                    albumName = item.album,
                    videoId = item.videoId,
                    durationMs = item.durationSeconds?.let { it * 1000L },
                    dedupeKeyOverride = "ytm|${item.videoId}|${if (shelfDay.exact) dayKey.toString() else "first"}",
                )
            }
        }
        val added = insert(plays).first
        prefsStore.edit { it[KEY_LAST_YOUTUBE_SYNC] = now }
        return added
    }

    /** Inserts in chunks; returns (new plays, already stored). */
    private suspend fun insert(plays: List<ImportedPlay>): Pair<Int, Int> {
        var added = 0
        var duplicates = 0
        plays.chunked(INSERT_BATCH).forEach { chunk ->
            val ids = dao.insertPlays(chunk.map(ImportedPlayEntity::from))
            ids.forEach { if (it == -1L) duplicates++ else added++ }
        }
        return added to duplicates
    }

    // ─── File import ───────────────────────────────────────────────────

    /** Imports Spotify / Google Takeout export files (ZIP or JSON). Runs on after the screen is left. */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = scope.launch {
            importState.value = HistoryImportState.Running(0, 0, null)
            val stats = ListeningHistoryParser.Stats()
            val buffer = ArrayList<ImportedPlayEntity>(INSERT_BATCH)
            val newArtists = HashSet<String>()
            var added = 0
            var duplicates = 0
            var unreadable = 0
            var currentFile: String? = null

            suspend fun flush() {
                if (buffer.isEmpty()) return
                val ids = dao.insertPlays(buffer)
                ids.forEachIndexed { index, id ->
                    if (id == -1L) duplicates++ else {
                        added++
                        newArtists += buffer[index].artistKey
                    }
                }
                buffer.clear()
                importState.value = HistoryImportState.Running(stats.files, stats.plays, currentFile)
            }

            try {
                for (uri in uris) {
                    val name = displayName(uri)
                    val stream = runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    if (stream == null) {
                        unreadable++
                        continue
                    }
                    stream.use { input ->
                        ListeningHistoryParser.parse(
                            input = input,
                            displayName = name,
                            stats = stats,
                            onFile = { file ->
                                currentFile = file
                                importState.value = HistoryImportState.Running(stats.files, stats.plays, file)
                            }
                        ) { play ->
                            buffer += ImportedPlayEntity.from(play)
                            if (buffer.size >= INSERT_BATCH) flush()
                        }
                    }
                }
                flush()
                runCatching {
                    dao.deleteApiPlaysCoveredByExport()
                    dao.deleteBasicPlaysCoveredByExtended()
                    dao.deleteYouTubeMusicPlaysCoveredByTakeout()
                }
                if (added > 0) rebuildTaste()
                refreshCounts()
                // The Home card asked for exactly this; it has done its job.
                if (counts.value.export) prefsStore.edit { it[KEY_HOME_CARD_DISMISSED] = true }
                importState.value = when {
                    added > 0 || duplicates > 0 -> HistoryImportState.Done(added, duplicates, newArtists.size)
                    stats.sawHtmlHistory -> HistoryImportState.Failed(
                        "This is the HTML version of your YouTube history. In Google Takeout, choose JSON as the history format and export again."
                    )
                    unreadable > 0 && stats.files == 0 -> HistoryImportState.Failed("Couldn't open that file. Try picking it again.")
                    else -> HistoryImportState.Failed(
                        "No listening history found in that file. Pick the ZIP from Spotify or Google Takeout, or the JSON files inside it."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Listening history import failed")
                // Whatever was already stored stays: re-importing the same file skips it.
                runCatching { flush() }
                refreshCounts()
                importState.value = if (added > 0) {
                    HistoryImportState.Done(added, duplicates, newArtists.size)
                } else {
                    HistoryImportState.Failed("Couldn't read that file. Make sure it's the ZIP or JSON from Spotify or Google Takeout.")
                }
                if (added > 0) runCatching { rebuildTaste() }
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment

    // ─── Feeding the algorithm ─────────────────────────────────────────

    private suspend fun rebuildTasteIfStale() {
        val builtAt = tasteRepository.importedTaste().builtAtMs
        if (System.currentTimeMillis() - builtAt >= REBUILD_INTERVAL_MS && dao.count() > 0) rebuildTaste()
    }

    /**
     * Re-derives what imported history adds to learning: every play weighted by source and age, mapped to the
     * current library. Reads the plays in pages so a 200k-play history never sits in memory at once.
     */
    suspend fun rebuildTaste() = withContext(Dispatchers.IO) {
        rebuildMutex.withLock {
            val now = System.currentTimeMillis()
            val index = libraryIndex(forceRefresh = true)
            val builder = ImportedTasteBuilder(index, now)
            var afterId = 0L
            while (true) {
                val page = dao.playsAfter(afterId, REBUILD_PAGE)
                if (page.isEmpty()) break
                page.forEach { builder.add(it.toRow()) }
                afterId = page.last().id
            }
            dao.allSeeds().forEach { entity -> entity.toSeed()?.let(builder::addSeed) }
            val taste = builder.build()
            if (taste.signals.isEmpty() && taste.history.isEmpty() && taste.artistPriors.isEmpty()) {
                tasteRepository.clearImportedTaste()
            } else {
                tasteRepository.saveImportedTaste(
                    ImportedTasteSnapshot(taste.signals, taste.history, taste.artistPriors, builtAtMs = now)
                )
            }
            Timber.i(
                "Imported taste rebuilt: %d songs, %d artists (%d plays mapped, %d not in library)",
                taste.signals.size, taste.artistPriors.size, taste.mappedPlays, taste.unmappedPlays
            )
        }
    }

    @Volatile private var cachedIndex: Pair<Long, LibraryIndex>? = null
    @Volatile private var cachedLibrary: Pair<Long, Map<String, Song>>? = null

    private suspend fun library(forceRefresh: Boolean = false): Map<String, Song> {
        val now = System.currentTimeMillis()
        cachedLibrary?.let { (at, songs) -> if (!forceRefresh && now - at < CACHE_MS) return songs }
        val songs = runCatching { musicRepository.getAllSongsOnce() }.getOrDefault(emptyList()).associateBy { it.id }
        cachedLibrary = now to songs
        return songs
    }

    private suspend fun libraryIndex(forceRefresh: Boolean = false): LibraryIndex {
        val now = System.currentTimeMillis()
        cachedIndex?.let { (at, index) -> if (!forceRefresh && now - at < CACHE_MS) return index }
        val songs = library(forceRefresh).values
        val videoToSpotify = runCatching { spotifyDao.getKnownMatches() }.getOrDefault(emptyList())
            .mapNotNull { row -> row.matchedVideoId?.takeIf { it.isNotBlank() }?.let { it to row.spotifyId } }
            .toMap()
        val index = LibraryIndex.build(songs.map { LibraryTrack(it.id, it.spotifyId, it.title, it.artist) }, videoToSpotify)
        cachedIndex = now to index
        return index
    }

    // ─── ListeningInsights ─────────────────────────────────────────────

    /** This app's own plays (weight 1.0), with their songs; early skips (< 30 s) left out. */
    private data class LocalPlay(val song: Song, val playedAtMs: Long, val listenedMs: Long)

    private suspend fun localPlays(sinceMs: Long): List<LocalPlay> {
        val songs = library()
        val events = runCatching { playbackStats.exportEventsForBackup() }.getOrDefault(emptyList())
        return events.mapNotNull { event ->
            if (event.timestamp < sinceMs || event.durationMs < ListeningHistoryWeights.SKIP_THRESHOLD_MS) return@mapNotNull null
            val song = songs[event.songId] ?: songs[TaisInstrumentalIndex.canonicalSongId(event.songId)] ?: return@mapNotNull null
            LocalPlay(song, event.timestamp, event.durationMs)
        }
    }

    private fun primaryArtist(song: Song): String = song.artist.split(',').firstOrNull()?.trim()?.ifBlank { null } ?: song.artist

    override suspend fun topArtists(period: InsightPeriod, limit: Int): List<ArtistInsight> = withContext(Dispatchers.IO) {
        val since = period.sinceMs(System.currentTimeMillis())
        val merged = LinkedHashMap<String, ArtistInsight>()
        runCatching { dao.topArtists(since, limit.coerceAtLeast(1) * 3) }.getOrDefault(emptyList()).forEach { row ->
            merged[row.artistKey] = ArtistInsight(row.name, row.score, row.plays, row.listenedMs)
        }
        for (play in localPlays(since)) {
            val name = primaryArtist(play.song)
            val key = HistoryText.artistKey(name)
            if (key.isEmpty()) continue
            val before = merged[key]
            merged[key] = ArtistInsight(
                name = before?.name ?: name,
                score = (before?.score ?: 0.0) + 1.0,
                plays = (before?.plays ?: 0) + 1,
                listenedMs = (before?.listenedMs ?: 0L) + play.listenedMs,
            )
        }
        merged.values.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun topTracks(period: InsightPeriod, limit: Int): List<TrackInsight> = withContext(Dispatchers.IO) {
        val since = period.sinceMs(System.currentTimeMillis())
        val index = libraryIndex()
        val merged = LinkedHashMap<String, TrackInsight>()
        runCatching { dao.topTracks(since, limit.coerceAtLeast(1) * 3) }.getOrDefault(emptyList()).forEach { row ->
            merged[row.trackKey] = TrackInsight(
                title = row.title, artist = row.artist, score = row.score, plays = row.plays, listenedMs = row.listenedMs,
                songId = index.songIdFor(row.spotifyTrackId, row.videoId, row.artist, row.title),
                spotifyTrackId = row.spotifyTrackId, videoId = row.videoId,
            )
        }
        for (play in localPlays(since)) {
            val song = play.song
            val key = song.spotifyId?.takeIf { it.isNotBlank() }?.let { "sp:$it" }
                ?: "t:${HistoryText.recordingKey(song.artist, song.title)}"
            val before = merged[key]
            merged[key] = TrackInsight(
                title = before?.title ?: song.title,
                artist = before?.artist ?: primaryArtist(song),
                score = (before?.score ?: 0.0) + 1.0,
                plays = (before?.plays ?: 0) + 1,
                listenedMs = (before?.listenedMs ?: 0L) + play.listenedMs,
                songId = song.id,
                spotifyTrackId = song.spotifyId ?: before?.spotifyTrackId,
                videoId = before?.videoId,
            )
        }
        merged.values.sortedByDescending { it.score }.take(limit)
    }

    override suspend fun recentPlays(n: Int): List<RecentPlay> = withContext(Dispatchers.IO) {
        if (n <= 0) return@withContext emptyList()
        val index = libraryIndex()
        val imported = runCatching { dao.recent(n) }.getOrDefault(emptyList()).map { row ->
            RecentPlay(
                title = row.trackName, artist = row.artistName, album = row.albumName, playedAtMs = row.playedAt,
                origin = PlayOrigin.of(HistorySource.fromKey(row.source)), msPlayed = row.msPlayed,
                songId = index.songIdFor(row.spotifyTrackId, row.videoId, row.artistName, row.trackName),
            )
        }
        val songs = library()
        val local = runCatching { playbackStats.loadPlaybackHistory(n) }.getOrDefault(emptyList()).mapNotNull { entry ->
            val song = songs[entry.songId] ?: songs[TaisInstrumentalIndex.canonicalSongId(entry.songId)] ?: return@mapNotNull null
            RecentPlay(song.title, primaryArtist(song), song.album.ifBlank { null }, entry.timestamp, PlayOrigin.PIXLAUDIO, songId = song.id)
        }
        (imported + local).sortedByDescending { it.playedAtMs }.take(n)
    }

    override suspend fun listeningSummary(): ListeningSummary = withContext(Dispatchers.IO) {
        val seeds = runCatching { dao.allSeeds() }.getOrDefault(emptyList())
        val genreScores = HashMap<String, Double>()
        for (seed in seeds) {
            val genres = seed.genres?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: continue
            val weight = 1.0 - 0.7 * (seed.position.toDouble() / seed.listSize.coerceAtLeast(1))
            genres.forEach { genreScores.merge(it, weight, Double::plus) }
        }
        val appPlays = runCatching { playbackStats.exportEventsForBackup().size }.getOrDefault(0)
        val c = counts.value
        ListeningSummary(
            appPlays = appPlays,
            importedPlays = runCatching { dao.count() }.getOrDefault(c.total),
            spotifyPlays = c.spotify,
            youTubeMusicPlays = c.youTube,
            importedArtists = c.artists,
            importedTracks = runCatching { dao.distinctTracks() }.getOrDefault(0),
            firstPlayedMs = runCatching { dao.firstPlayedAt() }.getOrNull(),
            lastPlayedMs = runCatching { dao.lastPlayedAt() }.getOrNull(),
            importedListeningMs = runCatching { dao.totalMsPlayed() }.getOrDefault(0L),
            lastSyncedMs = prefs.first()[KEY_LAST_SYNC] ?: 0L,
            topArtists = topArtists(InsightPeriod.LAST_6_MONTHS, 5).map { it.name },
            topGenres = genreScores.entries.sortedByDescending { it.value }.take(5).map { it.key },
            followedArtists = seeds.count { it.kind == SeedKind.FOLLOWED_ARTIST.key },
        )
    }

    companion object {
        /** Automatic sync at most this often (on app start and in the background syncs). */
        const val SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** Library changes (new songs) are picked up by re-mapping at most this often between syncs. */
        private const val REBUILD_INTERVAL_MS = 60 * 60 * 1000L
        private const val CACHE_MS = 10 * 60 * 1000L
        private const val INSERT_BATCH = 2_000
        private const val REBUILD_PAGE = 5_000
        private const val MAX_FOLLOWED_PAGES = 20
        private val TIME_RANGES = listOf("short_term", "medium_term", "long_term")

        private val KEY_SPOTIFY_ENABLED = booleanPreferencesKey("spotify_history_enabled")
        private val KEY_YOUTUBE_ENABLED = booleanPreferencesKey("youtube_music_history_enabled")
        private val KEY_LAST_SYNC = longPreferencesKey("last_sync_ms")
        private val KEY_LAST_SPOTIFY_SYNC = longPreferencesKey("last_spotify_sync_ms")
        private val KEY_LAST_YOUTUBE_SYNC = longPreferencesKey("last_youtube_sync_ms")
        private val KEY_HOME_CARD_DISMISSED = booleanPreferencesKey("home_card_dismissed")
    }
}
