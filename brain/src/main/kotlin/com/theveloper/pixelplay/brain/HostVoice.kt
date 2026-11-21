package com.theveloper.pixelplay.brain

/**
 * The spoken side of the host of Endless Listening (milestone E2), as pure rules: when a host note may be said aloud, what is said, how the
 * music is ducked while it is, and when a line that has started must stop. The app only reads the world (the player, the call state, the audio
 * route) and hands the numbers over; Kotlin and Swift reproduce `hostvoice.jsonl` from this one definition.
 *
 * Principles, all of them a refusal rather than an effort: a line is said only at a calm moment, never over a transition, a phone call or a
 * route change, and any doubt means silence. Every skip has a reason that the developer log shows.
 *
 * No clock and no locale here: time enters as milliseconds, text is taken apart by plain character tests.
 */
object HostVoice {
    /** A line starts at least this long into the track (the track must have settled, the previous song's tail is gone). */
    const val MIN_INTO_TRACK_MS = 1_500L

    /** After the audio route changed (headphones, Bluetooth) nothing is said for this long. */
    const val ROUTE_QUIET_MS = 4_000L

    /** At least this many other songs start between two spoken lines. A note is already at most one per five tracks. */
    const val MIN_TRACKS_BETWEEN = 4

    /** A line may still start this long after its track began; later it is dropped (it would describe a song already half over). */
    const val WINDOW_MS = 15_000L

    /** The music is brought down over this long, and up again over the next. The quick way back is for a skip or a pause. */
    const val RAMP_DOWN_MS = 250L
    const val RAMP_UP_MS = 400L
    const val RAMP_UP_QUICK_MS = 120L

    /** The level the music is held at while the host speaks, in thousandths of the level it had. */
    const val DUCK_PERMILLE = 350

    /** The level the music has when nothing is held down. */
    const val PERMILLE_FULL = 1000

    /** No line is allowed to run longer than this; the engine is stopped and the music comes back. */
    const val MAX_SPEECH_MS = 8_000L

    /** The song must still have this much left after the line, so the next transition never starts under the host's voice. */
    const val TRANSITION_MARGIN_MS = 15_000L

    /** A rough length of a spoken line: [MS_PER_WORD] a word and a breath at the start. */
    const val MS_PER_WORD = 420L
    const val LEAD_MS = 600L

    /** The developer log keeps this many decisions. */
    const val LOG_SIZE = 20

    /** What [Decision.action] says. */
    const val ACT_SPEAK = 0
    const val ACT_WAIT = 1
    const val ACT_SKIP = 2

    /** The state of the system voice. */
    const val VOICE_NONE = 0
    const val VOICE_PENDING = 1
    const val VOICE_READY = 2

    /** `tracksSinceLast` when nothing was spoken yet. */
    const val NEVER = 1_000_000

    /**
     * Everything the decision looks at, read at one moment. [line] is the cleaned text of the note of the current song ("" when it has none);
     * [sameAsLast] says it is the line already spoken for this song or the one before; [tracksSinceLast] is how many other songs started since
     * the last spoken line; [sinceStartMs] how long ago the current song started (measured by the caller); [currentIsTarget] says the engine's
     * player already plays the song the note belongs to (a transition shows the new song early); [positionMs] and [remainingMs] are the master
     * player's, [remainingMs] negative when the length is unknown; [msSinceRoute] how long ago the audio route last changed.
     */
    class Inputs(
        val enabled: Boolean,
        val endless: Boolean,
        val notesOn: Boolean,
        val line: String,
        val sameAsLast: Boolean,
        val tracksSinceLast: Int,
        val sinceStartMs: Long,
        val currentIsTarget: Boolean,
        val transitionRunning: Boolean,
        val playing: Boolean,
        val inCall: Boolean,
        val msSinceRoute: Long,
        val positionMs: Long,
        val remainingMs: Long,
        val voice: Int,
        val canDuck: Boolean,
    )

    /** [action]: say it now, ask again after [waitMs], or give up on this note for good. [reason] is a short stable word for the log. */
    class Decision(val action: Int, val reason: String, val waitMs: Long)

    private fun skip(reason: String) = Decision(ACT_SKIP, reason, 0L)

    private fun hold(reason: String, ms: Long) = Decision(ACT_WAIT, reason, if (ms < MIN_WAIT_MS) MIN_WAIT_MS else ms)

    private const val MIN_WAIT_MS = 50L

