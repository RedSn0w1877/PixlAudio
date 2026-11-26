package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.LearnReplay
import com.theveloper.pixelplay.brain.LibraryFacts
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.Reward
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.eval.Metrics.f
import java.io.File

/**
 * `fetch`  download the sample (cache/), build each listener's events (cache/built/)
 * `eval`   replay every listener under the shipped constants and print the report
 * `tune`   search the hyperparameters on 70% of the listeners, report on the other 30%, write RESULTS.md
 * `all`    the three in a row
 *
 * Run from tools/eval (the Gradle `run` task does): `./gradlew :eval:run --args="all"`. Flags: `--users=70`, `--offline`.
 */
fun main(args: Array<String>) {
    val cmd = args.firstOrNull() ?: "all"
    val opts = args.drop(1).associate { a -> a.removePrefix("--").split("=", limit = 2).let { it[0] to it.getOrElse(1) { "true" } } }
    val root = File(".").absoluteFile
    val repo = generateSequence(root) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    val cache = File(root, "cache")
    val target = opts["users"]?.toInt() ?: 70
    if (cmd == "fetch" || cmd == "all") fetch(Lb(cache, opts.containsKey("offline")), cache, repo, target)
    val users = Store.load(cache)
    System.err.println("listeners: ${users.size}")
    if (cmd == "eval" || cmd == "all") println(Report.baseline(users))
    if (cmd == "hitcheck") println(Report.hitCheck(Tune.split(users).second))
    if (cmd == "pair") println(Report.pair(users, opts["split"] ?: "test", opts["set"] ?: ""))
    if (cmd == "ablate") Report.ablate(users, opts["split"] ?: "tune", opts["set"] ?: "")
    if (cmd == "tune" || cmd == "all") {
        val text = Tune.run(users)
        File(root, "RESULTS.md").writeText(text)
        println(text)
    }
}

private fun fetch(lb: Lb, cache: File, repo: File, target: Int) {
    val atlas = Dataset.loadAtlas(repo)
    val meta = Dataset.loadMeta(cache)
    val names = Dataset.candidates(lb, cache, target * 2)
    System.err.println("candidates with enough listens: ${names.size}")
    val kept = ArrayList<Listener>()
    val drops = HashMap<String, Int>()
    for (name in names) {
        if (kept.size >= target) break
        val uid = "u" + (kept.size + 1).toString().padStart(2, '0')
        val listens = lb.listens(name, Dataset.MAX_LISTENS)
        Dataset.fetchMeta(lb, cache, meta, Dataset.recMbidsOf(listens))
        val b = Dataset.build(uid, listens, meta, atlas)
        if (b.listener == null) {
            drops[b.dropped!!] = (drops[b.dropped] ?: 0) + 1
            continue
        }
        kept.add(b.listener)
        File(cache, "users-map.tsv").appendText("$uid\t$name\n")
        System.err.println("$uid: ${b.listener.events.size} events, ${b.listener.tags.size} tagged tracks (requests so far ${lb.requests})")
    }
    System.err.println("dropped: $drops")
    Store.save(cache, kept)
}

/** Built listeners on disk (git-ignored): one PlayEvent line per play with its proxy label, and the tag vectors. */
object Store {
    fun save(cache: File, users: List<Listener>) {
        val dir = File(cache, "built")
        dir.deleteRecursively()
        dir.mkdirs()
        for (u in users) {
            File(dir, "${u.id}.events").printWriter().use { w ->
                for ((i, e) in u.events.withIndex()) w.println("${u.label[i]}\t${e.encode()}")
            }
            File(dir, "${u.id}.tags").printWriter().use { w ->
                for ((rid, v) in u.tags.entries.sortedBy { it.key }) w.println("$rid\t$v")
            }
        }
    }

