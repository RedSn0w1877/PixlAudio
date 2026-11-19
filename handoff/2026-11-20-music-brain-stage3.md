# Music brain, stage 3 (Android): running handoff

Branch `music-brain`. The contract is `docs/brain-spec.md` (v1.1), section 14 "Stage 3: It learns, and shows it". The earlier handoffs are the
map of what exists: stage 1 (deviations 1 to 79), stage 2a (80 to 127), stage 2b (128 to 174). This file continues the numbering at **175**.

**Nothing below has been run on a device.** No phone was available. "Works" means unit tests, compiling and reading the code path end to
end. **The learner has only ever run on synthetic listeners. There is no real-data number yet** (no accuracy, no log-loss, no test 6 result
on a real export): nothing in the code, the copy or this file claims one.

> The "Cut or deferred" list below was closed in part by the second pass: see "Stage 3 completion" at the end of this file (deviations 189 and on).

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

`ANDROID_HOME` set to the SDK, one Gradle at a time.

- `./gradlew --continue :brain:test :app:testDebugUnitTest :app:assembleDebug`: `:brain:test` **214 tests, 0 failures** (includes the new `LearnerTest` with the batch-refit
  comparison, `YourSoundTest`, `GenrePageTest`, the golden test over the four new files, `PurityTest`); `:app:testDebugUnitTest` **2,392 tests, 2 skipped, 1 failure** (my own
  `LearnerEngineTest`: a wrong assertion about how many times the tables were rewritten); `:app:assembleDebug` built both ABI APKs. `RecommendationGoldenEqualityTest` and the rest of
  `data/recommendation` passed untouched.
- After correcting that assertion: `:app:testDebugUnitTest --tests "*LearnerEngineTest" --tests "*BrainCopyTest"`: BUILD SUCCESSFUL. (The whole app suite was not run a second time.)
- **Not run**: the instrumented tests (no device); nothing on a phone.
- Golden files were regenerated with `:brain:generateGolden`: four NEW files, `MANIFEST.sha256` and `README.md` changed, no earlier file changed.

---

# Stage 3 completion (second pass)

The first pass (above) left out the parts listed in "Cut or deferred". This pass built them, in the order of the task: control sessions, the arm bandit,
novelty appetite, context and the daypart mix, impressions and the shelf bandit, Your sound's completions and the genre pages, the brain backup module,
and the test 6 command. **Still true: nothing here has run on a phone, every number is from synthetic listeners, there is no real-data number, and the
legacy engine is NOT deleted.** Deviations continue from 189.

## What was built (files)

`:brain` (pure, tested, golden files `bandit`, `novelty`, `context`, `shelfbandit`, `posteriors`; no earlier golden file changed):

- `ArmBandit.kt` (5.10 arms + audit of 5.12 point 3, `ArmPicker`, `Sampler`), `NoveltyAppetite.kt`, `Posteriors.kt` (`Posteriors`, `PosteriorUpdate`, `PosteriorCodec`: the one
  entry point for live and replay), `ContextLift.kt`, `ShelfBandit.kt` (+ `ShelfBanditCodec`, `rebuild` from impression rows).
- `ListBuilder` (`armPicker`, `Pick.explore`), `StationPlanner` / `MixPlanner` (`armPicker`, `nuBase`, `nuOverride`), `HomePlanner` (`DAYPART_MIX`, `reorder`),
  `LearnReplay` (`Result.posteriors`, `applyLive(..., posteriors)`), `Replay.applyFacts` (a taste adjustment on a TAG), `YourSound` (`lately`, `genreTree`, `genreHistory`).
- Tests: `Stage3bTest` (33 cases), `RealExportTest6` (skipped without a file).

App:

- Control sessions: `BrainEngine.Loaded.rankingChoice` / `RankingChoice`; `BrainItemTag.control` / `modelVersion` / `explore` (extras `brain.ctrl`, `brain.model`, `brain.explore`, queue snapshot
  `brainControl` / `brainModel` / `brainExplore`); `StoredMix`, `StoredHomeRow`, `PlanShelf`, `PlanItem` carry them; `BrainEventRecorder` reads them.
- `BrainEngine`: `Learner.posteriors`, `posteriors_v1`, `armPicker`, `sessionNu`; `ContextStore`; `ShelfStore` + `ImpressionDao` / `ImpressionEntity` (brain.db **version 6**, migration 5 to 6, schema `6.json`);
  `HomePlanBuilder` (daypart shelf, shelf order), `MixKinds.DAYPART`, `HomePlanStateHolder` / `HomeScreen` (seen and started signals), `BrainRefresh` (`settleShelves`).
- Your sound: `YourSoundRepository` (Lately, genre tree, island rows with names), `YourSoundScreen` (Lately, the tree, island dialog with Play station and More / Less), `YourSoundViewModel.adjustGenre`.
- Genre pages: `GenrePageRepository` (key artists, from your library, You and this genre), `GenrePageViewModel` / `GenrePageScreen` (More of this, Less of this), `BrainFeedback.adjustGenre`,
  `AppStateRebuilder` (a genre adjustment becomes a pseudo-count on its tag).
