# tools/eval: the Music Brain on real public listening data

A JVM tool (Kotlin, `:eval`, depends on `:brain` only) that replays public ListenBrainz listening histories through the brain's real
code (`Reward`, `GradeTracker`, `Affinity`, `Session`, `Features`, `Ranker`, `Tags`, the bundled genre atlas) and measures the prior
weights, the learner and the artist-rate baseline. It exists because docs/brain-spec.md 14.6 says no hyperparameter had met a real
listener.

```
./gradlew :eval:run --args="all"        # fetch (cached), evaluate, tune, write RESULTS.md
./gradlew :eval:run --args="tune"       # from the cache only
./gradlew :eval:run --args="all --offline --users=70"
```

## Data and politeness

- ListenBrainz listens are CC0. Users are found through public stats only: the top listeners (ranks 2-12) of every third of the 300
  most-listened artists sitewide, ordered by a hash of the name, kept with a public listen count of 2,500 or more.
- User-Agent `PixlAudio-eval/1.0`, nothing about whoever runs it; at most one request a second; the rate-limit headers are honoured.
- Every raw response is cached gzipped under `tools/eval/cache/` (git-ignored), with the built events. Listener names never leave the
  cache: everything written outside it says `u01`, `u02`, ...
- Recording metadata comes from `POST /1/metadata/recording/` with `inc: "artist tag"`, 1,000 recordings a request (it works; the app
  uses 16). Recording tags are source 1, artist tags source 2, merged by `Tags.merge` exactly as the app's DnaAssembler does.

## From a listen to a play event (the skip proxy)

ListenBrainz has no skip button and no `ms_played`. `listened_at` is when a track started. For each listen, the gap to the next one:

| case | event | label |
|---|---|---|
| length known, next listen starts at least 30 s before the track could end (`gap < length - 30 s`) | `endReason = next`, `playedMs = gap` | skipped |
| length known, otherwise | `endReason = trackDone`, `playedMs = length` | finished |
| length unknown, gap under 30 s | `endReason = next`, `playedMs = gap` | skipped |
| length unknown, gap 30 s or more; or the last listen before 30 minutes of silence | `playedMs = -1` (4.3 rule 3, r = 0.75) | not labelled |

Length is the listen's `duration_ms`, else the MusicBrainz recording length. Start reason: `tap` at a session start, `next` after a
proxy skip, else `autoAdvance` (so `passiveRun` counts listens in a row with no skip). Every play is `origin = live`, surface
`unknown` (the listener picked it), arm `NONE`; no likes, no seeks, no engine picks.

What the proxy cannot see: most scrobblers only submit a listen after half the track or 4 minutes, so a listen skipped early is
usually not in the data at all; a proxy "skip" is mostly a track left at 50-90%. Pauses look like finishes. Users with bulk-imported
timestamps (more than 5% of gaps of 2 s or less) or with under 60% known lengths are dropped.

## What is measured

Per listener, events in time order; the last 30% are held out (the spec's 8-week cut gives too few plays at this sample size). The
replay is prequential exactly as `LearnReplay.run` (the tool checks bit equality of the final model with tags off), plus the tag
parts the live path adds (features 4 and 24). For every labelled held-out play:

- log-loss and AUC of finished vs skipped, for (a) the prior `M0`, (b) the learned weights, (c) `Eval.ARTIST_RATE`;
- next-artist hit@20: before each held-out play that changes artist, the listener's known artists ranked by each scorer (best of the
  artist's last 3 tracks); next-genre hit@20 likewise over the top atlas tag;
- the phone's own test 6 (`LearnReplay.test6`, engine labels `r = 1` vs `r <= 0.1`).

Medians over listeners with 95% intervals from 2,000 resamples of listeners; per-listener intervals from resampled sessions.

## Tuning

70% of listeners (fixed hash split) tune, 30% report. Coordinate search: A, the prior means of the features this data can move
(1-4, 12-18, 22-26) on the prior's log-loss; B, the trust factors of 4.3 and skip strengths of 5.5 this data exercises; C, the
learner's step clip and curvature floor. A default changes only if the report listeners show at least 1% log-loss or 0.01 AUC with an
interval not crossing 0 and nothing worse, the ranking metrics included: `eval hitcheck` compares the pre-tuning trust constants
(`Report.TRUST_BEFORE`) with the shipped ones on the report listeners, every metric paired. Not measurable here: the engine-surface trust bases, `prev`/seek/like/explore factors,
graph, island, popularity, context and flow weights (no engine picks, no audio features in this data).