    fun load(cache: File): List<Listener> {
        val dir = File(cache, "built")
        val files = dir.listFiles { f -> f.name.endsWith(".events") }?.sortedBy { it.name } ?: return emptyList()
        return files.parallelStream().map { f ->
            val id = f.name.removeSuffix(".events")
            val labels = ArrayList<Int>()
            val events = ArrayList<PlayEvent>()
            f.forEachLine { line ->
                val tab = line.indexOf('\t')
                labels.add(line.substring(0, tab).toInt())
                events.add(PlayEvent.decode(line.substring(tab + 1)))
            }
            val tags = HashMap<Long, Tags.Vector>()
            File(dir, "$id.tags").forEachLine { line ->
                val p = line.split('\t')
                tags[p[0].toLong()] = Tags.Vector(p[1].split(',').map { t -> t.split(':').let { Tags.Tag(it[0].toInt(), it[1].toInt()) } })
            }
            Listener(id, events, labels.toIntArray(), tags)
        }.toList().sortedBy { it.id }
    }
}

object Report {
    fun scores(users: List<Listener>, cfg: Config, ranking: Boolean = false): List<UserScore> =
        users.parallelStream().map { Metrics.score(it.id, Replayer.run(it, cfg, ranking)) }.toList().sortedBy { it.id }

    /** The phone's own test 6 (engine labels: `r = 1` against `r <= 0.1`), from [LearnReplay.run] unchanged. */
    class T6(val id: String, val graded: Int, val improvement: Double, val low: Double, val high: Double, val passes: Boolean, val enough: Boolean)

    fun test6(users: List<Listener>): List<T6> = users.parallelStream().map { u ->
        val run = LearnReplay.run(u.events.iterator(), LibraryFacts.EMPTY, u.events.last().startedAtMs + 3_600_000L, emptySet(), 0)
        val t = LearnReplay.test6(run.predictions)
        T6(u.id, run.predictions.size, t.improvement, t.gainLow, t.gainHigh, t.passes, t.enoughPlays)
    }.toList().sortedBy { it.id }

    fun summary(title: String, s: List<UserScore>): String = buildString {
        fun col(sel: (UserScore) -> Double) = Metrics.bootstrap(s.map(sel).toDoubleArray())
        appendLine("**$title** (${s.size} listeners, ${s.sumOf { it.n }} labelled held-out plays, median skip share ${f(Metrics.median(s.map { it.skipShare }.toDoubleArray()), 3)})")
        appendLine()
        appendLine("| metric (median over listeners, 95% CI over resampled listeners) | prior (M0) | learned | artist-rate baseline |")
        appendLine("|---|---|---|---|")
        appendLine("| held-out log-loss | ${col { it.ll[0] }} | ${col { it.ll[1] }} | ${col { it.ll[2] }} |")
        appendLine("| AUC finished vs skipped | ${col { it.auc[0] }} | ${col { it.auc[1] }} | ${col { it.auc[2] }} |")
        if (s.any { it.artistHit.any { h -> h > 0 } }) {
            appendLine("| next-artist hit@20 | ${col { it.artistHit[0] }} | ${col { it.artistHit[1] }} | ${col { it.artistHit[2] }} |")
            appendLine("| next-genre hit@20 | ${col { it.genreHit[0] }} | ${col { it.genreHit[1] }} | ${col { it.genreHit[2] }} |")
        }
        val rel = s.map { if (it.ll[0] > 0) it.gain / it.ll[0] else 0.0 }.toDoubleArray()
        appendLine()
        appendLine("Learned vs prior, relative log-loss gain: median ${Metrics.bootstrap(rel)}; listeners whose own session-resampled interval is above 0: ${s.count { it.gainLow > 0 }} / ${s.size}; below 0: ${s.count { it.gainHigh < 0 }}.")
        appendLine("AUC learned minus prior: median ${Metrics.bootstrap(s.map { it.auc[1] - it.auc[0] }.toDoubleArray())}; prior minus artist rate: median ${Metrics.bootstrap(s.map { it.auc[0] - it.auc[2] }.toDoubleArray())}.")
    }

