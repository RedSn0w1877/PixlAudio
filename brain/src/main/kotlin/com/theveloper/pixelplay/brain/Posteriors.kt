package com.theveloper.pixelplay.brain

/**
 * The learned state that is not the ranker's weights (docs/brain-spec.md, 5.10, 5.13): the arm bandit and the novelty appetite. One watermark,
 * one entry point ([onPlay]) for the live path and the replay, one strict codec. A cache of the event log, like the ranker: a replay makes it
 * again, and "Reset what I've learned" puts it back to its priors.
 */
class Posteriors(
    val arms: ArmBandit.State,
    val appetite: NoveltyAppetite.State,
    /** The id of the newest event applied; an event at or below it is never applied again. */
    var lastEventId: Long = Long.MIN_VALUE,
) {
    fun copy(): Posteriors = Posteriors(arms.copy(), appetite.copy(), lastEventId)

    fun isValid(): Boolean = arms.isValid() && appetite.isValid()

    companion object {
        fun newState(today: Long): Posteriors = Posteriors(ArmBandit.newState(today), NoveltyAppetite.newState(today))
    }
}

object PosteriorUpdate {
    /**
     * One graded play: the arm bandit (when it came from a recommender arm on a surface the bandit knows), then the novelty appetite. [novel]
     * is true when it was the first play of the track (computed from the tables BEFORE the play is applied). An event at or below the
     * watermark changes nothing. Returns true when the event was applied.
     */
    fun onPlay(p: Posteriors, e: PlayEvent, g: Reward.Graded, novel: Boolean, nowMs: Long, eventId: Long = e.eventId): Boolean {
        if (eventId <= p.lastEventId) return false
        val t = if (e.startedAtMs > nowMs) nowMs else e.startedAtMs
        val day = LearnReplay.dayOf(e, t)
        val family = ArmBandit.familyOf(e.surface)
        val recommender = e.origin == Origin.LIVE && family >= 0 && e.arm >= 0 && e.arm < ArmBandit.ARMS
        if (recommender && !e.has(EventFlags.LEARN_EXCLUDED) && !e.has(EventFlags.NEVER_AUDIBLE)) {
            ArmBandit.update(p.arms, family, e.arm, g.r, g.cOther, e.has(EventFlags.EXPLORE_SLOT), day)
        } else {
            ArmBandit.discount(p.arms, day)
            p.arms.anchorDay = day
            p.arms.forgetDone = 0
        }
        NoveltyAppetite.onPlay(p.appetite, e, g, novel, recommender, day)
        p.lastEventId = eventId
        return true
    }

    /** Brings both posteriors to local day [today]: the discount and the slow appetite's steps. Call at the end of a replay and when a list is built. */
    fun advanceTo(p: Posteriors, today: Long) {
        ArmBandit.discount(p.arms, today)
        NoveltyAppetite.advance(p.appetite, today)
    }
}

/**
 * The persisted [Posteriors]: canonical JSON (see [BrainJson]), doubles as IEEE bit patterns. A reader is strict: anything wrong gives null,
 * and the caller starts again from the priors and schedules a replay.
 */
object PosteriorCodec {
    const val FORMAT = "brain-posteriors-v1"

    fun encode(p: Posteriors): String {
        val w = BrainJson.Writer().beginObject()
        w.string("format", FORMAT)
        w.long("lastEventId", p.lastEventId)
        val a = p.arms
        w.beginObject("arms")
        w.beginArray("alpha"); for (v in a.alpha) w.doubleItem(v); w.endArray()
        w.beginArray("beta"); for (v in a.beta) w.doubleItem(v); w.endArray()
        w.beginArray("auditAlpha"); for (v in a.auditAlpha) w.doubleItem(v); w.endArray()
        w.beginArray("auditBeta"); for (v in a.auditBeta) w.doubleItem(v); w.endArray()
        w.long("anchorDay", a.anchorDay)
        w.int("forgetDone", a.forgetDone)
        w.long("updates", a.updates)
        w.endObject()
        val n = p.appetite
        w.beginObject("appetite")
        w.double("fAlpha", n.fAlpha); w.double("fBeta", n.fBeta); w.double("nAlpha", n.nAlpha); w.double("nBeta", n.nBeta)
        if (n.nuLong != n.nuLong) w.nullValue("nuLong") else w.double("nuLong", n.nuLong)
        w.long("anchorDay", n.anchorDay)
        w.int("forgetDone", n.forgetDone)
        w.long("nuDay", n.nuDay)
        w.long("updates", n.updates)
        w.beginArray("days")
        for ((day, v) in n.days) {
            w.beginArray(); w.longItem(day); w.intItem(v[0]); w.intItem(v[1]); w.endArray()
        }
        w.endArray()
        w.beginArray("boosts"); for (t in n.boosts) w.longItem(t); w.endArray()
        w.endObject()
        return w.endObject().toString()
    }

