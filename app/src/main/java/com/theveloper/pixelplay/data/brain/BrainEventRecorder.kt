package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainJson
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.brain.store.BrainStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** What a finished play did to the session, for whoever reacts at once (the autoplay tail). */
class Reaction(
    val event: PlayEvent,
    val graded: Reward.Graded,
    /** The title of the track that played, for "Because you liked ...". */
    val title: String,
    /** The third engine pick skipped hard in a row: the engine changes direction ([Direction]). */
    val changedDirection: Boolean,
    /** The session's anchors, newest first: what the engine seeds from when it is not turned. */
    val anchors: List<AnchorInfo>,
    /**
     * 0 while the engine follows [anchors]; otherwise the number of the turn it is on. A turn lasts
     * while the engine's own picks play: the listener's next own track ends it.
     */
    val turn: Int = 0,
    /**
     * What the engine seeds from while turned, best first. Empty with [turn] set means the session has
     * nothing left to turn to, and the pool is seeded from the library.
     */
    val turnAnchors: List<AnchorInfo> = emptyList(),
    /** The folded primary artist of the track that played; "" when unknown. */
    val artistKey: String = "",
    /** That artist is not to be queued for now (a hard skip): their picks leave the engine's tail. */
    val artistBlocked: Boolean = false,
    /** The track that played, as a radio request needs it: what a station re-seeds from (6.2). */
    val played: AnchorInfo? = null,
) {
    val liked: Boolean
        get() = event.flags and (EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED) != 0 &&
            !event.has(EventFlags.NOT_FOR_ME)

    /** Turned, with no anchor of the session to follow. */
    val needsOtherDirection: Boolean get() = turn != 0 && turnAnchors.isEmpty()
}

/**
 * Writes the event log (docs/brain-spec.md, 2.3 steps 1 to 5, 4.2).
 *
 * The tracker hands over raw facts for EVERY play, however short. Off the main thread and in order,
 * each play is resolved to a recording id, stamped with its session, appended to `play_event`
 * together with the affinity rows it touched, and applied to the session. Then [reactions] tells the
 * autoplay tail.
 *
 * Nothing here runs at app start: the first play that ends starts the worker and loads the state.
 */
