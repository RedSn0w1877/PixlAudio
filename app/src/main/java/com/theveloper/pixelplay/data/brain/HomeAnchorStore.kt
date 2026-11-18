package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.data.brain.store.BrainStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A track a "Because you listened to" row can be built around, with what a radio request needs. */
@Serializable
data class HomeAnchor(
    val rid: Long,
    val aid: Long,
    val mediaId: String,
    val title: String,
    /** ONE artist name. */
    val primaryArtist: String,
    val spotifyId: String? = null,
    val durationMs: Long = 0L,
    val heardAtMs: Long,
)

/**
 * The newest tracks a Home row may be built around (docs/brain-spec.md, 8.1): a play on a surface the
 * listener picked from that they heard out (`r >= 0.8`), or an engine pick they liked, saved or
 * replayed. Never a track that merely played through on autoplay.
 *
 * `play_event` is the source of truth, but an event holds ids, not a title or a media id, and the
 * in-memory session is gone by the time Home is opened. So the recorder writes this short list beside
 * the event, in `brain_state`: a cache of the log's last few qualifying rows, [MAX] at most.
 */
@Singleton
class HomeAnchorStore @Inject constructor(
    private val store: BrainStore,
) {
    /** Called by the recorder, on its worker, right after the event was written. */
    fun onEvent(event: PlayEvent, graded: Reward.Graded, facts: TrackFacts, mediaId: String) {
        if (!qualifies(event, graded)) return
        val anchor = HomeAnchor(
            rid = event.rid,
            aid = event.primaryAid,
            mediaId = mediaId,
            title = facts.title,
            primaryArtist = facts.primaryArtist,
            spotifyId = facts.spotifyId,
            durationMs = facts.durationMs,
            heardAtMs = event.startedAtMs + event.playedMs.coerceAtLeast(0),
        )
        store.putState(KEY, encode(push(decode(store.getState(KEY)), anchor)))
    }

    /** Newest first; blocking, off the main thread. */
    fun read(): List<HomeAnchor> = decode(store.getState(KEY))

    companion object {
        const val KEY = "home_anchors_v1"
        const val MAX = 8
        const val MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(HomeAnchor.serializer())

        private const val ACTED_ON =
            EventFlags.LIKED_DURING or EventFlags.SAVED_DURING or EventFlags.ADDED_TO_PLAYLIST or EventFlags.REPLAYED

        fun qualifies(event: PlayEvent, graded: Reward.Graded): Boolean {
            if (event.origin != Origin.LIVE || event.rid == 0L) return false
            if (event.has(EventFlags.NOT_FOR_ME) || event.has(EventFlags.NEVER_AUDIBLE)) return false
            return if (event.arm == Arm.NONE) graded.r >= Reward.COMPLETION_R else event.flags and ACTED_ON != 0
        }

        /** [anchor] first; an older entry of the same recording goes; [MAX] at most. */
        fun push(current: List<HomeAnchor>, anchor: HomeAnchor): List<HomeAnchor> {
            val out = ArrayList<HomeAnchor>(MAX)
            out.add(anchor)
            for (old in current) {
                if (out.size >= MAX) break
                if (old.rid != anchor.rid) out.add(old)
            }
            return out
        }

        /**
         * The anchors a Home page shows rows for: the newest ones, by different artists, heard within
         * the last seven days; [count] at most.
         */
        fun pick(anchors: List<HomeAnchor>, nowMs: Long, count: Int): List<HomeAnchor> {
            val out = ArrayList<HomeAnchor>(count)
            for (anchor in anchors) {
                if (out.size >= count) break
                if (nowMs - anchor.heardAtMs !in 0 until MAX_AGE_MS) continue
                val sameArtist = out.any {
                    (anchor.aid != 0L && it.aid == anchor.aid) || it.primaryArtist.equals(anchor.primaryArtist, ignoreCase = true)
                }
                if (!sameArtist) out.add(anchor)
            }
            return out
        }

        fun encode(anchors: List<HomeAnchor>): String = json.encodeToString(serializer, anchors)

        fun decode(text: String?): List<HomeAnchor> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
        }
    }
}