    /** The decision for one moment. Never throws. The order is the order of the reasons: the first one that holds ends it. */
    fun decide(i: Inputs): Decision {
        if (!i.enabled) return skip("off")
        if (!i.endless) return skip("not_endless")
        if (!i.notesOn) return skip("notes_off")
        if (i.line.isEmpty()) return skip("no_line")
        if (i.sameAsLast) return skip("repeat")
        if (i.tracksSinceLast < MIN_TRACKS_BETWEEN) return skip("too_soon")
        if (i.inCall) return skip("call")
        if (!i.playing) return skip("paused")
        if (i.msSinceRoute < ROUTE_QUIET_MS) return skip("route_changed")
        if (i.sinceStartMs > WINDOW_MS) return skip("window_over")
        if (i.transitionRunning) return hold("transition", 300L)
        if (!i.currentIsTarget) return hold("not_current", 300L)
        if (i.positionMs < MIN_INTO_TRACK_MS) return hold("too_early", MIN_INTO_TRACK_MS - i.positionMs)
        if (i.remainingMs < 0L) return skip("unknown_length")
        if (i.remainingMs < estimateMs(i.line) + TRANSITION_MARGIN_MS) return skip("short_left")
        if (i.voice == VOICE_NONE) return skip("no_voice")
        if (i.voice == VOICE_PENDING) return hold("voice_pending", 300L)
        if (!i.canDuck) return skip("cant_duck")
        return Decision(ACT_SPEAK, "speak", 0L)
    }

    /** What the app sees while a line is running. [sinceSpeechStartMs] counts from the first word. */
    class Running(
        val enabled: Boolean,
        val endless: Boolean,
        val notesOn: Boolean,
        val songChanged: Boolean,
        val playing: Boolean,
        val transitionRunning: Boolean,
        val focusLost: Boolean,
        val inCall: Boolean,
        val routeChanged: Boolean,
        val volumeTaken: Boolean,
        val sinceSpeechStartMs: Long,
    )

    /** "" while the line may go on, else why it must stop at once (the first reason that holds). */
    fun cancelReason(r: Running): String {
        if (!r.enabled) return "off"
        if (!r.notesOn) return "notes_off"
        if (!r.endless) return "not_endless"
        if (r.songChanged) return "skip"
        if (!r.playing) return "pause"
        if (r.transitionRunning) return "transition"
        if (r.focusLost) return "focus"
        if (r.inCall) return "call"
        if (r.routeChanged) return "route"
        if (r.volumeTaken) return "volume_taken"
        if (r.sinceSpeechStartMs > MAX_SPEECH_MS) return "too_long"
        return ""
    }

    /** Roughly how long [line] takes to say. */
    fun estimateMs(line: String): Long = HostNotes.wordCount(line).toLong() * MS_PER_WORD + LEAD_MS

    /**
     * A linear ramp in thousandths: from [fromPermille] to [toPermille] over [rampMs], [elapsedMs] in. The ends are exact (no rounding drift),
     * a ramp of no length is at its end at once.
     */
    fun ramp(fromPermille: Int, toPermille: Int, elapsedMs: Long, rampMs: Long): Int {
        if (rampMs <= 0L || elapsedMs >= rampMs) return toPermille
        if (elapsedMs <= 0L) return fromPermille
        return fromPermille + ((toPermille - fromPermille).toLong() * elapsedMs / rampMs).toInt()
    }

    /**
     * The line the host says for a note's [text], or "" when the note must stay on the screen only: at most [MAX_CHARS] characters, two
     * sentences of at most [HostNotes.MAX_WORDS] words, none of the words the app never says; marks a voice reads badly are taken out ("&" is
     * said "and"). Text is never cut: a note that is too long is not said at all.
     */
    fun line(text: String): String {
        val sb = StringBuilder(text.length + 8)
        var lastSpace = true
        for (c in text) {
            val out: String = when (c) {
                '&' -> " and "
                '"', '*', '_', '~', '|', '<', '>', '[', ']', '{', '}', '\\', '#', '·', '•' -> " "
                '\n', '\r', '\t' -> " "
                else -> c.toString()
            }
            for (o in out) {
                if (o == ' ') {
                    if (lastSpace) continue
                    lastSpace = true
                } else {
                    // A mark taken out must not leave a gap before the full stop or comma that follows it.
                    if ((o == '.' || o == ',' || o == '!' || o == '?' || o == ':' || o == ';') && sb.isNotEmpty() && sb[sb.length - 1] == ' ') {
                        sb.setLength(sb.length - 1)
                    }
                    lastSpace = false
                }
                sb.append(o)
            }
        }
        var s = sb.toString()
        if (s.endsWith(" ")) s = s.substring(0, s.length - 1)
        if (s.isEmpty() || s.length > MAX_CHARS) return ""
        return if (HostNotes.isSpeakable(s)) s else ""
    }

    const val MAX_CHARS = 160
}
