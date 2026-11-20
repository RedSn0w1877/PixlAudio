package com.theveloper.pixelplay.songtap

/**
 * The beam animation as a pure function of time, in integers: every value is a permille (1000 = 1.0) so
 * the Kotlin and Swift versions give the same number for the same millisecond. The UI reads one frame
 * per draw and applies it with graphicsLayer / scale and offset only; there is no animation state to keep.
 *
 * `tMs` counts from the animation start T0 (see [SongTapHandshake]). Positions are fractions of the
 * container height: 0 is in place, -1100 is clear off the top.
 *
 * Sender: the artwork lifts (scale to 920 with a shadow), shrinks to 400 and flies off the top while it
 * fades, then comes back in place. Receiver: the artwork enters from the top at scale 400, settles into
 * place and grows into a card with a small overshoot, then the text and the Undo chip appear; the card
 * rests [CARD_REST_MS] and slides away. With reduced motion there is no flight: the card cross-fades in
 * [REDUCED_FADE_MS] and the haptics stay.
 */
data class BeamFrame(
    /** The glow on the screen edge, 0..500. */
    val glow: Int,
    /** Artwork (sender) or the whole card group (receiver). */
    val scale: Int,
    val translateY: Int,
    val alpha: Int,
    /** Sender only: the lifted artwork's shadow, 0..1000. */
    val shadow: Int,
    /** Receiver only: the card behind the artwork, text, and the Undo chip. */
    val cardAlpha: Int,
    val textAlpha: Int,
    val undoAlpha: Int,
) {
    fun describe(): String = "glow=$glow scale=$scale ty=$translateY alpha=$alpha shadow=$shadow card=$cardAlpha text=$textAlpha undo=$undoAlpha"
}

/**
 * The same eight numbers as [BeamFrame], mutable, so a draw loop can ask for a frame 60 or 120 times a second
 * without allocating: [SongTapTimeline.frameInto] fills it in place.
 */
class BeamFrameBuffer {
    @JvmField var glow = 0
    @JvmField var scale = 1000
    @JvmField var translateY = 0
    @JvmField var alpha = 1000
    @JvmField var shadow = 0
    @JvmField var cardAlpha = 0
    @JvmField var textAlpha = 0
    @JvmField var undoAlpha = 0

    fun set(glow: Int, scale: Int, translateY: Int, alpha: Int, shadow: Int, cardAlpha: Int, textAlpha: Int, undoAlpha: Int): BeamFrameBuffer {
        this.glow = glow; this.scale = scale; this.translateY = translateY; this.alpha = alpha
        this.shadow = shadow; this.cardAlpha = cardAlpha; this.textAlpha = textAlpha; this.undoAlpha = undoAlpha
        return this
    }

    fun toFrame(): BeamFrame = BeamFrame(glow, scale, translateY, alpha, shadow, cardAlpha, textAlpha, undoAlpha)
}

data class TimedCue(val tMs: Long, val cue: HapticCue) {
    fun describe(): String = "$tMs:${cue.code}"
}

object SongTapTimeline {

    const val LIFT_MS = 180L
    const val FLY_MS = 420L
    const val SEND_RETURN_START_MS = 1_000L
    const val SEND_RETURN_MS = 500L
    const val SEND_TOTAL_MS = 1_500L

    const val RECEIVE_ENTER_MS = 420L
    const val RECEIVE_SETTLE_END_MS = 900L
    const val RECEIVE_TEXT_START_MS = 700L
    const val RECEIVE_UNDO_START_MS = 1_000L
    const val CARD_REST_MS = 6_000L
    const val SLIDE_AWAY_MS = 300L
    const val RECEIVE_REST_END_MS = RECEIVE_UNDO_START_MS + CARD_REST_MS
    const val RECEIVE_TOTAL_MS = RECEIVE_REST_END_MS + SLIDE_AWAY_MS

    const val REDUCED_FADE_MS = 150L

    const val OFF_TOP = -1_100
    private const val GLOW_PEAK = 500
    private const val GLOW_RISE_MS = 150L
    private const val GLOW_FALL_END_MS = 1_300L

    // ── Easing, all permille in and out ─────────────────────────────────────────────

    fun progress(tMs: Long, startMs: Long, durMs: Long): Int {
        if (tMs <= startMs) return 0
        if (tMs >= startMs + durMs) return 1000
        return ((tMs - startMs) * 1000L / durMs).toInt()
    }

    fun lerp(a: Int, b: Int, x: Int): Int = a + ((b - a).toLong() * x.toLong() / 1000L).toInt()

    fun easeOutCubic(x: Int): Int {
        val u = (1000 - x).toLong()
        return 1000 - (u * u * u / 1_000_000L).toInt()
    }

    fun easeInCubic(x: Int): Int {
        val v = x.toLong()
        return (v * v * v / 1_000_000L).toInt()
    }

    fun smooth(x: Int): Int {
        val v = x.toLong()
        return (v * v * (3000L - 2L * v) / 1_000_000L).toInt()
    }

    /** Overshoots to about 1100 near the end and settles at 1000. */
    fun easeOutBack(x: Int): Int {
        val u = (x - 1000).toLong()
        return 1000 + (2702L * u * u * u / 1_000_000_000L).toInt() + (1702L * u * u / 1_000_000L).toInt()
    }

    private fun glow(tMs: Long): Int {
        if (tMs <= 0L) return 0
        if (tMs < GLOW_RISE_MS) return lerp(0, GLOW_PEAK, easeOutCubic(progress(tMs, 0L, GLOW_RISE_MS)))
        return lerp(GLOW_PEAK, 0, smooth(progress(tMs, GLOW_RISE_MS, GLOW_FALL_END_MS - GLOW_RISE_MS)))
    }