    fun perUser(s: List<UserScore>): String = buildString {
        appendLine("| listener | labelled plays | skip share | LL prior | LL learned | LL artist | AUC prior | AUC learned | AUC artist | learned gain (CI) | artist hit@20 p/l/a |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        for (u in s) appendLine(
            "| ${u.id} | ${u.n} | ${f(u.skipShare, 2)} | ${f(u.ll[0], 3)} | ${f(u.ll[1], 3)} | ${f(u.ll[2], 3)} | ${f(u.auc[0], 3)} | ${f(u.auc[1], 3)} | ${f(u.auc[2], 3)} | " +
                "${f(u.gain, 4)} [${f(u.gainLow, 4)}, ${f(u.gainHigh, 4)}] | ${f(u.artistHit[0], 2)}/${f(u.artistHit[1], 2)}/${f(u.artistHit[2], 2)} |",
        )
    }

    /** The trust constants before the 2026-11-25 tuning, for the paired before/after check. */
    val TRUST_BEFORE = Reward.Trust(ownSkipped = 0.6, passive12 = 0.25, hUnderHalf = 0.2)

    fun parseSet(x: String): Config {
        var cfg = Config.DEFAULT
        for (kv in x.split(',').filter { it.isNotBlank() }) {
            if (kv.trim().startsWith("x")) { cfg = cfg.withExtra(kv.trim().drop(1).toInt()); continue }
            val (i, v) = kv.split(':')
            cfg = cfg.withM0(i.trim().toInt(), v.trim().toDouble())
        }
        return cfg
    }

    /** `pair --split=test --set=...`: shipped prior against a candidate prior on one split, every metric paired per listener. */
    fun pair(users: List<Listener>, split: String, set: String): String = buildString {
        val (tune, test) = Tune.split(users)
        val us = when (split) { "tune" -> tune; "all" -> users; else -> test }
        val a = scores(us, Config.DEFAULT, ranking = true)
        val b = scores(us, parseSet(set), ranking = true)
        appendLine(summary("Before (shipped M0), $split", a))
        appendLine(summary("After ($set), $split", b))
        appendLine("Barely-known or cold artists (played at most ${Replayer.RARE_PLAYS} times, or not in 30 days) in the top 20, median: before ${f(Metrics.median(a.map { it.rare[0] }.toDoubleArray()), 3)} prior / ${f(Metrics.median(a.map { it.rare[1] }.toDoubleArray()), 3)} learned, after ${f(Metrics.median(b.map { it.rare[0] }.toDoubleArray()), 3)} / ${f(Metrics.median(b.map { it.rare[1] }.toDoubleArray()), 3)}, artist rate ${f(Metrics.median(a.map { it.rare[2] }.toDoubleArray()), 3)}")
        appendLine()
        fun d(name: String, sel: (UserScore) -> Double) =
            appendLine("| $name | ${Metrics.bootstrap(DoubleArray(a.size) { sel(b[it]) - sel(a[it]) }, Metrics::mean)} |")
        fun vsBase(name: String, sel: (UserScore) -> Double, base: (UserScore) -> Double) =
            appendLine("| $name | ${Metrics.bootstrap(DoubleArray(b.size) { sel(b[it]) - base(b[it]) }, Metrics::mean)} |")
        appendLine("| metric, after minus before (mean, 95% CI over listeners) | |")
        appendLine("|---|---|")
        d("log-loss prior") { it.ll[0] }; d("log-loss learned") { it.ll[1] }
        d("AUC prior") { it.auc[0] }; d("AUC learned") { it.auc[1] }
        d("next-artist hit@20 prior") { it.artistHit[0] }; d("next-artist hit@20 learned") { it.artistHit[1] }
        d("next-genre hit@20 prior") { it.genreHit[0] }; d("next-genre hit@20 learned") { it.genreHit[1] }
        d("barely-known share prior") { it.rare[0] }; d("barely-known share learned") { it.rare[1] }
        vsBase("after: next-artist hit@20 prior minus artist rate", { it.artistHit[0] }, { it.artistHit[2] })
        vsBase("after: next-artist hit@20 learned minus artist rate", { it.artistHit[1] }, { it.artistHit[2] })
        vsBase("after: next-genre hit@20 prior minus artist rate", { it.genreHit[0] }, { it.genreHit[2] })
        vsBase("after: next-genre hit@20 learned minus artist rate", { it.genreHit[1] }, { it.genreHit[2] })
        val wp = a.indices.count { (a[it].ll[0] - b[it].ll[0]) / a[it].ll[0] < -0.01 }
        val wl = a.indices.count { (a[it].ll[1] - b[it].ll[1]) / a[it].ll[1] < -0.01 }
        appendLine()
        appendLine("Listeners worse by more than 1% log-loss: prior $wp, learned $wl of ${a.size}.")
    }

