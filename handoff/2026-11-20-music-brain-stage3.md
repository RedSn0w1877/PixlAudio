# Music brain, stage 3 (Android): running handoff

Branch `music-brain`. The contract is `docs/brain-spec.md` (v1.1), section 14 "Stage 3: It learns, and shows it". The earlier handoffs are the
map of what exists: stage 1 (deviations 1 to 79), stage 2a (80 to 127), stage 2b (128 to 174). This file continues the numbering at **175**.

**Nothing below has been run on a device.** No phone was available. "Works" means unit tests, compiling and reading the code path end to
end. **The learner has only ever run on synthetic listeners. There is no real-data number yet** (no accuracy, no log-loss, no test 6 result
on a real export): nothing in the code, the copy or this file claims one.

## What happened to this branch

The first stage 3 pass left the branch without commits: one uncommitted edit of `Ranker.kt`. An independent review (3 majors, 5 minors, 1
blocker "nothing is committed") was answered in this pass. What exists now is below; the list of findings with what was done is in
"Review findings, one by one".

## What was built

### The pure core (`:brain`, tested, golden files added)

- `Ranker.kt`: the learner of 5.9. `Ranker.update` (one diagonal extended-Kalman step), `Ranker.forget` (the daily line), `Ranker.applyEvent` (forget,
  update, anchor and watermark in one call; an event at or below the watermark is never applied twice), `Ranker.weightsFor` / `WeightSource` (which
  weights rank a list), `Ranker.listSeed`, `Ranker.isControlSession`, `RankerModel`.
- `Gate.kt`: the frozen-prior gate of 5.12 point 8 and the figures Your sound shows (log-loss and accuracy over the last 2,000 graded plays, accuracy a
  month earlier).
- `LearnReplay.kt`: `run` replays the log (one pass: the same tables as `Replay.rebuild`, the session, the features, both predictions, then one update),
  `applyLive` (the same step for one live play), `test6` (11, test 6).
- `LearnerCodec.kt`: the persisted learner (`m` and `q` as IEEE bit patterns, anchor, watermark, gate state, figures). Strict reader: anything wrong gives
  null and a prior model plus a replay.
- `YourSound.kt` (`YourSound`, `ModelVoice`) and `GenrePage.kt`: the figures of Your sound and the facts of a genre page.
- `StationPlanner.Request.ranking` / `MixPlanner.Request.ranking`: null (the default) is `M0`, so every earlier plan is byte for byte what it was.
- `Brain.onEvent` got an optional hook called after grading and before the tables change.

### The app

- `BrainEngine`: `Loaded.learner` (model, gate, figures, favourites) under the engine's one lock; live update inside `applyLocked`, saved in the same
  transaction as the event row (`brain_state` key `learner_v1`, no table changed, brain.db stays at version 5); `resetLearned`; the rebuild trains the
  learner in the same pass and adopts it unless a reset happened while it ran; plays recorded meanwhile are caught up through the watermark.
- `BrainRebuilder` (`ReplayMerger.rebuild(..., learn = true)`), `AppStateRebuilder` (a replay is due when `learn_version` differs).
- The weights reach the rankers: mixes (one posterior sample per list and day), autoplay and stations (the mean), Home rows (the mean). Control
  sessions (one in ten) and a gate that is off use `M0`.
- `BrainEventRecorder`: engine picks in a control session get `EventFlags.CONTROL_SESSION`; picks ranked while the learned weights were on carry
  `modelVersion = 1`.
- `YourSoundRepository`, `YourSoundViewModel`, `YourSoundScreen` (Settings > Library > Listening history > "Your sound"), `GenrePageViewModel`,
  `GenrePageScreen` (route `genre_page/{atlasId}`), strings in `strings_brain.xml`.
- `BrainCopyTest`: "learn" is allowed only in `brain_sound_model_learning` and the two reset strings; every other string, and every other forbidden word, still fails.

## Deviations 175 and on

175. **The bias is learned as an offset from the live `logit(p0)`** (spec: `M0[0] = logit(p0)` fixed at the first rebuild). The learned model and the prior
     share one intercept that follows the listener's finish rate, so the gate compares ranking and not calibration, and Reset returns exactly to `M0`.
176. **Forgetting is a pure function of the days on which plays were applied and of today**, not of how often it is called: the runs made since the last applied
     play are `min(gap, 30)` in all. A day before the anchor (clock set back, or a day written by a clock that was ahead) makes no run and pulls the
     anchor back (spec: "leaves the model and its day alone"). The worker does not need its own forgetting call: the end of a replay forgets up to today.
