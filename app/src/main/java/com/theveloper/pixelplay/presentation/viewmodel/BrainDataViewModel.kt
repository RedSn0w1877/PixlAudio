package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.brain.AtlasProvider
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainRefresh
import com.theveloper.pixelplay.data.brain.BrainSwitch
import com.theveloper.pixelplay.data.brain.RequestLedger
import com.theveloper.pixelplay.data.brain.source.BrainRemoteFlags
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.source.KnowledgeHost
import com.theveloper.pixelplay.data.brain.source.KnowledgeLedger
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.DnaCoverage
import com.theveloper.pixelplay.data.brain.store.DnaSource
import com.theveloper.pixelplay.data.brain.store.SourceMissDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.history.ListeningHistoryDao
import com.theveloper.pixelplay.data.history.SeedKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings > Developer mode > Brain data (docs/brain-spec.md, 3.7 and 12): what the genre atlas and the track facts hold, how many
 * requests each open-data source has had today, each source's own off switch with the terms it is used under, and what the Spotify
 * sync returned for artist genres. Read when the screen opens; nothing here runs at app start.
 */
@HiltViewModel
class BrainDataViewModel @Inject constructor(
    private val atlasProvider: AtlasProvider,
    private val tracks: TrackDnaDao,
    private val artists: ArtistDnaDao,
    private val misses: SourceMissDao,
    private val ledger: KnowledgeLedger,
    private val youTubeLedger: RequestLedger,
    private val states: BrainStateDao,
    private val switch: BrainSwitch,
    private val remote: BrainRemoteFlags,
    private val historyDao: dagger.Lazy<ListeningHistoryDao>,
    private val clock: BrainClock,
) : ViewModel() {

    class SourceRow(
        val host: KnowledgeHost,
        val enabled: Boolean,
        /** False when the remote config switches the source off for every phone. */
        val remoteAllowed: Boolean,
        val requestsToday: Int,
        val lastRequestMs: Long,
        val tracksAnswered: Int,
    )

    class State(
        val loading: Boolean = true,
        val atlasLoaded: Boolean = false,
        val atlasVersion: Int = 0,
        val atlasGenres: Int = 0,
        val atlasDescribed: Int = 0,
        val atlasFamilies: Int = 0,
        val coverage: DnaCoverage = DnaCoverage(0, 0, 0, 0, 0, 0),
        val artistsKnown: Int = 0,
        val artistsWithGenres: Int = 0,
        val activeMisses: Int = 0,
        val sources: List<SourceRow> = emptyList(),
        val youTubeToday: Int = 0,
        val youTubeLimit: Int = HostBudget.DAILY_LIMIT,
        val spotifyArtistSeeds: Int = 0,
        val spotifyArtistSeedsWithGenres: Int = 0,
        val spotifyShapes: Boolean = true,
        val lastRun: BrainRefresh.Note? = null,
        val nowMs: Long = 0L,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = clock.nowMs()
            val atlas = atlasProvider.atlas()
            val coverage = runCatching { tracks.coverage() }.getOrDefault(DnaCoverage(0, 0, 0, 0, 0, 0))
            val today = ledger.today()
            val bits = mapOf(
                KnowledgeHost.DEEZER to DnaSource.DEEZER, KnowledgeHost.MUSICBRAINZ to DnaSource.MUSICBRAINZ,
                KnowledgeHost.LISTENBRAINZ to DnaSource.LISTENBRAINZ, KnowledgeHost.WIKIDATA to DnaSource.WIKIDATA, KnowledgeHost.ITUNES to DnaSource.ITUNES,
            )
            val rows = KnowledgeHost.entries.map { h ->
                SourceRow(
                    host = h,
                    enabled = switch.sourceEnabledFlow(h).first(),
                    remoteAllowed = remote.sourceAllowed(h.key),
                    requestsToday = today[h] ?: 0,
                    lastRequestMs = ledger.lastRequestMs(h),
                    tracksAnswered = runCatching { tracks.countFetchedBy(bits.getValue(h)) }.getOrDefault(0),
                )
            }
            val seeds = runCatching { historyDao.get().allSeeds() }.getOrDefault(emptyList())
                .filter { SeedKind.fromKey(it.kind).let { k -> k == SeedKind.TOP_ARTIST || k == SeedKind.FOLLOWED_ARTIST } }
                .distinctBy { it.spotifyId ?: it.artistName }
            val note = runCatching { states.get(BrainRefresh.KEY_LAST)?.toString(Charsets.UTF_8) }.getOrNull()
            _state.update {
                State(
                    loading = false,
                    atlasLoaded = atlas != null,
                    atlasVersion = atlas?.version ?: 0,
                    atlasGenres = atlas?.size ?: 0,
                    atlasDescribed = atlas?.allIds?.count { atlas.descriptor(it) != null } ?: 0,
                    atlasFamilies = atlas?.families?.size ?: 0,
                    coverage = coverage,
                    artistsKnown = runCatching { artists.count() }.getOrDefault(0),
                    artistsWithGenres = runCatching { artists.countWithTags() }.getOrDefault(0),
                    activeMisses = runCatching { misses.countActive(now) }.getOrDefault(0),
                    sources = rows,
                    youTubeToday = youTubeLedger.countSince(now - HostBudget.DAY_MS),
                    spotifyArtistSeeds = seeds.size,
                    spotifyArtistSeedsWithGenres = seeds.count { !it.genres.isNullOrBlank() },
                    spotifyShapes = switch.spotifyShapesPicks(),
                    lastRun = BrainRefresh.parseNote(note),
                    nowMs = now,
                )
            }
        }
    }

    fun setSourceEnabled(host: KnowledgeHost, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { switch.setSourceEnabled(host, enabled) }
            refresh()
        }
    }
}
