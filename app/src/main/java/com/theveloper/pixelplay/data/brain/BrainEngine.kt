package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.ArmBandit
import com.theveloper.pixelplay.brain.ArmPicker
import com.theveloper.pixelplay.brain.Brain
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Gate
import com.theveloper.pixelplay.brain.GradeTracker
import com.theveloper.pixelplay.brain.LearnReplay
import com.theveloper.pixelplay.brain.LearnerCodec
import com.theveloper.pixelplay.brain.NoveltyAppetite
import com.theveloper.pixelplay.brain.PosteriorCodec
import com.theveloper.pixelplay.brain.Posteriors
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.RankerModel
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.brain.SessionState
import com.theveloper.pixelplay.data.brain.store.AffinityEntity
import com.theveloper.pixelplay.data.brain.store.BrainStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** A track of this session the engine may seed a radio from, with what a radio request needs. */
data class AnchorInfo(
    val rid: Long,
    val mediaId: String,
    val title: String,
    /** ONE artist name. */
    val primaryArtist: String,
    val spotifyId: String?,
    val durationMs: Long,
)

/**
 * How one list was ranked, fixed when it was built (5.12 point 8): [source] is null for the prior; [control] is true when the list was
 * ranked with the prior because its session is one of the one in ten; [modelVersion] is 1 when the learned weights ranked it, else 0.
 */
class RankingChoice(val source: Ranker.WeightSource?, val control: Boolean, val modelVersion: Int)

/** Rebuilds the affinity tables from the log, the imported history and the library facts (4.4). */
interface StateRebuilder {
    /** True when the stored rows are missing, were built by other logic, or the import changed. */
    suspend fun isNeeded(): Boolean

    /**
     * True when the rebuild cannot wait for the refresh worker: the tables are missing, were built by other logic, or the import or a switch
     * changed. The learner's replay and the weekly refresh of the facts are not urgent: the worker runs them (5.9, "no second job").
     */
    suspend fun isUrgent(): Boolean = isNeeded()

    suspend fun rebuild(nowMs: Long): Rebuilt

    class Rebuilt(
        val state: Affinity.State,
        /** Ids of the stored events the rebuild read that started in its last day: see [BrainEngine]. */
        val recentEventIds: Set<Long>,
        /** Written to `brain_state` together with the rows. */
        val stateValues: Map<String, String>,
        /** Stage 3: the same replay's learner, its predictions and the favourites it used; null when the rebuild did not train it. */
        val learned: LearnReplay.Result? = null,
        val likedRids: Set<Long> = emptySet(),
    )
}

/**
 * The brain's in-memory state and the one lock that guards it (docs/brain-spec.md, 2.3, 5.13).
 *
 * Holds the affinity tables (loaded from `brain.db` the first time the brain is needed, never at app
 * start), the grading context and the session. Everything that reads or changes them goes through
 * [withState], on a background thread, one caller at a time.
 *
 * The tables are a cache of the event log. When they have to be rebuilt (first use: the one-shot
 * migration; a new spec version; a changed import; a clock that jumped) the rebuild runs beside the
 * live state, which keeps serving; plays recorded meanwhile are applied to the new tables before they
 * replace the old ones.
 */