    fun decode(text: String): Posteriors? = try {
        decodeOrThrow(text)
    } catch (e: RuntimeException) {
        null
    }

    private fun decodeOrThrow(text: String): Posteriors? {
        @Suppress("UNCHECKED_CAST")
        val o = BrainJson.parse(text) as? Map<String, Any?> ?: return null
        if (o["format"] != FORMAT) return null
        @Suppress("UNCHECKED_CAST")
        val ao = o["arms"] as Map<String, Any?>
        val size = ArmBandit.FAMILIES * ArmBandit.ARMS
        val arms = ArmBandit.State(
            alpha = doubles(ao["alpha"], size) ?: return null,
            beta = doubles(ao["beta"], size) ?: return null,
            auditAlpha = doubles(ao["auditAlpha"], size) ?: return null,
            auditBeta = doubles(ao["auditBeta"], size) ?: return null,
            anchorDay = (ao["anchorDay"] as String).toLong(),
            forgetDone = (ao["forgetDone"] as Long).toInt(),
            updates = (ao["updates"] as String).toLong(),
        )
        if (!arms.isValid() || arms.forgetDone < 0 || arms.forgetDone > ArmBandit.MAX_FORGET_RUNS) return null
        @Suppress("UNCHECKED_CAST")
        val no = o["appetite"] as Map<String, Any?>
        val nu = (no["nuLong"] as String?)?.let(BrainJson::doubleOfHex) ?: Double.NaN
        val days = java.util.TreeMap<Long, IntArray>()
        for (item in no["days"] as List<*>) {
            val row = item as List<*>
            if (row.size != 3) return null
            val counts = intArrayOf((row[1] as Long).toInt(), (row[2] as Long).toInt())
            if (counts[0] < 0 || counts[1] < 0 || counts[1] > counts[0]) return null
            days[(row[0] as String).toLong()] = counts
        }
        val boosts = ArrayList<Long>()
        for (item in no["boosts"] as List<*>) boosts.add((item as String).toLong())
        val appetite = NoveltyAppetite.State(
            fAlpha = BrainJson.doubleOfHex(no["fAlpha"] as String),
            fBeta = BrainJson.doubleOfHex(no["fBeta"] as String),
            nAlpha = BrainJson.doubleOfHex(no["nAlpha"] as String),
            nBeta = BrainJson.doubleOfHex(no["nBeta"] as String),
            nuLong = nu,
            anchorDay = (no["anchorDay"] as String).toLong(),
            forgetDone = (no["forgetDone"] as Long).toInt(),
            nuDay = (no["nuDay"] as String).toLong(),
            days = days,
            boosts = boosts,
            updates = (no["updates"] as String).toLong(),
        )
        if (!appetite.isValid() || appetite.forgetDone < 0 || appetite.forgetDone > NoveltyAppetite.MAX_FORGET_RUNS) return null
        return Posteriors(arms, appetite, (o["lastEventId"] as String).toLong())
    }

    private fun doubles(v: Any?, size: Int): DoubleArray? {
        val list = v as? List<*> ?: return null
        if (list.size != size) return null
        val out = DoubleArray(size)
        for (i in 0 until size) {
            val d = BrainJson.doubleOfHex(list[i] as String)
            if (!BrainMath.isFinite(d)) return null
            out[i] = d
        }
        return out
    }
}