177. **`x` is recomputed at finalisation** from the state just before the play is applied (the spec holds it with the queue entry). Live and replay therefore use
     one function (`LearnReplay.featuresOf`). Features the log cannot reproduce (graph, islands, tags, repeat activation) are 0 in training, so their weights
     stay at the prior; the favourites for feature 8 are those of the last replay.
178. **The learner is saved with every applied play**, in the same transaction as the event row (spec: debounced every 10 events). One 1.7 KB row beside
     the affinity rows already written; it makes a crash lose nothing and apply nothing twice.
179. **The gate starts OFF** and turns on after two counted weekly wins in a row (an evaluation less than 6 days after the last counted one cannot add to
     the streak); it turns off at once when the learned log-loss is not lower, and with fewer than 300 graded plays.
180. **"Accuracy"**: of the graded plays (finished `r = 1`, or skipped `r <= 0.1`), the share where "prediction at or above the listener's base finish rate
     at that moment" matched. Not the spec's wording "I call your skips right"; the screen says "finishes and skips".
181. **Posterior sampling only for mixes** (seed `Ranker.listSeed(salt, "mix:<kind>:<island>:<regenerate>", day)`); autoplay, stations and Home rows use the mean.
182. **`modelVersion = 1`** on an engine pick means "the learned weights were on when the play ended" (approximate: the list may have been ranked earlier).
     Your sound sets the control and learned completion rates side by side only from 300 control plays.
183. **Reset** stores `learn_after_event_id`; the replay still builds the tables from every play but learns from, and predicts, only plays after it. Events, exclusions and
     taste changes stay.
184. **Test 6** is `LearnReplay.test6` (at least 2,000 graded plays, gain of at least 1%, interval of the per-play gain above 0, resampling sessions). It is not run on
     a real export; the gate uses the spec's rule (learned log-loss lower), not the 1% rule.
185. **Your sound is computed when the screen opens**, cached in memory for 5 minutes (spec: `your_sound.json`, recomputed at most once per session end).
186. **Your sound sections**: right now (islands), genres (flat top list, not the tree), eras, through the day, over months, new and familiar, how well I know you,
     controls. **Cut**: "Lately" (the five largest affinity moves), tap-through on islands (top artists and songs, Play station, More / Less), the genre tree,
     the energy and mood sections, the hero tinted by artwork, entry from Home's avatar (it is reached from Settings only).
187. **Genre pages are a new atlas-only screen** (`GenrePageScreen`) reached from Your sound; the library's `GenreDetailScreen` is not rebuilt. Cut: key artists,
     "From your library", "You and this genre", More / Less of this.
188. **The ranker uses the learned weights in mixes, stations, autoplay and Home rows only.** Search, Ask Taizo and the legacy engine are untouched.

## Cut or deferred (and why)

- **The arm bandit, the learned novelty appetite, context lift and the daypart mix, impressions and the shelf bandit** (5.10, scope of stage 3): not built. The
  review's "done means" does not need them, and each needs a persisted posterior and a surface to feed it. Features 29 (`ctx_lift`) and 36 stay as in stage 2.
  The posterior sample per list (5.10) is built.
- **The `brain` backup module**: not built (the backup sections have schema validators and tests that would all change).
- **`M0` re-fitted on pooled exports of friends**: needs real exports.
- **Test 6 on real exports, and the deletion of the legacy engine** (`MusicRecommendationEngine`, `Muselle`, the premium recommendation stubs and the
  recommendation golden files): the deletion is allowed only if the gate passes on REAL data, and nobody has run that. **Nothing was deleted.**
  The written plan is in the next section.
- **iOS** is not touched.

## Deletion plan (do not run until test 6 passes on real data)

1. Run `LearnReplay.run` on at least two real exports (the phone's own history, one friend's) from Developer mode; read `LearnReplay.test6`. Required: at least
   2,000 graded plays, improvement of at least 1% with an interval above 0 for the listener the engine would be switched on for. Record the numbers in the handoff.