    // ── Frames ───────────────────────────────────────────────────────────────────────

    fun frame(role: Role, tMs: Long, reducedMotion: Boolean): BeamFrame =
        frameInto(role, tMs, reducedMotion, BeamFrameBuffer()).toFrame()

    /** [frame] without the allocation: fills [out] and returns it. */
    fun frameInto(role: Role, tMs: Long, reducedMotion: Boolean, out: BeamFrameBuffer): BeamFrameBuffer {
        if (role == Role.SEND) sender(tMs, reducedMotion, out) else receiver(tMs, reducedMotion, out)
        return out
    }

    fun totalMs(role: Role, reducedMotion: Boolean): Long = when {
        reducedMotion && role == Role.SEND -> REDUCED_FADE_MS
        reducedMotion -> REDUCED_FADE_MS + CARD_REST_MS + REDUCED_FADE_MS
        role == Role.SEND -> SEND_TOTAL_MS
        else -> RECEIVE_TOTAL_MS
    }

    private fun sender(tMs: Long, reduced: Boolean, out: BeamFrameBuffer) {
        if (reduced) { out.set(0, 1000, 0, 1000, 0, 0, 0, 0); return }
        val g = glow(tMs)
        when {
            tMs <= 0L -> out.set(g, 1000, 0, 1000, 0, 0, 0, 0)
            tMs < LIFT_MS -> {
                val e = easeOutCubic(progress(tMs, 0L, LIFT_MS))
                out.set(g, lerp(1000, 920, e), 0, 1000, lerp(0, 1000, e), 0, 0, 0)
            }
            tMs < LIFT_MS + FLY_MS -> {
                val p = progress(tMs, LIFT_MS, FLY_MS)
                val fade = smooth(progress(tMs, LIFT_MS + FLY_MS / 2, FLY_MS / 2))
                out.set(g, lerp(920, 400, smooth(p)), lerp(0, OFF_TOP, easeInCubic(p)), lerp(1000, 0, fade), 1000, 0, 0, 0)
            }
            tMs < SEND_RETURN_START_MS -> out.set(g, 400, OFF_TOP, 0, 1000, 0, 0, 0)
            tMs < SEND_RETURN_START_MS + SEND_RETURN_MS -> {
                val e = easeOutCubic(progress(tMs, SEND_RETURN_START_MS, SEND_RETURN_MS))
                out.set(g, lerp(940, 1000, e), 0, lerp(0, 1000, e), 0, 0, 0, 0)
            }
            else -> out.set(g, 1000, 0, 1000, 0, 0, 0, 0)
        }
    }

    private fun receiver(tMs: Long, reduced: Boolean, out: BeamFrameBuffer) {
        if (reduced) {
            val inFade = easeOutCubic(progress(tMs, 0L, REDUCED_FADE_MS))
            val outFade = 1000 - smooth(progress(tMs, REDUCED_FADE_MS + CARD_REST_MS, REDUCED_FADE_MS))
            val a = if (tMs < REDUCED_FADE_MS + CARD_REST_MS) inFade else outFade
            out.set(0, 1000, 0, a, 0, a, a, a)
            return
        }
        val g = glow(tMs)
        if (tMs <= 0L) { out.set(g, 400, OFF_TOP, 0, 0, 0, 0, 0); return }
        val enter = progress(tMs, 0L, RECEIVE_ENTER_MS)
        val alphaIn = easeOutCubic(progress(tMs, 0L, 150L))
        val ty = lerp(OFF_TOP, 0, easeOutCubic(enter))
        val grow = progress(tMs, RECEIVE_ENTER_MS, RECEIVE_SETTLE_END_MS - RECEIVE_ENTER_MS)
        val scale = if (tMs < RECEIVE_ENTER_MS) 400 else lerp(400, 1000, easeOutBack(grow))
        val card = smooth(progress(tMs, 500L, 300L))
        val text = smooth(progress(tMs, RECEIVE_TEXT_START_MS, 300L))
        val undo = smooth(progress(tMs, RECEIVE_UNDO_START_MS, 250L))
        if (tMs <= RECEIVE_REST_END_MS) { out.set(g, scale, ty, alphaIn, 0, card, text, undo); return }
        val away = progress(tMs, RECEIVE_REST_END_MS, SLIDE_AWAY_MS)
        val slide = easeInCubic(away)
        val fade = 1000 - smooth(away)
        out.set(g, 1000, lerp(0, OFF_TOP, slide), fade, 0, card.coerceAtMost(fade), text.coerceAtMost(fade), undo.coerceAtMost(fade))
    }
}

/** The vibrations that go with the beam, as times from T0. */
object SongTapHaptics {

    fun cues(role: Role, reducedMotion: Boolean): List<TimedCue> = when {
        reducedMotion -> listOf(TimedCue(0L, HapticCue.SUCCESS))
        role == Role.SEND -> listOf(
            TimedCue(SongTapTimeline.LIFT_MS, HapticCue.CONFIRM),
            TimedCue(SongTapTimeline.SEND_RETURN_START_MS + 100L, HapticCue.SUCCESS),
        )
        else -> listOf(
            TimedCue(SongTapTimeline.RECEIVE_ENTER_MS, HapticCue.CONFIRM),
            TimedCue(SongTapTimeline.RECEIVE_SETTLE_END_MS, HapticCue.SUCCESS),
        )
    }
}
