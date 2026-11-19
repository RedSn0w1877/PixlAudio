package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import timber.log.Timber

/**
 * "Use my Spotify music to shape picks" turned off means Spotify's artist genres and album years are not in the brain's picture of the
 * listener any more. Rows written while it was on carry them as stored claims, and a claim that nobody replaces is kept for ever
 * (a partial pass never wipes what an earlier one learned), so this runs once when the switch is found off: every row with such a claim
 * is assembled again with the switch off, which drops them and re-merges tags, moods and the year. The islands are then due at once.
 * Switching it on again clears the mark, so the next switch-off purges again. Database only, no network; the refresh job runs it.
 */
@Singleton
class SpotifyDnaPurge @Inject constructor(
    private val switch: BrainSwitch,
    private val tracks: TrackDnaDao,
    private val artists: ArtistDnaDao,
    private val states: BrainStateDao,
    private val atlasProvider: AtlasProvider,
    private val islands: IslandStore,
    private val clock: BrainClock,
) {
    /** How many rows were rewritten (0 when the switch is on, the purge was done, or there was nothing to take out). */
    suspend fun run(): Int {
        val on = switch.spotifyShapesPicks()
        val done = states.get(KEY_PURGED)?.toString(Charsets.UTF_8) == "1"
        if (on) {
            if (done) states.put(BrainStateEntity(KEY_PURGED, "0".toByteArray(Charsets.UTF_8)))
            return 0
        }
        if (done) return 0
        val atlas = atlasProvider.atlas() ?: return 0
        var changed = 0
        var after = Long.MIN_VALUE
        while (true) {
            val page = tracks.page(after, PAGE)
            if (page.isEmpty()) break
            after = page.last().rid
            val dirty = page.filter(::carriesSpotify)
            if (dirty.isNotEmpty()) {
                val priorArtists = artists.getAll(dirty.mapNotNull { aidOf(it).takeIf { a -> a != 0L } }.distinct()).associateBy { it.aid }
                val now = clock.nowMs()
                for (row in dirty) {
                    try {
                        val aid = aidOf(row)
                        val prior = priorArtists[aid]
                        val target = DnaTarget(
                            rid = row.rid, aid = aid, spotifyId = row.spotifyId, isrc = row.isrc, title = row.title,
                            artist = prior?.name ?: row.artistDisplay.substringBefore(", "), artistDisplay = row.artistDisplay,
                            durationMs = row.durationMs,
                            // A year the YouTube row gave is a fact the app holds, not Spotify's: it stays.
                            ytYear = if (row.yearSource == TrackDna.YEAR_YOUTUBE) row.firstYear else 0,
                            spotifyOff = true,
                        )
                        val r = DnaAssembler.assemble(target, DnaEvidence(), row, prior, atlas, atlas.version, now, DnaEnricher.currentYear(now) + 1)
                        tracks.upsert(r.track)
                        r.artist?.let { artists.upsert(it) }
                        changed++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "brain: Spotify genres not taken out of %d", row.rid)
                    }
                }
            }
            if (page.size < PAGE) break
        }
        states.put(BrainStateEntity(KEY_PURGED, "1".toByteArray(Charsets.UTF_8)))
        if (changed > 0) islands.markRefit()
        Timber.i("brain: Spotify facts taken out of %d rows", changed)
        return changed
    }

    private fun aidOf(row: TrackDnaEntity): Long = row.artistIds.substringBefore(',').toLongOrNull() ?: 0L

    private fun carriesSpotify(row: TrackDnaEntity): Boolean =
        row.yearSource == TrackDna.YEAR_SPOTIFY || DnaAssembler.decodeClaims(row.tagInputs).any { it.provider == DnaAssembler.P_SPOTIFY }

    companion object {
        const val KEY_PURGED = "dna_spotify_purged"
        private const val PAGE = 300
    }
}
