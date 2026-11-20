package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.brain.FeedbackKinds
import com.theveloper.pixelplay.data.brain.LearnExclusion
import com.theveloper.pixelplay.data.brain.LibraryTrackCatalog
import com.theveloper.pixelplay.data.brain.TrackResolver
import com.theveloper.pixelplay.data.brain.WhyFactsLoader
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** The bar that follows a piece of feedback: what happened, Undo, and after some of them one more step. */
@Immutable
data class BrainNotice(
    /** Grows with every notice, so the bar restarts its timer for a new one. */
    val serial: Long,
    val kind: Kind,
    /** The change to take back with Undo (a taste change id, or a "don't count" row id); null when nothing can be undone. */
    val adjustmentId: String?,
    /** ONE artist name, for "Less from ..." and "Mute ..."; blank when the track names nobody. */
    val artistName: String,
) {
    enum class Kind {
        NOT_FOR_ME, NOT_SAVED, LESS_FROM, UNDONE,

        /** "More like this" was applied. */
        MORE_LIKE,

        /** "Less from X" was asked again for an artist that is already reduced: the bar offers to mute them. */
        OFFER_MUTE,

        /** The artist is muted. */
        MUTED_ARTIST,

        /** "Don't count this" was applied; Undo takes it back. */
        DONT_COUNT,

        /** There was nothing to leave out. */
        NOTHING_TO_LEAVE_OUT,
    }
}

/** What the sheet behind a reason line shows. */
@Immutable
data class WhySheetState(
    val song: Song,
    /** `WhyLine` key name ("radio_fans", ...). */
    val whyKey: String,
    val whyArg: String,
    /** Up to two supporting facts (8.8), loaded after the sheet opens; empty until then or when there are none. */
    val facts: List<WhyFactLine> = emptyList(),
    /**
     * What moved this pick's score the most (stage 4, 9.3 `explain_track`): the fixed factor names of the vector it was served with, read after the
     * sheet opens; empty when the pick has no stored vector (nothing is guessed).
     */
    val factors: List<WhyFactorLine> = emptyList(),
)

/** One factor of a pick: a fixed name from `AssistantRules.FACTOR_NAMES` and whether it pushed the pick up or held it back. */
@Immutable
data class WhyFactorLine(val name: String, val up: Boolean)

/** One supporting fact: a `WhyFacts` key and its two arguments; the words are in the resources. */
@Immutable
data class WhyFactLine(val key: String, val a: Int, val b: Int)

/**
 * The listener's feedback on picks and the two sheets that explain them (docs/brain-spec.md, 8.5,
 * 8.8, 8.9). State only: the mute itself is `BrainFeedback`, the skip is the player's.
 */