@Singleton
class BrainEventRecorder @Inject constructor(
    private val engine: BrainEngine,
    private val identity: TrackResolver,
    private val catalog: TrackCatalog,
    private val store: BrainStore,
    /** The newest tracks a "Because you listened to" row may be built around (8.1). */
    private val homeAnchors: HomeAnchorStore = HomeAnchorStore(store),
    /** While a private session is on, a play is logged as excluded from the picks (8.5). */
    private val privateSession: PrivateSession? = null,
) : PlaySink {

    private sealed interface Message
    private class Finished(val play: FinishedPlay, val origin: Int = Origin.LIVE) : Message
    private class Open(val play: FinishedPlay?) : Message

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inbox = Channel<Message>(Channel.UNLIMITED)
    private val started = AtomicBoolean(false)

    private val _reactions = MutableSharedFlow<Reaction>(extraBufferCapacity = 32)
    val reactions: SharedFlow<Reaction> = _reactions.asSharedFlow()

    override fun onPlayFinished(play: FinishedPlay) = post(Finished(play))

    override fun onPlayOpen(play: FinishedPlay?) {
        // A play that began (or goes on) while the session is private is saved as excluded, so a play cut
        // off by a process death is not counted at the next start, when the session is off again.
        val saved = if (play != null && isPrivate(play.startedAtMs, System.currentTimeMillis())) {
            play.copy(flags = play.flags or EventFlags.LEARN_EXCLUDED)
        } else play
        post(Open(saved))
    }

    override fun isPrivate(startedAtMs: Long, endMs: Long): Boolean =
        privateSession?.overlaps(startedAtMs, endMs) == true

    override suspend fun isOutsideLibrary(songId: String, context: PlayContext?): Boolean {
        // Only tracks that can be from outside the library cost a lookup.
        val candidate = context != null &&
            (context.isEngineOwned || context.spotifyId?.let(LibraryTrackCatalog::isSyntheticId) == true)
        if (!candidate) return false
        return try {
            catalog.isOutsideLibrary(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun post(message: Message) {
        if (started.compareAndSet(false, true)) {
            scope.launch {
                recoverKilledPlay()
                for (next in inbox) handle(next)
            }
        }
        inbox.trySend(message)
    }

    private suspend fun handle(message: Message) {
        try {
            when (message) {
                is Finished -> record(message.play)?.let { _reactions.tryEmit(it) }
                is Open -> store.putState(BrainStore.KEY_OPEN_PLAY, message.play?.let(::encodeOpenPlay))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: recording a play failed")
        }
    }

    /** A play left open by a process that died is recorded as `killed`, with what it had heard by its last pause. */
    private suspend fun recoverKilledPlay() {
        try {
            val saved = store.getState(BrainStore.KEY_OPEN_PLAY) ?: return
            store.putState(BrainStore.KEY_OPEN_PLAY, null)
            val play = decodeOpenPlay(saved) ?: return
            record(play.copy(endReason = EndReason.KILLED))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: recovering an unfinished play failed")
        }
    }

    /** Records one play. Null when it could not be identified or was already stored. Visible for tests. */
    internal suspend fun record(play: FinishedPlay): Reaction? {
        val context = play.context
        val facts = catalog.factsFor(play.songId, context) ?: return null
        val durationMs = (if (play.durationMs > 0L) play.durationMs else facts.durationMs).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val rid = identity.resolveTrack(facts.keys, durationMs)
        if (rid == 0L) return null
        val aids = identity.resolveArtistNames(listOf(facts.primaryArtist) + facts.otherArtists.take(PlayEvent.MAX_OTHER_AIDS))
        val tag = context.tag
        var flags = play.flags
        if (context.shuffle) flags = flags or EventFlags.SHUFFLE
        if (context.repeatOne) flags = flags or EventFlags.REPEAT_ONE
        if (context.offline) flags = flags or EventFlags.OFFLINE
        // A play that overlapped a private session counts for nothing in the picks; the log still has it.
        if (isPrivate(play.startedAtMs, play.startedAtMs + play.playedMs.coerceAtLeast(0L))) {
            flags = flags or EventFlags.LEARN_EXCLUDED
        }

        return engine.withState { state ->
            var newSession = state.session.sessionId == 0L
            if (state.session.sessionId != 0L && Session.isExpired(state.session, play.startedAtMs)) {
                state.newSession(play.startedAtMs)
                newSession = true
            }
            val stamp = Session.begin(state.session, play.startedAtMs, context.startReason)
            // A session starts at the novelty share of the moment (5.5): the learned appetite while the gate has the learned weights on. A
            // control session (5.12 point 8) is ranked with the standard recipe, and that includes its share of new music.
            if (newSession) {
                state.session.nuSession =
                    if (Ranker.isControlSession(stamp.sessionId)) Session.NU_DEFAULT else state.sessionNu(play.startedAtMs)
            }
            // One session in ten is ranked with the prior and flagged (5.12 point 8). Which weights ranked a list, and whether it was a
            // control, was decided when the list was BUILT and travels with the queue entry (`brain.ctrl`, `brain.model`): it is read
            // here, never worked out at the end of the play. An entry that carries nothing (a track the listener chose, a queue saved by
            // an older build) is the prior and not a control.
            var eventFlags = flags
            if (tag?.control == true) eventFlags = eventFlags or EventFlags.CONTROL_SESSION
            // A novel slot the arm bandit drew is an exploration slot: logged, and weighted 1.5 by the grade (5.12 point 3).
            if (tag?.explore == true) eventFlags = eventFlags or EventFlags.EXPLORE_SLOT
            val event = PlayEvent(
                eventId = PlayEvent.mintId(play.startedAtMs, Origin.LIVE, facts.keyAtWrite, store::eventIdentityAt),
                origin = Origin.LIVE,
                rid = rid,
                primaryAid = aids[0],
                otherAids = aids.copyOfRange(1, aids.size),
                keyAtWrite = facts.keyAtWrite,
                startedAtMs = play.startedAtMs,
                tzOffsetMin = play.tzOffsetMin,
                playedMs = play.playedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                durationMs = durationMs,
                startReason = context.startReason,
                endReason = play.endReason,
                flags = eventFlags,
                surface = Surface.codeOf(tag?.surface),
                surfaceId = tag?.surfaceId.orEmpty().take(PlayEvent.MAX_SURFACE_ID_LENGTH),
                recId = tag?.recId ?: 0L,
                slot = tag?.slot ?: -1,
                arm = Arm.codeOf(tag?.arm),
                sessionId = stamp.sessionId,
                posInSession = stamp.posInSession,
                passiveRun = stamp.passiveRun,
                route = context.route,
                retrQ = context.retrQ,
                radioRank = context.radioRank,
                modelVersion = if (tag != null && tag.modelVersion >= 0) tag.modelVersion else 0,
            )
            val graded = engine.applyLocked(state, event) ?: return@withState null
            // A play the listener chose and heard out, or a pick they acted on, may head a Home row.
            runCatching { homeAnchors.onEvent(event, graded, facts, play.songId) }
            // Keep what a radio request needs for the tracks the session now anchors on.
            val anchors = state.session.anchors
            val anchorCount = state.session.anchorCount
            var isAnchor = false
            for (i in 0 until anchorCount) if (anchors[i] == rid) isAnchor = true
            if (isAnchor) {
                state.anchors[rid] = AnchorInfo(rid, play.songId, facts.title, facts.primaryArtist, facts.spotifyId, facts.durationMs)
            }
            if (state.anchors.size > Session.MAX_ANCHORS) {
                state.anchors.keys.retainAll { key -> (0 until anchorCount).any { anchors[it] == key } }
            }
            val sessionAnchors = anchors.copyOf(anchorCount)
            val notForMe = event.has(EventFlags.NOT_FOR_ME)
            val likedNow = !notForMe && event.flags and
                (EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED) != 0
            val h = if (notForMe) 1.0 else graded.h
            val changed = state.direction.onEvent(
                enginePick = context.isEngineOwned,
                station = event.surface == Surface.STATION,
                hardSkip = graded.c > 0.0 && h >= Session.HARD_SKIP,
                positive = graded.c > 0.0 && h == 0.0 && (graded.isCompletion || likedNow),
                anchors = sessionAnchors,
            )
            Reaction(
                event = event,
                graded = graded,
                title = facts.title,
                changedDirection = changed,
                anchors = sessionAnchors.toList().mapNotNull { state.anchors[it] },
                turn = state.direction.turn,
                turnAnchors = if (state.direction.turn == 0) emptyList()
                    else state.direction.follow(sessionAnchors).toList().mapNotNull { state.anchors[it] },
                artistKey = BrainText.fold(facts.primaryArtist),
                artistBlocked = state.session.isBlocked(event.primaryAid),
                played = AnchorInfo(rid, play.songId, facts.title, facts.primaryArtist, facts.spotifyId, facts.durationMs),
            )
        }
    }

    companion object {
        /** `play_event.modelVersion` of a pick that was ranked with the learned weights (0: the prior). */
        const val MODEL_LEARNED = 1
    }

    private fun encodeOpenPlay(play: FinishedPlay): String {
        val c = play.context
        val w = BrainJson.Writer().beginObject()
        w.string("songId", play.songId).long("startedAtMs", play.startedAtMs).long("playedMs", play.playedMs)
        w.long("durationMs", play.durationMs).int("flags", play.flags).int("tz", play.tzOffsetMin)
        w.string("title", c.title).string("artist", c.artistDisplay).string("primaryArtist", c.primaryArtist.orEmpty())
        w.string("spotifyId", c.spotifyId.orEmpty()).int("startReason", c.startReason)
        w.string("surface", c.tag?.surface.orEmpty()).string("surfaceId", c.tag?.surfaceId.orEmpty())
        w.long("recId", c.tag?.recId ?: 0L).int("slot", c.tag?.slot ?: -1).string("arm", c.tag?.arm.orEmpty())
        w.int("retrQ", c.retrQ).int("radioRank", c.radioRank).bool("shuffle", c.shuffle).bool("repeatOne", c.repeatOne)
        w.int("route", c.route)
        return w.endObject().toString()
    }

    private fun decodeOpenPlay(text: String): FinishedPlay? {
        @Suppress("UNCHECKED_CAST")
        val m = runCatching { BrainJson.parse(text) as Map<String, Any?> }.getOrNull() ?: return null
        fun str(k: String): String = m[k] as? String ?: ""
        fun long(k: String): Long = (m[k] as? String)?.toLongOrNull() ?: 0L
        fun int(k: String, d: Int = 0): Int = (m[k] as? Long)?.toInt() ?: d
        val songId = str("songId").takeIf { it.isNotBlank() } ?: return null
        val tag = BrainItemTag(
            surface = str("surface").ifEmpty { null },
            surfaceId = str("surfaceId").ifEmpty { null },
            recId = long("recId"),
            slot = int("slot", -1),
            arm = str("arm").ifEmpty { null },
        ).takeUnless { it.isEmpty }
        return FinishedPlay(
            songId = songId,
            context = PlayContext(
                title = str("title"),
                artistDisplay = str("artist"),
                primaryArtist = str("primaryArtist").ifEmpty { null },
                spotifyId = str("spotifyId").ifEmpty { null },
                startReason = int("startReason"),
                tag = tag,
                retrQ = int("retrQ", -1),
                radioRank = int("radioRank", -1),
                shuffle = m["shuffle"] == true,
                repeatOne = m["repeatOne"] == true,
                route = int("route"),
            ),
            startedAtMs = long("startedAtMs"),
            playedMs = long("playedMs"),
            durationMs = long("durationMs"),
            endReason = EndReason.KILLED,
            flags = int("flags"),
            tzOffsetMin = int("tz"),
        )
    }
}
