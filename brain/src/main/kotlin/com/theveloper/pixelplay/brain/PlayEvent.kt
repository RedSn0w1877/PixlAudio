package com.theveloper.pixelplay.brain

/** Where a play row came from (4.1 `origin`). Codes are part of the event log: never renumber. */
object Origin {
    const val LIVE = 0
    const val IMPORT_SPOTIFY = 1
    const val IMPORT_YTM = 2
    const val MIGRATED_HISTORY = 3

    private val NAMES = arrayOf("live", "importSpotify", "importYtm", "migratedHistory")

    fun nameOf(code: Int): String = if (code >= 0 && code < NAMES.size) NAMES[code] else NAMES[LIVE]

    fun isImport(code: Int): Boolean = code == IMPORT_SPOTIFY || code == IMPORT_YTM
}

/** How a play started (4.1 `startReason`). Codes are part of the event log: never renumber. */
object StartReason {
    const val TAP = 0
    const val NEXT = 1
    const val PREV = 2
    const val AUTO_ADVANCE = 3
    const val AUTOPLAY = 4
    const val RESTORE = 5
    const val REMOTE = 6
    const val UNKNOWN = 7

    private val NAMES = arrayOf("tap", "next", "prev", "autoAdvance", "autoplay", "restore", "remote", "unknown")

    fun nameOf(code: Int): String = if (code >= 0 && code < NAMES.size) NAMES[code] else NAMES[UNKNOWN]

    /** True when the listener did something to start this play (it breaks a passive run). */
    fun isTouch(code: Int): Boolean = code == TAP || code == NEXT || code == PREV || code == REMOTE
}

/** How a play ended (4.1 `endReason`). Codes are part of the event log: never renumber. */
object EndReason {
    const val TRACK_DONE = 0
    const val NEXT = 1
    const val PREV = 2
    const val PICKED_OTHER = 3
    const val STOPPED = 4
    const val QUEUE_EDIT = 5
    const val ERROR = 6
    const val KILLED = 7
    const val UNKNOWN = 8

    private val NAMES = arrayOf("trackDone", "next", "prev", "pickedOther", "stopped", "queueEdit", "error", "killed", "unknown")

    fun nameOf(code: Int): String = if (code >= 0 && code < NAMES.size) NAMES[code] else NAMES[UNKNOWN]
}

/** The audio route of a play (4.1 `route`). Codes are part of the event log: never renumber. */
object Route {
    const val SPEAKER = 0
    const val WIRED = 1
    const val BLUETOOTH = 2
    const val CAR = 3
    const val CAST = 4
    const val CONNECT = 5
    const val UNKNOWN = 6
}

/** The bits of `play_event.flags` (4.1). Part of the event log: never move a bit. */
object EventFlags {
    const val SHUFFLE = 1
    const val REPEAT_ONE = 1 shl 1
    const val OFFLINE = 1 shl 2
    const val NEVER_AUDIBLE = 1 shl 3
    const val EXPLORE_SLOT = 1 shl 4
    const val CONTROL_SESSION = 1 shl 5
    const val LIKED_DURING = 1 shl 6
    const val SAVED_DURING = 1 shl 7
    const val ADDED_TO_PLAYLIST = 1 shl 8
    const val SEEK_BACK = 1 shl 9
    const val REPLAYED = 1 shl 10
    const val NOT_FOR_ME = 1 shl 11
    const val LEARN_EXCLUDED = 1 shl 12
}

/**
 * One play attempt, live or imported (docs/brain-spec.md, 4.1). Raw facts only: reward and trust
 * are derived by [Reward.grade], so their definition can change and be replayed.
 */