- Backup: `BrainBackupHandler`, `BrainBackupFormat`, `BackupSection.BRAIN`, `ModuleSchemaValidator`, `files/brain/` excluded in `backup_rules.xml` and `data_extraction_rules.xml`.

## Deviations 189 and on

189. **The control flag and the model version are decided when a list is built** (`RankingChoice`), carried in each item and read at finalisation. The recorder no longer works out
     either at the end of a play. An item that carries nothing (a queue saved by an older build, a track the listener chose, a pick made in pool order with no planner, a Home plan shelf other than
     "Because you listened to" and the daypart mix) is recorded as the prior and not a control. The flag now also applies to cards of mixes and Home rows (`chosen = true` items), which the first pass
     never flagged. Your sound's two completion rates are therefore measured on what was really ranked with the prior.
190. **The arm bandit runs only while the learned weights are in use and the session is not a control** (`Loaded.armPicker` is null otherwise). A listener on the standard recipe keeps stage 2's
     choice of the best-valued novel candidate, so no earlier list changes. The spec's "the same code runs with the priors frozen" was not done (it would draw over priors for everybody and move
     every golden list).
191. **An exploration slot** is a novel slot the bandit drew the arm for (`ListBuilder.Pick.explore`); it travels as `brain.explore`, becomes `EventFlags.EXPLORE_SLOT` (which `Reward` already weights 1.5) and feeds the audit
     posterior. The audit is drawn from instead of the main posterior when it holds at least 5 weighted exploration plays beyond its prior and its mean differs by more than 0.15 (the spec gives no evidence floor).
     Arms update from `cOther` of live plays on home, mix (daily, your, named) and station / autoplay surfaces only; other surfaces and imports are ignored.
192. **Novelty**: F and N start at Beta(4, 4) (the spec names no prior), discount 0.98 a day, `nu0` from per-day counts of the trailing 365 days (a "qualified" play is `c > 0`, not excluded; the first play is the first ever),
     `nuLong` moves at most 0.02 a day toward `nu0 * (meanN / meanF)^1.5` (`x * sqrt(x)`), the +0.01 boost fades linearly over 30 days, capped at +0.10, final clip 0.60. It replaces the stage 2 constant only while the
     learned weights are in use: `MixPlanner.nuBase` for mixes and island mixes, and the starting `nuSession` of a new session for autoplay and stations (surface factors and the Familiar / Blend / Discover multiplier unchanged).
     The `nuQ` written to `served_list` still uses the constant.
193. **Persisted as `posteriors_v1`** in `brain_state` (no table). A learner saved before this build, or an unreadable posterior state, clears `learn_version` so the next replay makes both from the log;
     `LearnReplay.VERSION` was not bumped (no golden file moves). Reset gives fresh posteriors with the reset watermark; plays before it are not learned from.
194. **Context** uses the spec's dayparts (05-11, 11-17, 17-22, 22-05) in `ContextLift`; the Home plan keeps its stage 2 daypart (morning to 12). The two disagree only at hour 11. The table is a pure function of the log
     (`ContextStore`, read in the background when the plan needs it, kept 30 minutes; a play with no timezone uses the device offset now). A bucket's play count is plays with an island and trust above 0.
     **Feature 29 (`ctx_lift`) is NOT fed**: it stays 0 as in stage 2 (not in the task list; see Cut).
195. **The daypart mix** is `MixKinds.DAYPART` (`surfaceId daypart:<bucket>:<island>`), planned like a Daily Mix of the leading island (largest `W_c * exp(lift)` among the up to six Daily Mix islands), 25 tracks, 15% new,
     seeded by date and bucket, one per bucket per day. The buckets are weekday and weekend, not single weekdays, so the shelf says "Your weekday nights", "Your weekend mornings" and so on (spec's example "Friday nights" would be a
     claim about one weekday the data does not make). It shows after the Because rows, only with at least two islands, only when the bucket has 50 plays, and not below 6 tracks. It is not gated by the learned weights.
196. **Shelf bandit**: no daily discount (the spec has none for shelves). Rewards come from the log: a tap on a shelf starts the visit's one reward candidate, and it is a reward only when the first play that starts within 2 minutes is heard
     for 30 s or to its end; a shelf on screen for 1 s counts as seen (`LaunchedEffect` in each shelf item). A visit is settled when 30 minutes have passed, at the next page show or in the refresh worker (`settleShelves`).
     Impressions: `impression` (brain.db v6), at most 200 visits and 90 days. The order is used only while the learned weights are on and after 20 shelf updates; drawn once per `planKey`, frozen, with one forced exposure
     (to the top) for a type last seen 14 or more days ago. Reset clears posteriors and impressions.
