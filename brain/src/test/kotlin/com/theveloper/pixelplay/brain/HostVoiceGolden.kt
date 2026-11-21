package com.theveloper.pixelplay.brain

/**
 * The golden vectors of the spoken host (`hostvoice.jsonl`): [HostVoice.decide], [HostVoice.cancelReason], [HostVoice.ramp] and
 * [HostVoice.line]. A new file only: no earlier golden file changes.
 */
object HostVoiceGolden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["hostvoice.jsonl"] = decisions() + flaws() + cancels() + ramps() + lines()
        return files
    }

    private fun line(fn: String, input: BrainJson.Writer.() -> Unit, output: BrainJson.Writer.() -> Unit): String {
        val w = BrainJson.Writer().beginObject()
        w.string("fn", fn)
        w.beginObject("in"); w.input(); w.endObject()
        w.beginObject("out"); w.output(); w.endObject()
        return w.endObject().toString() + "\n"
    }

    private fun BrainHash.Rng.int(n: Int): Int {
        val v = (uniform() * n).toInt()
        return if (v >= n) n - 1 else v
    }

    private fun BrainJson.Writer.b(name: String, v: Boolean) = int(name, if (v) 1 else 0)

    private fun decisions(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:hostvoice:decide"))
        val lines = listOf("", "From your Bedroom pop side.", "Released in 2019.", "New from Mono, an artist you play.", "A step sideways, from Shoegaze to V-pop.")
        for (c in 0 until 400) {
            // Most vectors are on the happy path with one thing wrong, so every reason is reached.
            val good = c % 3 != 0
            fun flag(p: Int) = if (good) false else rng.int(p) == 0
            val text = if (good && c % 7 != 0) lines[1 + rng.int(lines.size - 1)] else lines[rng.int(lines.size)]
            val i = HostVoice.Inputs(
                enabled = !flag(8),
                endless = !flag(8),
                notesOn = !flag(8),
                line = text,
                sameAsLast = flag(8),
                tracksSinceLast = if (flag(5)) rng.int(HostVoice.MIN_TRACKS_BETWEEN) else if (rng.int(4) == 0) HostVoice.NEVER else HostVoice.MIN_TRACKS_BETWEEN + rng.int(9),
                sinceStartMs = if (flag(6)) 14_000L + rng.int(6_000) else rng.int(4_000).toLong(),
                currentIsTarget = !flag(6),
                transitionRunning = flag(6),
                playing = !flag(8),
                inCall = flag(10),
                msSinceRoute = if (flag(6)) rng.int(HostVoice.ROUTE_QUIET_MS.toInt()).toLong() else 60_000L + rng.int(1_000),
                positionMs = if (flag(4)) rng.int(HostVoice.MIN_INTO_TRACK_MS.toInt()).toLong() else 1_500L + rng.int(8_000),
                remainingMs = if (flag(6)) -1L + rng.int(20_000) else 20_000L + rng.int(200_000),
                voice = if (good) HostVoice.VOICE_READY else rng.int(3),
                canDuck = !flag(6),
            )
            val d = HostVoice.decide(i)
            sb.append(line("decide", {
                b("enabled", i.enabled); b("endless", i.endless); b("notesOn", i.notesOn); string("line", i.line); b("sameAsLast", i.sameAsLast)
                int("tracksSinceLast", i.tracksSinceLast); long("sinceStartMs", i.sinceStartMs); b("currentIsTarget", i.currentIsTarget)
                b("transitionRunning", i.transitionRunning); b("playing", i.playing); b("inCall", i.inCall); long("msSinceRoute", i.msSinceRoute)
                long("positionMs", i.positionMs); long("remainingMs", i.remainingMs); int("voice", i.voice); b("canDuck", i.canDuck)
            }, {
                int("action", d.action); string("reason", d.reason); long("waitMs", d.waitMs)
            }))
        }
        return sb.toString()
    }

    /** One calm moment, and the same moment with exactly one thing wrong, so every reason of [HostVoice.decide] is in the file. */
    private fun flaws(): String {
        val sb = StringBuilder()
        val note = "From your Bedroom pop side."
        fun calm(
            enabled: Boolean = true, endless: Boolean = true, notesOn: Boolean = true, line: String = note, sameAsLast: Boolean = false,
            tracksSinceLast: Int = HostVoice.NEVER, sinceStartMs: Long = 2_000L, currentIsTarget: Boolean = true, transitionRunning: Boolean = false,
            playing: Boolean = true, inCall: Boolean = false, msSinceRoute: Long = 60_000L, positionMs: Long = 2_000L, remainingMs: Long = 120_000L,
            voice: Int = HostVoice.VOICE_READY, canDuck: Boolean = true,
        ) = HostVoice.Inputs(
            enabled, endless, notesOn, line, sameAsLast, tracksSinceLast, sinceStartMs, currentIsTarget, transitionRunning, playing, inCall, msSinceRoute,
            positionMs, remainingMs, voice, canDuck,
        )
        val need = HostVoice.estimateMs(note) + HostVoice.TRANSITION_MARGIN_MS
        val cases = listOf(
            calm(), calm(enabled = false), calm(endless = false), calm(notesOn = false), calm(line = ""), calm(sameAsLast = true),
            calm(tracksSinceLast = 0), calm(tracksSinceLast = 3), calm(tracksSinceLast = 4), calm(inCall = true), calm(playing = false),
            calm(msSinceRoute = 0L), calm(msSinceRoute = 3_999L), calm(msSinceRoute = 4_000L), calm(sinceStartMs = 15_000L), calm(sinceStartMs = 15_001L),
            calm(transitionRunning = true), calm(currentIsTarget = false), calm(positionMs = 0L), calm(positionMs = 1_499L), calm(positionMs = 1_500L),
            calm(remainingMs = -1L), calm(remainingMs = need - 1), calm(remainingMs = need), calm(voice = HostVoice.VOICE_NONE),
            calm(voice = HostVoice.VOICE_PENDING), calm(canDuck = false), calm(inCall = true, transitionRunning = true, positionMs = 0L),
            calm(transitionRunning = true, sinceStartMs = 20_000L), calm(line = "Released in 2019.", remainingMs = HostVoice.estimateMs("Released in 2019.") + HostVoice.TRANSITION_MARGIN_MS),
        )
        for (i in cases) {
            val d = HostVoice.decide(i)
            sb.append(line("decide", {
                b("enabled", i.enabled); b("endless", i.endless); b("notesOn", i.notesOn); string("line", i.line); b("sameAsLast", i.sameAsLast)
                int("tracksSinceLast", i.tracksSinceLast); long("sinceStartMs", i.sinceStartMs); b("currentIsTarget", i.currentIsTarget)
                b("transitionRunning", i.transitionRunning); b("playing", i.playing); b("inCall", i.inCall); long("msSinceRoute", i.msSinceRoute)
                long("positionMs", i.positionMs); long("remainingMs", i.remainingMs); int("voice", i.voice); b("canDuck", i.canDuck)
            }, {
                int("action", d.action); string("reason", d.reason); long("waitMs", d.waitMs)
            }))
        }
        return sb.toString()
    }

    private fun cancels(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:hostvoice:cancel"))
        for (c in 0 until 120) {
            val quiet = c % 4 != 0
            fun flag() = if (quiet) false else rng.int(4) == 0
            val r = HostVoice.Running(
                enabled = if (quiet) true else rng.int(8) != 0,
                endless = if (quiet) true else rng.int(8) != 0,
                notesOn = if (quiet) true else rng.int(8) != 0,
                songChanged = flag(), playing = if (quiet) true else rng.int(4) != 0, transitionRunning = flag(), focusLost = flag(),
                inCall = flag(), routeChanged = flag(), volumeTaken = flag(),
                sinceSpeechStartMs = rng.int(12_000).toLong(),
            )
            val reason = HostVoice.cancelReason(r)
            sb.append(line("cancel", {
                b("enabled", r.enabled); b("endless", r.endless); b("notesOn", r.notesOn); b("songChanged", r.songChanged); b("playing", r.playing)
                b("transitionRunning", r.transitionRunning); b("focusLost", r.focusLost); b("inCall", r.inCall); b("routeChanged", r.routeChanged)
                b("volumeTaken", r.volumeTaken); long("sinceSpeechStartMs", r.sinceSpeechStartMs)
            }, { string("reason", reason) }))
        }
        return sb.toString()
    }

    private fun ramps(): String {
        val sb = StringBuilder()
        val cases = listOf(
            intArrayOf(1000, 350, 250), intArrayOf(350, 1000, 400), intArrayOf(1000, 350, 0), intArrayOf(620, 1000, 120), intArrayOf(1000, 350, 250),
        )
        for (cs in cases) {
            val steps = listOf(-5L, 0L, 1L, 32L, 64L, 100L, 125L, 126L, 200L, 249L, 250L, 251L, 400L, 9_999L)
            for (t in steps) {
                sb.append(line("ramp", { int("from", cs[0]); int("to", cs[1]); long("elapsedMs", t); long("rampMs", cs[2].toLong()) },
                    { int("permille", HostVoice.ramp(cs[0], cs[1], t, cs[2].toLong())) }))
            }
        }
        return sb.toString()
    }

    private fun lines(): String {
        val sb = StringBuilder()
        val texts = listOf(
            "From your Bedroom pop side.", "From your V-pop & Hip hop side.", "New from Dr. Dre, an artist you play.", "", "   ", "One. Two. Three.",
            "A very very very very very very very very very very long sentence here.", "Listen to the lossless sound.", "Learn the genre.",
            "Released in 2019.", "Bedroom pop: Hushed, hazy pop made in bedrooms.", "Two words.\nAnd more.", "Say *this* and [that].", "  Spaced   out   words.  ",
            "No stop but short words only", "Mono_Mono | Mono.",
        )
        for (t in texts) sb.append(line("line", { string("text", t) }, { string("line", HostVoice.line(t)) }))
        val big = StringBuilder()
        for (k in 0 until 40) big.append("ab ")
        big.append("end.")
        sb.append(line("line", { string("text", big.toString()) }, { string("line", HostVoice.line(big.toString())) }))
        sb.append(line("estimate", { string("line", "From your Bedroom pop side.") }, { long("ms", HostVoice.estimateMs("From your Bedroom pop side.")) }))
        return sb.toString()
    }

    const val README_NOTES = """
        - `hostvoice.jsonl` (the spoken host): `decide`: `HostVoice.decide(Inputs)` for the fields in `in` (booleans are 1/0) gives `action`
          (0 speak, 1 wait, 2 skip), `reason` and `waitMs`. `cancel`: `HostVoice.cancelReason(Running)`. `ramp`: `HostVoice.ramp(from, to, elapsedMs, rampMs)` in
          thousandths. `line`: `HostVoice.line(text)` (Unicode characters are compared as UTF-16 units; a space-only difference is not allowed).
          `estimate`: `HostVoice.estimateMs(line)`.
    """
}