2. Test 1 of `Eval` (brain prior against the legacy score) at least 0.05 AUC better on the same exports (stage 2's own done-means).
3. Then, in one commit each: remove `BrainEngineChoice.LEGACY` and the switch row; delete `MusicRecommendationEngine.kt`, `Muselle.kt`,
   `MusicRecommendationEngineTest.kt`, `RecommendationGoldenEqualityTest.kt` and its resource; delete `HomeRecommendationPlanner.kt` and `HomeCatalogRepository.kt` once nothing
   reads them; keep `MusicTasteRepository` (it feeds the cloud prepare-ahead rankers, decision D11). Run `:app:testDebugUnitTest`.
4. The same on iOS (`Recommendation.swift`, `recommendationMatchesAndroid`) after Android.

## For the iOS port

Copy `brain/src/test/resources/brain-golden/` (new: `ekf.jsonl`, `listseed.jsonl`, `replay.jsonl`, `learner.jsonl`; `MANIFEST.sha256` and `README.md` changed; no earlier
file changed). Port `Ranker` (learner part), `Gate`, `LearnReplay`, `LearnerCodec`, `YourSound`, `ModelVoice`, `GenrePage`. `Brain.SPEC_VERSION` is unchanged (2): no earlier output
changed, so no forced full rebuild; the learner is its own `learn_version`. Points that are not in the spec: the bias offset (175), the forgetting anchor (176), the watermark,
the list seed layout (length prefixed, in `listseed.jsonl`), the origin one-hot used in training only, accuracy and its threshold (180).

## Review findings, one by one

| # | Finding | Result |
|---|---|---|
| 1 | blocker: nothing committed | Fixed: the work is committed in this branch, with tests and golden files. |
| 2 | major: a non-finite `m_i` is clamped to the box edge instead of resetting | Real. Fixed: `update` resets a model that is not valid before computing; a mean outside its box is invalid too. Tests: `a non-finite weight at an active feature resets the model...`. |
| 3 | major: model mutated in place, no lock, no watermark | Real. Fixed: the model lives in `BrainEngine.Loaded` under the engine's one lock; `lastEventId` watermark in the model and in the saved state; Reset bumps an epoch the rebuild checks; the rebuild's catch-up is idempotent. `WeightSource` is a snapshot. |
| 4 | major: the 30-run cap makes the model depend on call frequency | Real. Fixed (176). Test: `forgetting does not depend on how often it is called`. |
| 5 | minor: bias prior frozen at model creation | Real. Fixed (175). |
| 6 | minor: `updates` kept after a reset | Real. Fixed: reset sets it to 0; the screen's count is plays since the last reset. |
| 7 | minor: a forward-jumped clock stalls forgetting | Real. Fixed (176). Test: `a day written by a clock that was a year ahead...`. |
| 8 | minor: unbounded `x` wipes the model | Real. Fixed: `|x_i| > 4` skips the update (`X_MAX`). |
| 9 | minor: `listSeed` layout invented, no golden | Real. Fixed: length prefix, `listseed.jsonl`. |
| 10 | minor: `precisionFor` returns the shared array; dangling KDoc links | Real. Fixed: copies; `Gate` and `LearnReplay` now exist and the links are real. |

## How to test on a phone (stage 3 "done means", for Hoa)

1. **Update over the previous build.** The app opens; Settings > Library & storage > Listening history > "Your sound" opens. With under 300 graded plays it says
   "Still getting to know you" and a count. Nothing says "learning" anywhere before the model is really in use.
2. **First replay.** After the first use (not at app start) the rebuild runs once in the background; Developer mode > Brain data should not change. "Your sound" then shows the
   standard-recipe line with the recipe's own accuracy once there are 300 graded plays.
3. **The gate.** The learned weights turn on only after two weekly rebuilds in which they predicted better (so no earlier than two weeks). Until then every pick uses the standard recipe and
   the screen says so. To force a look: `adb shell cmd jobscheduler run -f <pkg> <brain_refresh id>` does not advance the week; the gate counts weeks by time.
4. **Learning shown.** Once on, the screen says "Learning from N plays.", the accuracy now, a month ago when there is one, and the standard recipe's, all from your own plays.
   Check that no number appears before 300 graded plays.
5. **Control sessions.** One session in ten is picked with the standard recipe; after 300 plays there the finish rates are shown side by side.
6. **Reset what I've learned.** Controls > Reset: picks go back to the standard recipe now; "Your sound" says so; after the next rebuild it still does (only plays from now on are
   learned from). Your plays, blocked artists and taste changes are still there.
7. **Genres.** In "Your sound", tap a genre: a page with the atlas's facts (origin, paragraph when the atlas has one, comes from / branches / close to / shaped by); "Play station" starts a genre station.
8. **Nothing at start, one job.** `adb shell dumpsys jobscheduler | grep -c brain_refresh` still shows one entry; app start does no brain work.

## What nobody has verified on a device

Everything above. In particular: the replay time on a real log of 20,000 to 100,000 plays (it computes features and two predictions per play; expected seconds, never measured); the
memory of the replay's prediction arrays; whether the learned weights ever beat the prior on a real listener (**no real-data number exists**); the scale of the gate's two-week rule in
practice; the Your sound screen on a phone (layout, empty states, the island names); the genre page; the Reset on a real database; the saved learner after a process kill; the control-session
completion rates (the flag and `modelVersion` are set at the end of a play, not when the list was ranked).

## What was run, and what it said

See the end of this file (filled in after the last run).
