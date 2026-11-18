package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import com.theveloper.pixelplay.data.brain.BrainFeedback
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

/** The bar that follows a piece of feedback: what happened, Undo, and after "Not for me" one more step. */
@Immutable
data class BrainNotice(
    /** Grows with every notice, so the bar restarts its timer for a new one. */
    val serial: Long,
    val kind: Kind,
    /** The taste change to take back with Undo; null when nothing can be undone. */
    val adjustmentId: String?,
    /** ONE artist name, for "Less from ..."; blank when the track names nobody. */
    val artistName: String,
) {
    enum class Kind { NOT_FOR_ME, NOT_SAVED, LESS_FROM, UNDONE }
}

/** What the sheet behind a reason line shows. */
@Immutable
data class WhySheetState(
    val song: Song,
    /** `WhyLine` key name ("radio_fans", ...). */
    val whyKey: String,
    val whyArg: String,
)

/**
 * The listener's feedback on picks and the two sheets that explain them (docs/brain-spec.md, 8.5,
 * 8.8, 8.9). State only: the mute itself is `BrainFeedback`, the skip is the player's.
 */
@Singleton
class BrainActionsStateHolder @Inject constructor(
    private val feedback: BrainFeedback,
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

    /** Undo on the bar: takes the change it names back. */
    fun undo() {
        val current = _notice.value ?: return
        val id = current.adjustmentId ?: return
        scope.launch {
            try {
                feedback.undo(id)
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

    fun lessFrom(artistName: String) {
        scope.launch {
            try {
                val id = feedback.lessFrom(artistName)
                show(if (id != null) BrainNotice.Kind.LESS_FROM else BrainNotice.Kind.NOT_SAVED, id, artistName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: less-from failed")
            }
        }
    }

    fun dismissNotice() {
        dismissJob?.cancel()
        _notice.value = null
    }

    fun showWhy(song: Song, whyKey: String, whyArg: String) {
        _whySheet.value = WhySheetState(song, whyKey, whyArg)
    }

    fun dismissWhy() {
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