    /** Paired before/after (old trust vs the shipped one) on [users]: every metric, mean difference with its interval over listeners. */
    fun hitCheck(users: List<Listener>): String = buildString {
        val a = scores(users, Config(trust = TRUST_BEFORE), ranking = true)
        val b = scores(users, Config.DEFAULT, ranking = true)
        fun d(name: String, sel: (UserScore) -> Double) =
            appendLine("| $name | ${Metrics.bootstrap(DoubleArray(a.size) { sel(b[it]) - sel(a[it]) }, Metrics::mean)} |")
        appendLine("Paired after minus before, ${a.size} report listeners (mean, 95% CI over listeners)")
        appendLine()
        appendLine("| metric | after - before |")
        appendLine("|---|---|")
        d("log-loss prior") { it.ll[0] }; d("log-loss learned") { it.ll[1] }; d("log-loss artist rate") { it.ll[2] }
        d("AUC prior") { it.auc[0] }; d("AUC learned") { it.auc[1] }; d("AUC artist rate") { it.auc[2] }
        d("next-artist hit@20 prior") { it.artistHit[0] }; d("next-artist hit@20 learned") { it.artistHit[1] }; d("next-artist hit@20 artist rate") { it.artistHit[2] }
        d("next-genre hit@20 prior") { it.genreHit[0] }; d("next-genre hit@20 learned") { it.genreHit[1] }; d("next-genre hit@20 artist rate") { it.genreHit[2] }
    }

    /**
     * Ranking ablations: `ablate --split=tune|test|all --set="12:0,13:0;2:2"`. Each `;`-separated experiment sets prior means (index:value)
     * and prints the medians of every metric, the barely-known share of the top 20 included. The empty experiment is the shipped prior.
     */
    fun ablate(users: List<Listener>, split: String, set: String) {
        val (tune, test) = Tune.split(users)
        val us = when (split) { "test" -> test; "all" -> users; else -> tune }
        val exps = listOf("") + set.split(';').map { it.trim() }.filter { it.isNotEmpty() }
        for (x in exps) {
            var cfg = Config.DEFAULT
            for (kv in x.split(',').filter { it.isNotBlank() }) {
                if (kv.trim().startsWith("x")) { cfg = cfg.withExtra(kv.trim().drop(1).toInt()); continue }
                val (i, v) = kv.split(':')
                cfg = cfg.withM0(i.trim().toInt(), v.trim().toDouble())
            }
            val runs = us.parallelStream().map { it.id to Replayer.run(it, cfg, true) }.toList().sortedBy { it.first }
            val s = runs.map { Metrics.score(it.first, it.second) }
            val idxs = intArrayOf(1, 2, 3, 4, 9, 12, 13, 14, 15, 16, 22, 23, 24, 25, 26)
            System.out.println("  learned m (median): " + idxs.joinToString(" ") { i -> "$i=" + f(Metrics.median(runs.map { it.second.model.m[i] }.toDoubleArray()), 2) })
            fun m(sel: (UserScore) -> Double) = f(Metrics.median(s.map(sel).toDoubleArray()), 3)
            fun mn(sel: (UserScore) -> Double) = f(Metrics.mean(s.map(sel).toDoubleArray()), 3)
            println(
                "[${if (x.isEmpty()) "shipped" else x}] LL ${m { it.ll[0] }}/${m { it.ll[1] }}/${m { it.ll[2] }}  AUC ${m { it.auc[0] }}/${m { it.auc[1] }}/${m { it.auc[2] }}  " +
                    "artist ${m { it.artistHit[0] }}/${m { it.artistHit[1] }}/${m { it.artistHit[2] }} (mean ${mn { it.artistHit[0] }}/${mn { it.artistHit[1] }}/${mn { it.artistHit[2] }})  " +
                    "genre ${m { it.genreHit[0] }}/${m { it.genreHit[1] }}/${m { it.genreHit[2] }}  rare ${m { it.rare[0] }}/${m { it.rare[1] }}/${m { it.rare[2] }}",
            )
        }
    }

