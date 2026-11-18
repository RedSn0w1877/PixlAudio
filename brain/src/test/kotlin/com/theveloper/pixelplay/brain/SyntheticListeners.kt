package com.theveloper.pixelplay.brain

/**
 * Three made-up listeners for the evaluation tests (docs/brain-spec.md, 11: "Eval on three synthetic
 * listeners in CI"). Each has a hidden finish probability per track, and a history drawn from it, so a
 * scorer that has learned who they are should beat one that has not.
 */
object SyntheticListeners {

    class Listener(
        val name: String,
        val events: List<PlayEvent>,
        val library: List<Candidate>,
        val novel: List<Candidate>,
        val seeds: List<Retrieval.SeedList>,
        val finishRate: Map<Long, Double>,
        val nowMs: Long,
    )

    private const val T0 = 1_750_000_000_000L
    private const val DAY = 86_400_000L

    private fun rid(world: String, i: Int): Long = BrainHash.fnv1a64("n:rid $world $i")
    private fun aid(world: String, i: Int): Long = BrainHash.fnv1a64("n:aid $world $i")

    /** [artistRate]: finish probability by artist index; [trackJitter]: how far a track can sit from its artist's rate. */
    private fun build(
        name: String,
        artists: Int,
        libraryTracks: Int,
        novelTracks: Int,
        events: Int,
        days: Int,
        artistRate: (Int) -> Double,
        trackJitter: Double,
        popularShare: Double,
    ): Listener {
        val rng = BrainHash.Rng(BrainHash.fnv1a64("synthetic:$name"))
        val artistOf = IntArray(libraryTracks + novelTracks) { rng.int(artists) }
        val rate = HashMap<Long, Double>()
        for (i in 0 until libraryTracks + novelTracks) {
            val r = artistRate(artistOf[i]) + (rng.uniform() - 0.5) * 2.0 * trackJitter
            rate[rid(name, i)] = BrainMath.clip(r, 0.03, 0.97)
        }
        val library = (0 until libraryTracks).map { Candidate(rid(name, it), aid(name, artistOf[it]), BrainHash.fnv1a64("alb $name ${it / 4}"), true) }
        val novel = (libraryTracks until libraryTracks + novelTracks).map {
            Candidate(rid(name, it), aid(name, artistOf[it]), BrainHash.fnv1a64("alb $name ${it / 4}"), false)
        }
        val list = ArrayList<PlayEvent>(events)
        var t = T0
        val step = (days.toLong() * DAY) / events
        var session = 1L
        var pos = 0
        for (i in 0 until events) {
            // A new session every 12 plays, or after a quiet gap.
            if (i % 12 == 0) {
                session++
                pos = 0
                t += 2L * 3_600_000L
            }
            // Mostly the tracks they like (a popular few are played again and again), sometimes anything.
            val idx = if (rng.uniform() < popularShare) rng.int(minOf(120, libraryTracks)) else rng.int(libraryTracks)
            val c = library[idx]
            val finish = rng.uniform() < (rate[c.rid] ?: 0.5)
            val duration = 150_000 + rng.int(120_000)
            val played = if (finish) duration else 3_000 + rng.int(25_000)
            t += step
            list.add(
                PlayEvent(
                    eventId = PlayEvent.mintId(t, Origin.LIVE, "n:track|${c.rid}") { null },
                    origin = Origin.LIVE, rid = c.rid, primaryAid = c.aid, keyAtWrite = "n:track|${c.rid}",
                    startedAtMs = t, tzOffsetMin = 0, playedMs = played, durationMs = duration,
                    startReason = StartReason.TAP, endReason = if (finish) EndReason.TRACK_DONE else EndReason.NEXT,
                    surface = Surface.PLAYLIST, sessionId = session, posInSession = pos, route = Route.SPEAKER,
                )
            )
            pos++
            t += played.toLong()
        }
        list.sortWith { a, b -> a.eventId.compareTo(b.eventId) }
        val seedTracks = library.take(3)
        val seeds = seedTracks.mapIndexed { s, seed ->
            val rids = LinkedHashSet<Long>()
            while (rids.size < 40) {
                val pick = (library + novel)[rng.int(library.size + novel.size)]
                if (pick.rid != seed.rid) rids.add(pick.rid)
            }
            Retrieval.SeedList(seed.rid, seed.aid, rids.toLongArray(), if (s == 2) Graph.RELATED else Graph.RADIO)
        }
        return Listener(name, list, library, novel, seeds, rate, t)
    }

    private fun BrainHash.Rng.int(n: Int): Int = BrainMath.floorToLong(uniform() * n.toDouble()).toInt().coerceIn(0, n - 1)

    /** Twenty-five loved artists out of 80; everything else is skipped most of the time. */
    fun loyal(): Listener = build("loyal", 80, 1500, 1000, 2000, 120, { if (it < 25) 0.9 else 0.22 }, 0.05, 0.5)

    /** Many artists, each with its own taste level. */
    fun explorer(): Listener {
        val levels = BrainHash.Rng(BrainHash.fnv1a64("synthetic:explorer:levels"))
        val table = DoubleArray(120) { 0.15 + 0.75 * levels.uniform() }
        return build("explorer", 120, 2000, 1200, 2500, 150, { table[it] }, 0.15, 0.25)
    }

    /** Few artists, and the track matters more than the artist. */
    fun repeater(): Listener = build("repeater", 40, 800, 600, 1800, 90, { 0.5 }, 0.45, 0.7)

    fun all(): List<Listener> = listOf(loyal(), explorer(), repeater())
}