class PlayEvent(
    /** `(startedAtMs << 16) | seq`; time-ordered; see [mintId]. */
    val eventId: Long,
    val origin: Int,
    val rid: Long,
    val primaryAid: Long,
    /** The other credited artists, at most [MAX_OTHER_AIDS]. */
    val otherAids: LongArray = NO_AIDS,
    /** The key that was resolved, for audit and re-resolution. */
    val keyAtWrite: String = "",
    /** UTC. For imported and migrated rows: the end time minus what was heard. */
    val startedAtMs: Long,
    /** Minutes east of UTC at play time; [TZ_UNKNOWN] for imports. */
    val tzOffsetMin: Int = TZ_UNKNOWN,
    /** Audible milliseconds; -1 when unknown (an imported play without `ms_played`). */
    val playedMs: Int,
    /** 0 when unknown. */
    val durationMs: Int = 0,
    val startReason: Int = StartReason.UNKNOWN,
    val endReason: Int = EndReason.UNKNOWN,
    val flags: Int = 0,
    val surface: Int = Surface.UNKNOWN,
    val surfaceId: String = "",
    val recId: Long = 0L,
    val slot: Int = -1,
    val arm: Int = Arm.NONE,
    val sessionId: Long = 0L,
    val posInSession: Int = 0,
    val passiveRun: Int = 0,
    val route: Int = Route.UNKNOWN,
    val retrQ: Int = -1,
    val radioRank: Int = -1,
    val pServedQ: Int = 0,
    val modelVersion: Int = 0,
    val schema: Int = SCHEMA,
) {
    fun has(flag: Int): Boolean = flags and flag != 0

    /** The time the play ended, as far as the row knows: start plus what was heard. */
    val endedAtMs: Long get() = startedAtMs + (if (playedMs > 0) playedMs.toLong() else 0L)

    /** One canonical JSON line (no newline). Keys in the order of the table in 4.1. */
    fun encode(): String {
        val w = BrainJson.Writer().beginObject()
        w.long("eventId", eventId)
        w.int("schema", schema)
        w.int("origin", origin)
        w.long("rid", rid)
        w.long("primaryAid", primaryAid)
        w.longs("otherAids", otherAids)
        w.string("keyAtWrite", keyAtWrite)
        w.long("startedAtMs", startedAtMs)
        if (tzOffsetMin == TZ_UNKNOWN) w.nullValue("tzOffsetMin") else w.int("tzOffsetMin", tzOffsetMin)
        w.int("playedMs", playedMs)
        w.int("durationMs", durationMs)
        w.int("startReason", startReason)
        w.int("endReason", endReason)
        w.int("flags", flags)
        w.int("surface", surface)
        w.string("surfaceId", surfaceId)
        w.long("recId", recId)
        w.int("slot", slot)
        w.int("arm", arm)
        w.long("sessionId", sessionId)
        w.int("posInSession", posInSession)
        w.int("passiveRun", passiveRun)
        w.int("route", route)
        w.int("retrQ", retrQ)
        w.int("radioRank", radioRank)
        w.int("pServedQ", pServedQ)
        w.int("modelVersion", modelVersion)
        return w.endObject().toString()
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_OTHER_AIDS = 4
        const val MAX_SURFACE_ID_LENGTH = 40

        /** `tzOffsetMin` of a row that does not know its offset. Never a real offset (those are within +-900). */
        const val TZ_UNKNOWN = -32768

        val NO_AIDS = LongArray(0)

        /** Decodes [encode]'s output. A field a later schema added is ignored; a missing one takes its default. */
        fun decode(line: String): PlayEvent {
            @Suppress("UNCHECKED_CAST")
            val m = BrainJson.parse(line) as Map<String, Any?>
            fun long(k: String, d: Long = 0L): Long = when (val v = m[k]) {
                is String -> v.toLong()
                is Long -> v
                else -> d
            }
            fun int(k: String, d: Int = 0): Int = (m[k] as? Long)?.toInt() ?: d
            val others = (m["otherAids"] as? List<*>).orEmpty()
            val aids = LongArray(others.size) { i -> (others[i] as String).toLong() }
            return PlayEvent(
                eventId = long("eventId"),
                schema = int("schema", SCHEMA),
                origin = int("origin"),
                rid = long("rid"),
                primaryAid = long("primaryAid"),
                otherAids = aids,
                keyAtWrite = m["keyAtWrite"] as? String ?: "",
                startedAtMs = long("startedAtMs"),
                tzOffsetMin = int("tzOffsetMin", TZ_UNKNOWN),
                playedMs = int("playedMs", -1),
                durationMs = int("durationMs"),
                startReason = int("startReason", StartReason.UNKNOWN),
                endReason = int("endReason", EndReason.UNKNOWN),
                flags = int("flags"),
                surface = int("surface", Surface.UNKNOWN),
                surfaceId = m["surfaceId"] as? String ?: "",
                recId = long("recId"),
                slot = int("slot", -1),
                arm = int("arm", Arm.NONE),
                sessionId = long("sessionId"),
                posInSession = int("posInSession"),
                passiveRun = int("passiveRun"),
                route = int("route", Route.UNKNOWN),
                retrQ = int("retrQ", -1),
                radioRank = int("radioRank", -1),
                pServedQ = int("pServedQ"),
                modelVersion = int("modelVersion"),
            )
        }

        /** What makes two rows the same play: the origin and the key that was resolved. */
        fun identityOf(origin: Int, keyAtWrite: String): String = Origin.nameOf(origin) + "|" + keyAtWrite

        /**
         * The event id of a play (4.1): `(startedAtMs << 16) | seq`, with `seq` the low 16 bits of
         * `fnv1a64(utf8(origin + "|" + keyAtWrite))`. Deterministic, so copying the same legacy row
         * twice gives the same id. [identityAt] returns the identity ([identityOf]) of the row already
         * stored under an id, or null when the id is free; an id taken by a DIFFERENT play moves on to
         * `seq + 1` modulo 65,536.
         */
        fun mintId(startedAtMs: Long, origin: Int, keyAtWrite: String, identityAt: (Long) -> String?): Long {
            val identity = identityOf(origin, keyAtWrite)
            val base = startedAtMs shl 16
            var seq = (BrainHash.fnv1a64(identity) and 0xFFFFL).toInt()
            var tries = 0
            while (tries < 65536) {
                val id = base or seq.toLong()
                val taken = identityAt(id)
                if (taken == null || sameText(taken, identity)) return id
                seq = (seq + 1) and 0xFFFF
                tries++
            }
            return base or seq.toLong()
        }

        // Strings are never compared as strings in the core (10.3, rule 13): UTF-16 units, one by one.
        private fun sameText(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            for (i in a.indices) if (a[i].code != b[i].code) return false
            return true
        }
    }
}