    fun baseline(users: List<Listener>): String = buildString {
        val same = users.take(5).all { Replayer.matchesEngine(it) }
        appendLine("Replay loop equals LearnReplay.run bit for bit (tags off, shipped constants, 5 listeners): $same")
        appendLine(summary("Shipped constants, tags on", scores(users, Config.DEFAULT, ranking = true)))
        appendLine(summary("Shipped constants, tags off (what LearnReplay rebuilds from the log)", scores(users, Config(useTags = false))))
    }
}

object Tune {
    /** 70% of the listeners tune, 30% report; the split is a hash of the anonymous id, fixed before anything is measured. */
    fun split(users: List<Listener>): Pair<List<Listener>, List<Listener>> {
        val order = users.sortedBy { BrainHash.splitmix64(BrainHash.fnv1a64("split:" + it.id)) }
        val n = (order.size * 0.7).toInt()
        return order.take(n).sortedBy { it.id } to order.drop(n).sortedBy { it.id }
    }

    private fun objective(users: List<Listener>, cfg: Config, which: Int): Double {
        val s = Report.scores(users, cfg)
        return when (which) {
            0 -> Metrics.mean(s.map { it.ll[0] }.toDoubleArray())
            1 -> Metrics.mean(s.map { it.ll[1] }.toDoubleArray())
            else -> Metrics.mean(s.map { (it.ll[0] + it.ll[1]) / 2 }.toDoubleArray())
        }
    }

    private fun trustWith(t: Reward.Trust, name: String, v: Double): Reward.Trust = Reward.Trust(
        ownKept = t.ownKept, ownSkipped = if (name == "ownSkipped") v else t.ownSkipped, engineKept = t.engineKept, engineSkipped = t.engineSkipped,
        streakFactor = if (name == "streakFactor") v else t.streakFactor, quickSkipFactor = t.quickSkipFactor,
        lovedSkipFactor = if (name == "lovedSkipFactor") v else t.lovedSkipFactor,
        passive6 = if (name == "passive6") v else t.passive6, passive12 = if (name == "passive12") v else t.passive12,
        hUnder10 = t.hUnder10, hUnder30 = if (name == "hUnder30") v else t.hUnder30, hUnderHalf = if (name == "hUnderHalf") v else t.hUnderHalf,
    )

    private fun trustValue(t: Reward.Trust, name: String): Double = when (name) {
        "ownSkipped" -> t.ownSkipped; "streakFactor" -> t.streakFactor; "lovedSkipFactor" -> t.lovedSkipFactor
        "passive6" -> t.passive6; "passive12" -> t.passive12; "hUnder30" -> t.hUnder30; else -> t.hUnderHalf
    }

    private val M0_SEARCH = intArrayOf(1, 2, 3, 4, 12, 13, 14, 15, 16, 17, 18, 22, 23, 24, 25, 26)
    private val TRUST_SEARCH = mapOf(
        "ownSkipped" to doubleArrayOf(0.3, 0.6, 1.0, 1.5),
        "passive6" to doubleArrayOf(0.25, 0.5, 0.75, 1.0),
        "passive12" to doubleArrayOf(0.1, 0.25, 0.5, 1.0),
        "streakFactor" to doubleArrayOf(0.1, 0.3, 0.6, 1.0),
        "lovedSkipFactor" to doubleArrayOf(0.1, 0.3, 0.6, 1.0),
        "hUnder30" to doubleArrayOf(0.3, 0.6, 0.8),
        "hUnderHalf" to doubleArrayOf(0.1, 0.2, 0.4, 0.6),
    )
    private val CLIPS = doubleArrayOf(0.05, 0.1, 0.25, 0.5, 1.0)
    private val FLOORS = doubleArrayOf(0.005, 0.01, 0.02, 0.05, 0.1)

