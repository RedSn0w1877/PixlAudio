package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainJson
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
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
    /** The third hard skip in a row: the engine changes direction. */
    val changedDirection: Boolean,
    /**
     * What the engine seeds from now, best first: the session's anchors, or after a change of
     * direction only the anchor it turned to. Empty when the session has none yet.
     */
    val anchors: List<AnchorInfo>,
    /** The engine changed direction but the session has no other anchor to turn to. */
    val needsOtherDirection: Boolean,
    /** The session has changed direction at least once: [anchors] is the one anchor it follows. */
    val turned: Boolean,
) {
    val liked: Boolean
        get() = event.flags and (EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED) != 0 &&
            !event.has(EventFlags.NOT_FOR_ME)
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

    override fun onPlayOpen(play: FinishedPlay?) = post(Open(play))

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

        return engine.withState { state ->
            if (state.session.sessionId != 0L && Session.isExpired(state.session, play.startedAtMs)) state.newSession()
            val stamp = Session.begin(state.session, play.startedAtMs, context.startReason)
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
                flags = flags,
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
            )
            val directionBefore = state.session.reseedCount
            val graded = engine.applyLocked(state, event) ?: return@withState null
            // Keep what a radio request needs for the tracks the session now anchors on.
            val anchors = state.session.anchors
            var isAnchor = false
            for (i in 0 until state.session.anchorCount) if (anchors[i] == rid) isAnchor = true
            if (isAnchor) {
                state.anchors[rid] = AnchorInfo(rid, play.songId, facts.title, facts.primaryArtist, facts.spotifyId, facts.durationMs)
            }
            if (state.anchors.size > Session.MAX_ANCHORS) {
                state.anchors.keys.retainAll { key -> (0 until state.session.anchorCount).any { anchors[it] == key } }
            }
            val changed = state.session.reseedCount != directionBefore
            val order = Session.seedOrder(state.session).toList().mapNotNull { state.anchors[it] }
            Reaction(
                event = event,
                graded = graded,
                title = facts.title,
                changedDirection = changed,
                // After a change of direction the engine follows ONE anchor, the one it turned to:
                // the same set of anchors in another order would be the same pool again.
                anchors = if (state.session.reseedCount == 0) order else order.take(1),
                needsOtherDirection = state.session.reseedCount > 0 && state.session.anchorCount <= 1,
                turned = state.session.reseedCount > 0,
            )
        }
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
