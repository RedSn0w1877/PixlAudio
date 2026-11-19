package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.FeedbackDao
import com.theveloper.pixelplay.data.brain.store.FeedbackEntity
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** The two kinds of `feedback` row (docs/brain-spec.md, 3.8) and what they point at. Never renumber. */
object FeedbackKinds {
    const val PRIVATE_SPAN = 1
    const val DONT_COUNT = 2

    const val TARGET_SESSION = 0
    const val TARGET_SONG = 1
    const val TARGET_ALBUM = 2
    const val TARGET_PLAYLIST = 3
    const val TARGET_LAST_HOUR = 4
}

/**
 * Private session (docs/brain-spec.md, 8.5): while it is on, every play is logged with `learnExcluded`,
 * so it counts for nothing in the picks. The state is visible (a "Private" pill) and is OFF at every app
 * start: it is held in memory only, never saved, so a listener who forgot it was on is not private
 * for good.
 *
 * Each switch-on and switch-off is also a row of `feedback` (a span with its end), so the log of what
 * was excluded and why can be shown and replayed. A play counts as private when it overlapped a span
 * at all: one that began before the switch went on and was still sounding, or one that ended after it
 * went off, is private too (the listener's intent was "not this").
 */
@Singleton
class PrivateSession @Inject constructor(
    private val feedback: FeedbackDao,
    private val clock: BrainClock,
    @AppScope private val scope: CoroutineScope,
) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    // Spans in this process: [startMs, endMs], endMs = Long.MAX_VALUE while the span is open.
    private val spans = ArrayList<LongArray>()
    private var openRowId = 0L

    @Synchronized
    fun set(on: Boolean) {
        if (on == _active.value) return
        val now = clock.nowMs()
        if (on) {
            spans.add(longArrayOf(now, Long.MAX_VALUE))
            // Plays are recorded within seconds of ending: nothing older than a few hours can still ask.
            if (spans.size > MAX_SPANS) spans.removeAt(0)
            openRowId = now * 8 + FeedbackKinds.PRIVATE_SPAN
            val row = FeedbackEntity(openRowId, FeedbackKinds.PRIVATE_SPAN, FeedbackKinds.TARGET_SESSION, now, now, null)
            write { feedback.insert(row) }
        } else {
            spans.lastOrNull()?.let { if (it[1] == Long.MAX_VALUE) it[1] = now }
            val id = openRowId
            if (id != 0L) write { feedback.undo(id, now) }
            openRowId = 0L
        }
        _active.value = on
    }

    /** True when a play from [startMs] to [endMs] overlapped a private span. */
    @Synchronized
    fun overlaps(startMs: Long, endMs: Long): Boolean {
        for (span in spans) if (span[0] <= endMs && startMs <= span[1]) return true
        return false
    }

    private fun write(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.d(e, "brain: the private session note could not be saved")
            }
        }
    }

    private companion object {
        const val MAX_SPANS = 24
    }
}