197. **Lately** is two sets of affinity tables built from the log in order (now, and as of 14 days ago), over artists and tags, keys with at least 3 weight and a move of at least 0.25 of `aff`; only names the brain knows are shown.
     It makes Your sound read the whole log once (background, on open, cached 5 minutes), as it did.
198. **Genre tree**: a family is the genre's ancestor of depth 0; the section shows families, each opening to the genres played inside it; a genre opens its page. **Island tap-through**: top artists and songs by name from the
     DNA tables (unknown names absent); "Play station" is the genre station of the island's first nameable top genre; More / Less is a boost or reduce on that genre with no end date. An island with no nameable genre has the
     lists only.
199. **Genre page**: key artists come from `artist_dna` vectors (artists the brain holds DNA for), "From your library" is the artists of the library songs under the genre, "You and this genre" is first play, share and top 5
     (hidden if never played). More / Less is a genre taste change; `AppStateRebuilder` turns it into the 4.4 pseudo-count (+6 / +6, scaled by strength) on the genre's TAG, and `BrainFeedback.adjustGenre` asks for that replay.
     **Not done**: the ranking-time `ln(product)` of a genre adjustment still matches `Song.genre` only (empty for Spotify tracks); its effect on Spotify tracks is through feature 4 and the pseudo-count.
200. **Backup**: the `brain` module holds the salt, the taste changes and the whole `feedback` table (exclusions); restore puts them back and asks for one replay. The event log is the listener's history and is not in it
     (the playback history modules cover plays); `brain.db` itself still goes with the platform backup; `files/brain/` (saved plans) is excluded there. `sinceVersion` is 4 like the AI logs (the manifest schema is still 3).
201. **Test 6 on a real export**: `RealExportTest6` (below). Not run: no export.

## Cut or deferred in this pass

- Feature 29 `ctx_lift` is not fed (needs the context table at scoring time, and a decision on gating it).
- Your sound: the energy and mood sections, the hero tinted by artwork, entry from Home's avatar (still Settings only), "Private session" / "Not learning from" list on the screen (they live in their own screens).
- A station from an island that has no nameable genre; "Export my data" on Your sound.
- `M0` refit on pooled exports; test 6 on real data; the deletion of the legacy engine (plan above is unchanged).
- iOS is not touched (see below).

## How to test (additions to the phone checklist above)

1. **Control flag**: play autoplay in a few sessions; in the exported events (Developer mode, "Export play events") about one session in ten has flag 32 on its engine picks, and `modelVersion` is 0 on them and on every pick while the gate is off.
2. **Arm bandit and appetite**: nothing changes until the gate turns on. After that, engine picks in novel slots carry flag 16 (exploration); Your sound's novelty line and the share of new tracks follow what you finish.
3. **Daypart shelf**: needs islands and 50 plays in one bucket of the week; then "Your weekday nights" (or the bucket you are in) appears after "Because you listened to". Absent otherwise.
4. **Shelf order**: with the gate on and 20 shelf updates, the order of shelves can differ between dayparts but not within one; `adb shell sqlite3` on `brain.db`: `select count(*) from impression` stays under about 1,600 rows.
5. **Your sound**: Lately appears after a few days of moved plays; tapping a family opens its genres; tapping an island opens its artists and songs, Play station, More of this, Less of this. A genre page shows More / Less.
6. **Backup**: Settings > Backup lists "Music picks"; export, change a taste change, restore: the change comes back.
7. **Test 6 on your own export** (nothing is uploaded):
   `ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk" PIXLAUDIO_EVAL_EXPORT="C:\path\to\play-events.jsonl" ./gradlew :brain:test --tests "com.theveloper.pixelplay.brain.RealExportTest6" -i`
   The report is printed and written to `brain/build/reports/brain-test6.txt`. Only a pass of test 6 on at least two real exports, and test 1 of the same file, allow the deletion plan above.

## For the iOS port (completion pass)

Copy `brain/src/test/resources/brain-golden/` again: NEW `bandit.jsonl`, `novelty.jsonl`, `context.jsonl`, `shelfbandit.jsonl`, `posteriors.jsonl`; `MANIFEST.sha256` and `README.md` changed; **no earlier golden file changed and
`Brain.SPEC_VERSION` is still 2.** Port: `ArmBandit`, `NoveltyAppetite`, `Posteriors` (+ codec), `ContextLift`, `ShelfBandit` (+ codec), the `armPicker` / `explore` path of `ListBuilder` and `StationPlanner`, `MixPlanner.nuBase` /
`nuOverride`, `HomePlanner.reorder` and `DAYPART_MIX`, `LearnReplay` (`posteriors`, the extra `applyLive` argument), `Replay.applyFacts` for a TAG adjustment, and `YourSound.lately` / `genreTree` / `genreHistory` (no golden file holds
those three; port the tests of `Stage3bTest`). The app side to copy by behaviour: deviations 189 to 200 (the item carries the ranking record; the arm picker only while the learned weights are on; the
impression visit and its settlement; the daypart shelf; Your sound's Lately and genre tree; the genre page's sections; the backup module's contents).