    /** An accepted move must lower the tuning objective by at least this (mean per-play log-loss). */
    private const val MIN_STEP = 0.0005

    fun run(users: List<Listener>): String {
        val (tune, test) = split(users)
        val log = StringBuilder()
        // A: the prior means, on the prior's log-loss.
        var cfg = Config.DEFAULT
        var best = objective(tune, cfg, 0)
        log.appendLine("A. prior M0, objective = mean prior log-loss on the tuning listeners; start ${f(best)}")
        repeat(2) { pass ->
            for (i in M0_SEARCH) {
                val cur = cfg.m0[i]
                for (d in doubleArrayOf(-0.6, -0.3, 0.3, 0.6)) {
                    val c = cfg.withM0(i, Math.round((cur + d) * 100) / 100.0)
                    val o = objective(tune, c, 0)
                    if (o < best - MIN_STEP) { best = o; cfg = c; log.appendLine("  pass ${pass + 1}: M0[$i] ${f(cur, 2)} -> ${f(c.m0[i], 2)}: ${f(o)}") }
                }
            }
        }
        val cfgA = cfg
        // B: the trust factors and skip strengths, on the mean of prior and learned log-loss.
        best = objective(tune, cfg, 2)
        log.appendLine("B. trust factors (4.3) and skip strengths (5.5), objective = mean of prior and learned log-loss; start ${f(best)}")
        repeat(2) { pass ->
            for ((name, values) in TRUST_SEARCH) {
                for (v in values) {
                    if (v == trustValue(cfg.trust, name)) continue
                    val c = Config(cfg.m0, trustWith(cfg.trust, name, v), cfg.stepClip, cfg.hFloor, true)
                    val o = objective(tune, c, 2)
                    if (o < best - MIN_STEP) { best = o; cfg = c; log.appendLine("  pass ${pass + 1}: $name -> $v: ${f(o)}") }
                }
            }
        }
        val cfgB = cfg
        // C: the learner's step clip and curvature floor, on the learned log-loss.
        best = objective(tune, cfg, 1)
        log.appendLine("C. learner step clip and curvature floor, objective = mean learned log-loss; start ${f(best)}")
        for (clip in CLIPS) for (floor in FLOORS) {
            if (clip == cfg.stepClip && floor == cfg.hFloor) continue
            val c = Config(cfg.m0, cfg.trust, clip, floor, true)
            val o = objective(tune, c, 1)
            if (o < best - MIN_STEP) { best = o; cfg = c; log.appendLine("  stepClip $clip, hFloor $floor: ${f(o)}") }
        }
        val cfgC = cfg
        val groups = listOf(
            "A prior M0 only" to cfgA,
            "B trust only" to Config(trust = cfgB.trust),
            "C learner only" to Config(stepClip = cfgC.stepClip, hFloor = cfgC.hFloor),
            "A+B" to cfgB,
            "A+B+C" to cfgC,
        )

        val base = Report.scores(test, Config.DEFAULT, ranking = true)
        val baseAll = Report.scores(users, Config.DEFAULT, ranking = true)
        val t6 = Report.test6(users)
        return buildString {
            appendLine("# Music Brain on public ListenBrainz listeners")
            appendLine()
            appendLine("Generated by `./gradlew :eval:run --args=\"all\"` (tools/eval). ${users.size} anonymous public ListenBrainz listeners (CC0 data), up to")
            appendLine("${Dataset.MAX_LISTENS} newest listens each; ${tune.size} tune the constants, ${test.size} report. Labels are the skip proxy of README.md, not real skips.")
            appendLine("Every number is on each listener's held-out last ${(Replayer.HOLD_OUT * 100).toInt()}% of plays, replayed prequentially (predict, then learn).")
            appendLine()
            appendLine("## Shipped constants")
            appendLine()
            appendLine(summary("All listeners", baseAll))
            appendLine(summary("Report listeners only", base))
            appendLine("Phone test 6 (LearnReplay.run + LearnReplay.test6 unchanged, engine labels `r = 1` vs `r <= 0.1`): enough graded plays ${t6.count { it.enough }} / ${t6.size}, passes ${t6.count { it.passes }}, median gain ${f(Metrics.median(t6.map { it.improvement * 100 }.toDoubleArray()), 2)} %, median graded plays ${Metrics.median(t6.map { it.graded.toDouble() }.toDoubleArray()).toInt()}.")
            appendLine()
            appendLine("## Search (tuning listeners)")
            appendLine()
            appendLine("```")
            append(log)
            appendLine("```")
            appendLine()
            appendLine("## Held-out check (report listeners): old constants -> candidate, paired per listener")
            appendLine()
            appendLine("| change | rel. LL gain prior (mean, CI) | rel. LL gain learned | AUC diff prior | AUC diff learned | listeners worse by >1% LL (prior / learned) | clear? |")
            appendLine("|---|---|---|---|---|---|---|")
            for ((name, c) in groups) {
                val s = Report.scores(test, c)
                fun rel(m: Int) = Metrics.bootstrap(DoubleArray(s.size) { (base[it].ll[m] - s[it].ll[m]) / base[it].ll[m] }, Metrics::mean)
                fun dauc(m: Int) = Metrics.bootstrap(DoubleArray(s.size) { s[it].auc[m] - base[it].auc[m] }, Metrics::mean)
                val rp = rel(0); val rl = rel(1); val ap = dauc(0); val al = dauc(1)
                val gain = (rp.value >= 0.01 && rp.low > 0) || (rl.value >= 0.01 && rl.low > 0) || (ap.value >= 0.01 && ap.low > 0) || (al.value >= 0.01 && al.low > 0)
                val worse = rp.high < 0 || rl.high < 0 || ap.high < 0 || al.high < 0
                val wp = s.indices.count { (base[it].ll[0] - s[it].ll[0]) / base[it].ll[0] < -0.01 }
                val wl = s.indices.count { (base[it].ll[1] - s[it].ll[1]) / base[it].ll[1] < -0.01 }
                appendLine("| $name | $rp | $rl | $ap | $al | $wp / $wl of ${s.size} | ${if (gain && !worse) "yes" else "no"} |")
            }
            appendLine()
            appendLine("Candidate values: M0 ${describeM0(cfgA)}; trust ${describeTrust(cfgB.trust)}; stepClip ${cfgC.stepClip}, hFloor ${cfgC.hFloor}.")
            appendLine()
            appendLine("## Per listener (report listeners, shipped constants)")
            appendLine()
            append(Report.perUser(base))
        }
    }

    private fun summary(t: String, s: List<UserScore>) = Report.summary(t, s)

    fun describeM0(c: Config): String {
        val diffs = (1 until Ranker.D).filter { c.m0[it] != Ranker.m0(it) }.map { "[$it] ${Ranker.m0(it)} -> ${c.m0[it]}" }
        return if (diffs.isEmpty()) "unchanged" else diffs.joinToString(", ")
    }

    fun describeTrust(t: Reward.Trust): String {
        val d = Reward.Trust.DEFAULT
        val names = listOf("ownSkipped", "streakFactor", "lovedSkipFactor", "passive6", "passive12", "hUnder30", "hUnderHalf")
        val diffs = names.filter { trustValue(t, it) != trustValue(d, it) }.map { "$it ${trustValue(d, it)} -> ${trustValue(t, it)}" }
        return if (diffs.isEmpty()) "unchanged" else diffs.joinToString(", ")
    }
}