@Singleton
class BrainActionsStateHolder @Inject constructor(
    private val feedback: BrainFeedback,
    private val learnExclusion: LearnExclusion,
    private val engine: BrainEngine,
    private val identity: TrackResolver,
    private val whyFacts: WhyFactsLoader,
    private val servedFactors: com.theveloper.pixelplay.data.brain.ServedFactors,
    private val historyDao: dagger.Lazy<com.theveloper.pixelplay.data.history.ListeningHistoryDao>,
    @AppScope private val scope: CoroutineScope,
) {
    private val _notice = MutableStateFlow<BrainNotice?>(null)
    val notice: StateFlow<BrainNotice?> = _notice.asStateFlow()

    private val _whySheet = MutableStateFlow<WhySheetState?>(null)
    val whySheet: StateFlow<WhySheetState?> = _whySheet.asStateFlow()

    private val _howPicksWorkVisible = MutableStateFlow(false)
    val howPicksWorkVisible: StateFlow<Boolean> = _howPicksWorkVisible.asStateFlow()

    private var serial = 0L
    private var dismissJob: Job? = null
    private var factsJob: Job? = null

    // What "Undo" of a "don't count" bar takes back. Held here: the bar's token is only the row id.
    @Volatile private var lastExcluded: LearnExclusion.Excluded? = null

    /**
     * "Not for me" (8.5). [skipNow] is called at once when [isPlaying] says this song is the one
     * sounding: the play is flagged first, so the skip is logged as what it was.
     */
    fun notForMe(song: Song, isPlaying: Boolean, skipNow: () -> Unit) {
        feedback.flagNotForMe(song.id)
        if (isPlaying) skipNow()
        scope.launch {
            try {
                val result = feedback.mute(song)
                show(
                    if (result.adjustmentId != null) BrainNotice.Kind.NOT_FOR_ME else BrainNotice.Kind.NOT_SAVED,
                    result.adjustmentId,
                    result.artistName,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: not-for-me failed")
            }
        }
    }

    /** "More like this" (8.5): the artist is boosted for two weeks, so the next batch leans that way. */
    fun moreLikeThis(song: Song) {
        scope.launch {
            try {
                val result = feedback.moreLike(song)
                show(
                    if (result.adjustmentId != null) BrainNotice.Kind.MORE_LIKE else BrainNotice.Kind.NOT_SAVED,
                    result.adjustmentId,
                    result.artistName,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: more-like-this failed")
            }
        }
    }

    /**
     * "Don't count this" for one song (8.5): its plays, the one in flight included, stop shaping the picks.
     * [playingSongId] is the track that is sounding now (or null), so its play is flagged as well.
     */
    fun dontCount(song: Song, playingSongId: String?) {
        scope.launch {
            try {
                val rid = ridOf(song)
                // Plays from an imported Spotify history are not in the log; the rebuild leaves them out by the row this writes.
                val imported = song.spotifyId?.takeIf { it.isNotBlank() }
                    ?.let { runCatching { historyDao.get().countOfSpotifyTrack(it) }.getOrDefault(0) } ?: 0
                val excluded = if (rid == 0L) null else learnExclusion.excludeRecordings(
                    rids = listOf(rid),
                    targetKind = FeedbackKinds.TARGET_SONG,
                    targetId = rid,
                    playingSongId = playingSongId?.takeIf { it == song.id },
                    importedPlays = imported,
                )
                showExcluded(excluded)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: don't-count failed")
            }
        }
    }

    /** "Don't count the last hour" (8.5). */
    fun dontCountLastHour(playingSongId: String?) {
        scope.launch {
            try {
                showExcluded(learnExclusion.excludeLastHour(playingSongId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: don't-count-the-last-hour failed")
            }
        }
    }

    private fun showExcluded(excluded: LearnExclusion.Excluded?) {
        if (excluded == null || excluded.count == 0) {
            lastExcluded = null
            show(BrainNotice.Kind.NOTHING_TO_LEAVE_OUT, null, "")
        } else {
            lastExcluded = excluded
            show(BrainNotice.Kind.DONT_COUNT, excluded.feedbackId.toString(), "")
        }
    }

    /**
     * The listener swiped an engine pick out of the queue without hearing it (8.4): a weak negative for the
     * rest of the session (`h = 0.2`). Only when the brain is in memory: with no session there is nothing
     * to nudge, and a swipe must not be what loads it.
     */
    fun swiped(song: Song) {
        if (!engine.isLoaded) return
        scope.launch {
            try {
                val facts = LibraryTrackCatalog.factsOf(song) ?: return@launch
                val rid = identity.resolveTrack(facts.keys, facts.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                if (rid == 0L) return@launch
                val aid = identity.resolveArtistNames(listOf(facts.primaryArtist))[0]
                engine.withState { state -> Session.weakNegative(state.session, rid, aid) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "brain: a swipe could not be noted")
            }
        }
    }

    private suspend fun ridOf(song: Song): Long {
        val facts = LibraryTrackCatalog.factsOf(song) ?: return 0L
        return identity.resolveTrack(facts.keys, facts.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
    }

    /** Undo on the bar: takes the change it names back. */
    fun undo() {
        val current = _notice.value ?: return
        val id = current.adjustmentId ?: return
        scope.launch {
            try {
                if (current.kind == BrainNotice.Kind.DONT_COUNT) {
                    lastExcluded?.takeIf { it.feedbackId.toString() == id }?.let { learnExclusion.undo(it) }
                    lastExcluded = null
                } else {
                    feedback.undo(id)
                }
                show(BrainNotice.Kind.UNDONE, null, "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: undo failed")
            }
        }
    }

    /** "Less from X" on the bar after "Not for me": the existing reduce on the artist. */
    fun lessFromArtist() {
        val artist = _notice.value?.artistName?.takeIf { it.isNotBlank() } ?: return
        lessFrom(artist)
    }

    /**
     * "Less from X" (8.5). The first tap reduces the artist; a second tap on an artist that is already
     * reduced does not stack another change: the bar offers to mute them instead.
     */
    fun lessFrom(artistName: String) {
        scope.launch {
            try {
                if (feedback.isReduced(artistName)) {
                    show(BrainNotice.Kind.OFFER_MUTE, null, artistName)
                    return@launch
                }
                val id = feedback.lessFrom(artistName)
                show(if (id != null) BrainNotice.Kind.LESS_FROM else BrainNotice.Kind.NOT_SAVED, id, artistName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: less-from failed")
            }
        }
    }

    /** "Mute X" on the bar: the artist leaves what the app picks by itself. */
    fun muteArtist() {
        val artist = _notice.value?.artistName?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            try {
                val id = feedback.muteArtist(artist)
                show(if (id != null) BrainNotice.Kind.MUTED_ARTIST else BrainNotice.Kind.NOT_SAVED, id, artist)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: mute-artist failed")
            }
        }
    }

    fun dismissNotice() {
        dismissJob?.cancel()
        _notice.value = null
    }

    fun showWhy(song: Song, whyKey: String, whyArg: String, servedX: String = "", model: Int = -1) {
        _whySheet.value = WhySheetState(song, whyKey, whyArg)
        factsJob?.cancel()
        factsJob = scope.launch {
            try {
                val facts = whyFacts.load(song).map { WhyFactLine(it.key, it.a, it.b) }
                _whySheet.value?.takeIf { it.song.id == song.id }?.let { _whySheet.value = it.copy(facts = facts) }
                // The factors come from the vector the pick was served with: the queue's copy, else the newest play of this song that kept one.
                val factors = (if (servedX.isNotEmpty()) servedFactors.topOfHex(servedX, model) else null) ?: servedFactors.topOfLastPlay(song)
                if (!factors.isNullOrEmpty()) {
                    _whySheet.value?.takeIf { it.song.id == song.id }
                        ?.let { _whySheet.value = it.copy(factors = factors.map { f -> WhyFactorLine(f.name, f.up) }) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "brain: the supporting facts could not be read")
            }
        }
    }

    fun dismissWhy() {
        factsJob?.cancel()
        _whySheet.value = null
    }

    fun showHowPicksWork() {
        _howPicksWorkVisible.value = true
    }

    fun dismissHowPicksWork() {
        _howPicksWorkVisible.value = false
    }

    @Synchronized
    private fun show(kind: BrainNotice.Kind, adjustmentId: String?, artistName: String) {
        serial++
        _notice.value = BrainNotice(serial, kind, adjustmentId, artistName)
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(if (kind == BrainNotice.Kind.UNDONE) SHORT_MS else VISIBLE_MS)
            _notice.value = null
        }
    }

    private companion object {
        const val VISIBLE_MS = 7_000L
        const val SHORT_MS = 2_500L
    }
}
