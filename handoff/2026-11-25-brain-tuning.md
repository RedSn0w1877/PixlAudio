# 2026-11-25: Music Brain measured on real public listening data (branch brain-tuning)

## What changed

- **New tool `tools/eval/` (Gradle `:eval`, JVM only, depends on `:brain`).** Downloads ~70 public ListenBrainz listeners (CC0,
  generic User-Agent, 1 req/s, raw JSON cached gzipped in `tools/eval/cache/`, git-ignored), maps listens to `PlayEvent`s with a
  documented skip proxy, tags tracks through the bundled atlas exactly like DnaAssembler, and replays each listener through the
  brain's real code. `./gradlew :eval:run --args="all"` (or `fetch` / `eval` / `tune` / `hitcheck`). Details: `tools/eval/README.md`.
  Numbers: `tools/eval/RESULTS.md`.
- **`:brain`, no behaviour change:** `Reward.Trust` (the 4.3 trust factors and 5.5 skip strengths as one value with the current
  numbers as defaults), `Reward.grade(e, ctx, t = Trust.DEFAULT)`, `Reward.skipStrength(..., t)`, `GradeTracker(completions, trust)`,
  and `Ranker.update/applyEvent(..., stepClip = STEP_CLIP, hFloor = H_FLOOR)`. Only the tool passes other values. Golden files are
  unchanged (brain/ is byte-identical in behaviour; `:brain:test` green).
- `docs/brain-spec.md` 14.6, "Every hyperparameter" row: what was measured.

## Constants: old -> new

**None changed.** Measured and rejected (tuning on 49 listeners, decided on 21 held-out):

| group | candidate | held-out result | why not |
|---|---|---|---|
| A, M0 | [2] 1.0->0.7, [3] 0.4->-0.2, [4] 0.8->0.2, [12] -1.5->-0.9, [14] -0.1->-0.7, [15] 0.1->-0.5, [16] 0.2->-0.4, [22] 0.8->0.5, [23] -1.0->-1.6, [24] -1.0->-2.2 | AUC prior +0.036 [0.021, 0.052], learned LL +0.9% | prior log-loss +4.6% with CI [-6%, +14%]; 4 of 21 listeners >1% worse; since_last shift likely ListenBrainz selection bias (listeners re-pick tracks on purpose) |
| B, trust | own skip 0.6->1.5, passive12 0.25->0.5, h under half 0.2->0.4 | LL prior +3.2%, learned +3.7% (CIs > 0) | next-artist hit@20 -0.8 pt prior, next-genre -0.8 pt prior / -2.0 pt learned, CIs exclude 0 |
| C, learner | stepClip 0.25->0.1, hFloor 0.02->0.005 | +0.06% LL | no gain |

So **iOS has nothing to copy** for constants or goldens. If iOS ports `Reward.Trust` / the `stepClip`, `hFloor` parameters, it must
keep the same defaults.

## Caveats for whoever reads the numbers

- ListenBrainz users are not Spotify users (self-hosted players like Navidrome dominate; heavy, album-oriented listeners).
- The skip proxy is weak: scrobblers only submit after ~50% or 4 min, so a proxy "skip" is mostly a track left at 50-90%. Many get
  `r >= 0.8` under 4.3 and so are not skips to the engine. Learned log-loss on the proxy label is hurt by that mismatch (it trains on `r`).
- The phone's own test 6 "passes" for 69/70 with a ~50% gain: an artefact. Under engine labels (`r = 1` vs `r <= 0.1`) almost every
  graded play is a finish and the prior's `p0` is clipped at 0.8, so learning the bias alone wins. Do not quote it as evidence.
- No engine picks, likes, seeks, prev, radio ranks, graph, islands or audio features in this data: engine-surface trust bases,
  features 5-11, 19-21, 27-47 and the flow weights were not measurable.
- Next step that would actually settle M0: run `tools/eval` logic on the owner's own export (`RealExportTest6` path) or on Spotify
  extended history, which has real `ms_played`.

## 2026-11-26: the next-artist gap (no constants changed)

- Why the brain trails the artist-rate baseline on next-artist / next-genre hit@20: the since_last one-hots (features 12-13, -1.5 / -0.6)
  sink every artist whose last tracks were played in the last day, which is most of the artists people go back to. The score predicts
  "will they finish it", not "will they pick it", and the 4 h hard constraint of 6.4 already keeps just-played tracks out of lists.
- Tried (tuning listeners) and measured on the report listeners: since_last 12-16 -> 0, track_aff 1.2 -> 0.4, artist_aff_recent
  0.4 -> 1.0, tag_aff 0.8 -> 0.4, sess_tag_pen -1.0 -> -0.5. It beats the baseline on both hit metrics (prior and learned), but learned
  AUC falls (0.5925 -> 0.5821 median), prior log-loss is +1.3% (noise, 9/21 listeners worse) and the share of cold or barely known
  artists in the top 20 drops from 0.195 to 0.051 (learned). Not shipped. Numbers: tools/eval/RESULTS.md, last section.
- An artist listening-share feature (21-day or 120-day, log scale) was tried in the eval only (slot 9): it raises hits less and costs the
  prior log-loss; the learner does not keep its weight. Not added (a new feature means D 51 -> 52 and a learner reset on both apps).
- Tool only: `eval ablate --split=tune --set="12:0,13:0;..."` (prior M0 experiments, prints the medians and the learned weights) and
  `eval pair --split=test --set=...` (paired before/after on one split). Run hits now also report the cold share of the top 20.
- If someone ships the candidate later: bump `LearnReplay.VERSION` (saved learners replay from the new prior), regenerate goldens, and
  iOS must copy M0 and the version. The trade it buys is familiarity for discovery; the owner should pick it, not the metric.