@Singleton
class BrainEngine @Inject constructor(
    private val store: BrainStore,
    private val rebuilder: dagger.Lazy<StateRebuilder>,
    private val clock: BrainClock,
) {
    class Loaded internal constructor(affinity: Affinity.State) {
        var affinity: Affinity.State = affinity
            internal set
        var tracker: GradeTracker = GradeTracker(affinity.completions)
            internal set
        var session: SessionState = SessionState()
            internal set

        /** The learner (stage 3, 5.9): the ranker's weights, the gate's state and the figures Your sound reads. */
        var learner: Learner = Learner(Ranker.newModel(0L))
            internal set

        /** Bumped by "Reset what I've learned": a rebuild that started before it must not bring the old weights back. */
        var learnEpoch: Int = 0
            internal set

        /**
         * Which weights rank a list for the session [sessionId]: null (the prior `M0`) while the gate has the prior on, and for one
         * session in ten (the control sessions, 5.12 point 8). With [seed] the list gets one posterior sample (5.10).
         */
        fun ranking(sessionId: Long, seed: Long? = null): Ranker.WeightSource? = rankingChoice(sessionId, seed).source

        /**
         * [ranking] together with what the plays of the list must say about it (5.12 point 8): decided ONCE, when the list is built,
         * and carried with each queue entry, so the control completion rate is measured on what was really ranked with the prior.
         */
        fun rankingChoice(sessionId: Long, seed: Long? = null): RankingChoice {
            val control = Ranker.isControlSession(controlKey(sessionId))
            if (!learner.useLearned || control) return RankingChoice(null, control, 0)
            return RankingChoice(Ranker.WeightSource(learner.model, true, seed), false, BrainEventRecorder.MODEL_LEARNED)
        }

        /**
         * The arm bandit of a list (5.10), seeded by [seed]: null while the gate has the prior on and in a control session, so a listener on the
         * standard recipe keeps stage 2's choice of the novel candidate exactly.
         */
        fun armPicker(sessionId: Long, family: Int, seed: Long): ArmPicker? {
            if (!learner.useLearned || Ranker.isControlSession(controlKey(sessionId))) return null
            return ArmBandit.Sampler(learner.posteriors.arms, family, seed)
        }

        private var pendingKey = 0L
        private var pendingFor = -1L

        /**
         * The id a list's control draw is taken on. A session's id is the start time of its first play, so before that play (right after the
         * process starts, or after the session has expired and before the next play) there is no live id: the stored one is 0 or the old
         * session's. Drawing on it would make every list built then the same kind for good (id 0 is never a control). Those lists are drawn on
         * one key per gap instead (the time the gap was first seen), so about one in ten of them is a control as well.
         */
        fun controlKey(sessionId: Long, nowMs: Long = System.currentTimeMillis()): Long {
            val stale = sessionId == 0L || (sessionId == session.sessionId && Session.isExpired(session, nowMs))
            if (!stale) return sessionId
            if (pendingFor != sessionId) {
                pendingFor = sessionId
                pendingKey = nowMs
            }
            return pendingKey
        }

        /**
         * The novelty share a new session starts at (5.5, "nuSession starts at the surface's nu"): the learned appetite while the gate has the
         * learned weights on, else stage 2's constant.
         */
        fun sessionNu(nowMs: Long): Double =
            if (learner.useLearned) NoveltyAppetite.nu(learner.posteriors.appetite, nowMs) else Session.NU_DEFAULT

        /** What a radio request needs for each of the session's anchors, by rid. */
        val anchors: HashMap<Long, AnchorInfo> = HashMap()

        /** Which way three skipped picks turned the engine, for this session. */
        var direction: Direction = Direction()
            private set

        /** Starts a new session: the old one's lists, blocks, anchors and direction are gone. */
        fun newSession(nowMs: Long = System.currentTimeMillis()) {
            session = SessionState()
            // A new gap before the next play: its lists are drawn on a fresh key (see [controlKey]), and a control draw keeps the standard share.
            pendingFor = -1L
            session.nuSession = if (Ranker.isControlSession(controlKey(0L, nowMs))) Session.NU_DEFAULT else sessionNu(nowMs)
            anchors.clear()
            direction = Direction()
        }
    }

    /** The learner's state, held under the engine's one lock (see [BrainEngine]). */
    class Learner(
        var model: RankerModel,
        var gate: Gate.State = Gate.State(),
        var summary: Gate.Summary? = null,
        /** [LearnReplay.VERSION] the model was built under; 0 when there is none yet (a rebuild is due). */
        var learnVersion: Int = 0,
        var rebuiltAtMs: Long = 0L,
        /** Favourites at the last rebuild (feature 8 of a live play). */
        var liked: Set<Long> = emptySet(),
        /** The arm bandit and the novelty appetite (5.10), learned from the same plays as [model]. */
        var posteriors: Posteriors = Posteriors.newState(0L),
    ) {
        /** The learned weights rank, and the copy may say so: the gate has them on and the model is valid. */
        val useLearned: Boolean get() = gate.useLearned && learnVersion == LearnReplay.VERSION && model.isValid()

        fun saved(): LearnerCodec.Saved = LearnerCodec.Saved(model, gate, learnVersion, summary, rebuiltAtMs)
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loaded: Loaded? = null
    private var rebuildJob: Job? = null
    private var recordedDuringRebuild: ArrayList<PlayEvent>? = null

    /** True once the state is in memory. A caller on a hot path may use this to avoid being the first load. */
    @Volatile
    var isLoaded: Boolean = false
        private set

    /** Runs [block] with the state, off the main thread, one caller at a time. Loads the state on first use. */
    suspend fun <T> withState(block: suspend (Loaded) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(ensureLoadedLocked()) }
    }

    private fun ensureLoadedLocked(): Loaded {
        loaded?.let { return it }
        val startedNanos = System.nanoTime()
        val state = Affinity.State()
        store.loadAffinity(state)
        state.drainTouched { _, _ -> }
        val storedLearner = loadLearner()
        // A saved learner that cannot be read: the replay is due again.
        if (storedLearner == null && store.getState(KEY_LEARN_VERSION) != null) store.putState(KEY_LEARN_VERSION, null)
        // How long the first use of the brain costs (never at app start: see the class comment).
        com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics.recordTiming(
            "brain_state_load", (System.nanoTime() - startedNanos) / 1_000_000
        )
        if (store.getState(BrainStore.KEY_FIRST_USE_MS) == null) {
            store.putState(BrainStore.KEY_FIRST_USE_MS, clock.nowMs().toString())
        }
        val fresh = Loaded(state)
        if (storedLearner != null) fresh.learner = storedLearner
        loaded = fresh
        isLoaded = true
        // First use only reads the cache; a rebuild runs now only when the tables themselves are missing or stale. The replay that trains the
        // learner, the gate and the weekly facts run in the refresh worker (BrainRefreshRunner), never while Home is loading.
        requestRebuild(force = false, urgentOnly = true)
        return fresh
    }

    /**
     * One graded play into the state and the log, in one transaction: the event row and the affinity
     * rows it touched. Returns null when the event was already stored (nothing changes then). Must be
     * called inside [withState].
     */
    fun applyLocked(state: Loaded, event: PlayEvent): Reward.Graded? {
        if (store.eventIdentityAt(event.eventId) != null) return null
        var learnerReset = false
        val graded = Brain.onEvent(state.affinity, state.tracker, state.session, event) { e, g ->
            learnerReset = learnLive(state, e, g)
        }
        val rows = ArrayList<AffinityEntity>(12)
        val seen = HashSet<Pair<Int, Long>>()
        state.affinity.drainTouched { family, key ->
            if (seen.add(family to key)) BrainStore.rowOf(state.affinity, family, key)?.let(rows::add)
        }
        store.transaction {
            store.insertEvent(event)
            store.upsertAffinity(rows)
            // The learner is saved with the play it learned from: no loss and no double apply after a process death.
            if (state.learner.learnVersion == LearnReplay.VERSION) {
                store.putState(KEY_LEARNER, LearnerCodec.encode(state.learner.saved()))
                store.putState(KEY_POSTERIORS, PosteriorCodec.encode(state.learner.posteriors))
            }
        }
        recordedDuringRebuild?.add(event)
        // A value that was not finite sent the model back to its prior: replay it from the log (5.12 point 2).
        if (learnerReset || Affinity.clockJumpedBack(state.affinity, clock.nowMs())) requestRebuild(force = true)
        return graded
    }

    /** One play into the learner (5.9), before the tables change. True when the model was reset because a value was not finite. */
    private fun learnLive(state: Loaded, e: PlayEvent, g: Reward.Graded): Boolean {
        val learner = state.learner
        if (learner.learnVersion != LearnReplay.VERSION) return false // the first replay builds it
        val status = LearnReplay.applyLive(learner.model, e, g, e.rid in learner.liked, state.affinity, state.session, clock.nowMs(), learner.posteriors)
        return status == Ranker.UPDATE_RESET
    }

    private fun loadLearner(): Learner? {
        val text = store.getState(KEY_LEARNER) ?: return null
        val saved = LearnerCodec.decode(text) ?: return null
        val liked = decodeLiked(store.getState(KEY_LEARNER_LIKED))
        val posteriors = store.getState(KEY_POSTERIORS)?.let(PosteriorCodec::decode)
        // A learner saved before the arm bandit and the novelty appetite existed, or a posterior state that cannot be read: the replay is due
        // again (it makes both from the log), and until then they start from their priors.
        if (posteriors == null && store.getState(KEY_LEARN_VERSION) != null) store.putState(KEY_LEARN_VERSION, null)
        return Learner(saved.model, saved.gate, saved.summary, saved.learnVersion, saved.rebuiltAtMs, liked, posteriors ?: Posteriors.newState(saved.model.anchorDay))
    }

    /**
     * "Reset what I've learned" (8.6): the weights go back to the prior now, the gate goes off, and what the listener has played so
     * far is not learned from again (only plays after this moment are). Events, exclusions and taste changes stay. A rebuild
     * realigns the saved state; one that was already running cannot bring the old weights back.
     */
    suspend fun resetLearned() = withState { state ->
        val nowMs = clock.nowMs()
        // Event ids are (startedAtMs << 16) | sequence: this is above every play that has started so far.
        val after = (nowMs shl 16) or 0xFFFFL
        val model = Ranker.newModel(BrainMath.localDay(nowMs, 0))
        model.lastEventId = after
        val learner = Learner(model, Gate.State(), null, LearnReplay.VERSION, nowMs, state.learner.liked, Posteriors.newState(BrainMath.localDay(nowMs, 0)).also { it.lastEventId = after })
        state.learner = learner
        state.learnEpoch++
        store.transaction {
            store.putState(KEY_LEARN_AFTER, after.toString())
            store.putState(KEY_LEARNER, LearnerCodec.encode(learner.saved()))
            store.putState(KEY_POSTERIORS, PosteriorCodec.encode(learner.posteriors))
        }
        requestRebuild(force = true)
    }

    /** Asks for a rebuild in the background. Without [force] it only runs when [StateRebuilder.isNeeded]. */
    @Synchronized
    fun requestRebuild(force: Boolean, urgentOnly: Boolean = false) {
        if (rebuildJob?.isActive == true) return
        rebuildJob = scope.launch {
            try {
                val rebuilder = rebuilder.get()
                if (!force && !(if (urgentOnly) rebuilder.isUrgent() else rebuilder.isNeeded())) return@launch
                var epochAtStart = 0
                mutex.withLock {
                    recordedDuringRebuild = ArrayList()
                    epochAtStart = loaded?.learnEpoch ?: 0
                }
                val rebuilt = rebuilder.rebuild(clock.nowMs())
                mutex.withLock {
                    val live = recordedDuringRebuild.orEmpty()
                    recordedDuringRebuild = null
                    val state = loaded ?: return@withLock
                    val nowMs = clock.nowMs()
                    // The learner of this replay, unless the listener reset what was learned while it ran.
                    val replayed = rebuilt.learned
                    val adopt = replayed != null && state.learnEpoch == epochAtStart
                    val learner = if (adopt) learnerOf(replayed!!, state.learner, rebuilt.likedRids, nowMs) else null
                    // Plays that ended while the rebuild ran and that it did not read from the log.
                    val tracker = GradeTracker(rebuilt.state.completions)
                    // The session features of these plays come from a session built from them, as the replay builds it (not an empty one).
                    var catchUpSession = SessionState()
                    for (event in live) {
                        if (event.eventId in rebuilt.recentEventIds) continue
                        val novelBefore = !Affinity.isHeard(rebuilt.state, Family.TRACK, event.rid)
                        val graded = tracker.grade(event)
                        if (Session.isExpired(catchUpSession, event.startedAtMs)) catchUpSession = SessionState()
                        Session.begin(catchUpSession, event.startedAtMs, event.startReason)
                        if (learner != null) {
                            LearnReplay.applyLive(learner.model, event, graded, event.rid in learner.liked, rebuilt.state, catchUpSession, nowMs, learner.posteriors)
                        }
                        Affinity.applyEvent(rebuilt.state, event, graded, event.startedAtMs)
                        Session.apply(catchUpSession, event, graded, novelBefore)
                    }
                    rebuilt.state.drainTouched { _, _ -> }
                    store.transaction {
                        store.replaceAffinity(BrainStore.allRows(rebuilt.state))
                        for ((key, value) in rebuilt.stateValues) store.putState(key, value)
                        if (learner != null) {
                            store.putState(KEY_LEARNER, LearnerCodec.encode(learner.saved()))
                            store.putState(KEY_POSTERIORS, PosteriorCodec.encode(learner.posteriors))
                            store.putState(KEY_LEARNER_LIKED, encodeLiked(learner.liked))
                        }
                    }
                    state.affinity = rebuilt.state
                    state.tracker = tracker
                    if (learner != null) state.learner = learner
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordedDuringRebuild = null
                Timber.w(e, "brain: rebuilding the state failed; the stored state keeps serving")
            }
        }
    }

    /** The learner a finished replay gives: its model, the gate's new decision on its predictions, the figures Your sound shows. */
    private fun learnerOf(result: LearnReplay.Result, previous: Learner, liked: Set<Long>, nowMs: Long): Learner {
        val summary = Gate.summarize(result.predictions, nowMs)
        val gate = Gate.decide(previous.gate, summary, nowMs)
        return Learner(result.model, gate, summary, LearnReplay.VERSION, nowMs, liked, result.posteriors)
    }

    /** Waits for a rebuild that is running. For tests and for the debug screen; nothing in the app blocks on it. */
    suspend fun awaitRebuild() {
        rebuildJob?.join()
    }

    companion object {
        /** The learner's saved state ([LearnerCodec]); a row of `brain_state`, no table changed. */
        const val KEY_LEARNER = "learner_v1"

        /** The arm bandit and the novelty appetite ([PosteriorCodec]); a row of `brain_state`, no table changed. */
        const val KEY_POSTERIORS = "posteriors_v1"

        /** The favourites the last replay used, base 36, comma separated. */
        /** [LearnReplay.VERSION] of the last replay; absent or different means the learner is rebuilt. */
        const val KEY_LEARN_VERSION = "learn_version"

        const val KEY_LEARNER_LIKED = "learner_liked_v1"

        /** The event id at or below which plays are not learned from (set by "Reset what I've learned"). */
        const val KEY_LEARN_AFTER = "learn_after_event_id"

        private const val MAX_LIKED = 20_000

        fun encodeLiked(liked: Set<Long>): String {
            val sb = StringBuilder()
            var n = 0
            for (rid in liked.sorted()) {
                if (n++ >= MAX_LIKED) {
                    // Loud, not silent: after a restart the live path sees a smaller set than the replay used until the next rebuild.
                    Timber.w("brain: %d favourites, only the first %d are kept for the live learner until the next rebuild", liked.size, MAX_LIKED)
                    break
                }
                if (sb.isNotEmpty()) sb.append(',')
                sb.append(java.lang.Long.toString(rid, 36))
            }
            return sb.toString()
        }

        fun decodeLiked(text: String?): Set<Long> {
            if (text.isNullOrEmpty()) return emptySet()
            val out = HashSet<Long>()
            for (part in text.split(',')) part.toLongOrNull(36)?.let(out::add)
            return out
        }
    }
}
