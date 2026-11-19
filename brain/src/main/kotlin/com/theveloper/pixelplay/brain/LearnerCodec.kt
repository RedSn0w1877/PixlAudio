package com.theveloper.pixelplay.brain

/**
 * The learner's persisted state (docs/brain-spec.md, 5.13): `m` and `q` as IEEE bit patterns, the forgetting anchor, the watermark of the
 * last play applied, the gate's state and the figures Your sound reads. One canonical JSON object (see [BrainJson]), written
 * atomically by the caller together with the event row it follows.
 *
 * A reader is strict: a wrong format, a wrong length, a value that is not finite, a precision not above 0 or a mean outside its box
 * gives `null`, and the caller starts again from the prior and schedules a replay. Nothing here ever throws on bad input.
 */
object LearnerCodec {
    const val FORMAT = "brain-learner-v1"

    /** What is saved beside the model. */
    class Saved(
        val model: RankerModel,
        val gate: Gate.State,
        /** [LearnReplay.VERSION] the model was built under. */
        val learnVersion: Int,
        val summary: Gate.Summary?,
        /** When the last full replay finished; 0 for never. */
        val rebuiltAtMs: Long,
    )

    fun encode(saved: Saved): String {
        val model = saved.model
        val w = BrainJson.Writer().beginObject()
        w.string("format", FORMAT)
        w.int("learnVersion", saved.learnVersion)
        w.int("featureVersion", Ranker.D)
        w.beginArray("m")
        for (i in 0 until Ranker.D) w.doubleItem(model.m[i])
        w.endArray()
        w.beginArray("q")
        for (i in 0 until Ranker.D) w.doubleItem(model.q[i])
        w.endArray()
        w.long("anchorDay", model.anchorDay)
        w.int("forgetDone", model.forgetDone)
        w.long("updates", model.updates)
        w.long("lastEventId", model.lastEventId)
        w.int("resets", model.resets)
        w.bool("useLearned", saved.gate.useLearned)
        w.int("winStreak", saved.gate.winStreak)
        w.long("gateAtMs", saved.gate.atMs)
        w.long("rebuiltAtMs", saved.rebuiltAtMs)
        val s = saved.summary
        if (s == null) w.nullValue("summary") else {
            w.beginObject("summary")
            w.int("plays", s.plays)
            w.int("playsTotal", s.playsTotal)
            w.double("llLearned", s.logLossLearned)
            w.double("llPrior", s.logLossPrior)
            w.double("accLearned", s.accLearned)
            w.double("accPrior", s.accPrior)
            if (s.accLearnedBefore == null) w.nullValue("accLearnedBefore") else w.double("accLearnedBefore", s.accLearnedBefore)
            if (s.accPriorBefore == null) w.nullValue("accPriorBefore") else w.double("accPriorBefore", s.accPriorBefore)
            w.endObject()
        }
        return w.endObject().toString()
    }

    fun decode(text: String): Saved? = try {
        decodeOrThrow(text)
    } catch (e: RuntimeException) {
        // Any bad shape (a missing key, a wrong type, a bad number or hex) is "no usable state".
        null
    }

    private fun decodeOrThrow(text: String): Saved? {
        @Suppress("UNCHECKED_CAST")
        val o = BrainJson.parse(text) as? Map<String, Any?> ?: return null
        if (o["format"] != FORMAT) return null
        if ((o["featureVersion"] as? Long) != Ranker.D.toLong()) return null
        val m = doubles(o["m"]) ?: return null
        val q = doubles(o["q"]) ?: return null
        val prior = Ranker.newModel(0L)
        val model = RankerModel(
            m = m, q = q, m0 = prior.m0,
            anchorDay = (o["anchorDay"] as String).toLong(),
            updates = (o["updates"] as String).toLong(),
            lastEventId = (o["lastEventId"] as String).toLong(),
            forgetDone = (o["forgetDone"] as Long).toInt(),
            resets = (o["resets"] as Long).toInt(),
        )
        if (!model.isValid()) return null
        if (model.forgetDone < 0 || model.forgetDone > Ranker.MAX_FORGET_RUNS) return null
        val gate = Gate.State(o["useLearned"] as Boolean, (o["winStreak"] as Long).toInt(), (o["gateAtMs"] as String).toLong())
        var summary: Gate.Summary? = null
        @Suppress("UNCHECKED_CAST")
        val so = o["summary"] as? Map<String, Any?>
        if (so != null) {
            summary = Gate.Summary(
                plays = (so["plays"] as Long).toInt(),
                playsTotal = (so["playsTotal"] as Long).toInt(),
                logLossLearned = BrainJson.doubleOfHex(so["llLearned"] as String),
                logLossPrior = BrainJson.doubleOfHex(so["llPrior"] as String),
                accLearned = BrainJson.doubleOfHex(so["accLearned"] as String),
                accPrior = BrainJson.doubleOfHex(so["accPrior"] as String),
                accLearnedBefore = (so["accLearnedBefore"] as String?)?.let(BrainJson::doubleOfHex),
                accPriorBefore = (so["accPriorBefore"] as String?)?.let(BrainJson::doubleOfHex),
            )
        }
        return Saved(model, gate, (o["learnVersion"] as Long).toInt(), summary, (o["rebuiltAtMs"] as String).toLong())
    }

    private fun doubles(v: Any?): DoubleArray? {
        val list = v as? List<*> ?: return null
        if (list.size != Ranker.D) return null
        val out = DoubleArray(Ranker.D)
        for (i in 0 until Ranker.D) {
            val d = BrainJson.doubleOfHex(list[i] as String)
            if (!BrainMath.isFinite(d)) return null
            out[i] = d
        }
        return out
    }
}
