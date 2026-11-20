package com.theveloper.pixelplay.songtap

/** Why a beam was not accepted, when the limits say no. */
enum class LimitVerdict(val code: String) {
    ALLOWED("allowed"),
    TOO_SOON("too_soon"),
    MINUTE_LIMIT("minute_limit"),
    HOUR_LIMIT("hour_limit"),
    DUPLICATE("duplicate"),
    MUTED("muted"),
}

/**
 * What a receiver lets in: one accepted beam per 3 seconds, 10 a minute, 30 an hour, the same video id
 * not twice within 60 seconds, and a sender that was undone twice is muted for an hour.
 *
 * An immutable value: [admit] only reads, [accepted] and [undone] return the next value. Held in memory
 * for the process only.
 */
class RateLimits private constructor(
    private val acceptedAt: List<Long>,
    private val recentVideos: List<Pair<String, Long>>,
    private val undoCounts: List<Pair<Int, Int>>,
    private val mutedUntil: List<Pair<Int, Long>>,
) {
    constructor() : this(emptyList(), emptyList(), emptyList(), emptyList())

    fun admit(nowMs: Long, videoId: String, senderKey: Int): LimitVerdict {
        for ((key, until) in mutedUntil) if (key == senderKey && until > nowMs) return LimitVerdict.MUTED
        if (acceptedAt.isNotEmpty() && nowMs - acceptedAt.last() < MIN_GAP_MS) return LimitVerdict.TOO_SOON
        for ((id, at) in recentVideos) if (id == videoId && nowMs - at < SAME_SONG_MS) return LimitVerdict.DUPLICATE
        var inMinute = 0
        var inHour = 0
        for (at in acceptedAt) {
            if (nowMs - at < MINUTE_MS) inMinute++
            if (nowMs - at < HOUR_MS) inHour++
        }
        if (inMinute >= PER_MINUTE) return LimitVerdict.MINUTE_LIMIT
        if (inHour >= PER_HOUR) return LimitVerdict.HOUR_LIMIT
        return LimitVerdict.ALLOWED
    }

    /** A beam of [videoId] was accepted at [nowMs]. */
    fun accepted(nowMs: Long, videoId: String): RateLimits {
        val times = (acceptedAt.filter { nowMs - it < HOUR_MS } + nowMs).takeLast(PER_HOUR)
        val videos = (recentVideos.filter { it.first != videoId && nowMs - it.second < SAME_SONG_MS } + (videoId to nowMs))
        return RateLimits(times, videos, undoCounts, mutedUntil.filter { it.second > nowMs })
    }

    /** The listener undid a beam from [senderKey]; the second undo mutes that sender for an hour. */
    fun undone(nowMs: Long, senderKey: Int): RateLimits {
        val before = undoCounts.firstOrNull { it.first == senderKey }?.second ?: 0
        val count = before + 1
        val others = undoCounts.filter { it.first != senderKey }
        return if (count >= UNDOS_TO_MUTE) {
            RateLimits(acceptedAt, recentVideos, others, mutedUntil.filter { it.first != senderKey } + (senderKey to nowMs + MUTE_MS))
        } else {
            RateLimits(acceptedAt, recentVideos, others + (senderKey to count), mutedUntil)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RateLimits && acceptedAt == other.acceptedAt && recentVideos == other.recentVideos &&
            undoCounts == other.undoCounts && mutedUntil == other.mutedUntil

    override fun hashCode(): Int = acceptedAt.size * 31 + mutedUntil.size

    companion object {
        const val MIN_GAP_MS = 3_000L
        const val PER_MINUTE = 10
        const val PER_HOUR = 30
        const val SAME_SONG_MS = 60_000L
        const val UNDOS_TO_MUTE = 2
        const val MUTE_MS = 3_600_000L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
    }
}
