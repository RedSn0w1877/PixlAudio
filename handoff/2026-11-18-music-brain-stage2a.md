# Music brain, stage 2a (Android): running handoff

Branch `music-brain`. The contract is `docs/brain-spec.md` (v1.1), section 14 "Stage 2" half 2a: everything moved
from stage 1, the brain scorer behind `BrainSwitch`, and Daily Mix / Your Mix with new music. Stage 1's own handoff
(`handoff/2026-11-17-music-brain-stage1.md`) is the map of what exists; its deviations are numbered 1 to 79. This
file continues the numbering at **80**.

**Nothing below has been run on a device.** No phone was available. "Works" means: unit tests, compiling, and
reading the code path end to end. Every section ends with what is still unverified.

---

## Milestone 1 of 2a: "the mixes get new music"

### Task 1: Spotify Connect and Cast with a track that has no Spotify URI (spec 14.6, owner question 7)

**The trace (read from the code, not run).**

*Cast.* `CastPlayer.loadQueue(songs, ...)` builds the whole Cast queue once, as urls of this phone's Ktor file server
(`MediaFileHttpServerService`), one `/song/{songId}` url per song. `CastSessionSecurity.buildAccessPolicy` takes the
list of allowed song ids at that moment and the server refuses every other id (`isAuthorizedSongRequest`). For an
allowed id the server resolves the song with `musicRepository.getSong(id)` and, failing that, a MediaStore query on
a numeric id (`resolveSongForServing`), then serves `contentUriString` / the file path. Nothing appends to a loaded
Cast queue. So a pick from outside the library (`playable` row, synthetic id, no `songs` row, no content uri) is
not in the allowed set, has no row to resolve, has no file to serve, and cannot be added after the load.
**Decision: Cast stays off.** No picks, no station. The queue line says so ("Off while casting. Cast plays only the
songs you queued"), the Autoplay switch and the Familiar / Blend / Discover control are hidden while a Cast session
is up, and "Start station" shows a plain toast instead of starting one song and nothing after it.

*Spotify Connect.* A Connect session plays the phone's own queue entry by entry (`SpotifyConnectController`): each
queue item becomes a `ConnectTrack` (`ConnectTrack.from(item)`), which is a direct Spotify track uri when the item
carries a real Spotify id (`SpotifyConnect.isTrackId`: 22 base62 characters that are not all lower-case hex) and
otherwise is looked up by ISRC, then by "title artist", and accepted only on a strict match (same normalised title
and primary artist, duration within 3 s, no live / remix / cover mismatch: `SpotifyConnectMatcher`). A miss is
cached (`MISS_RETRY_MS`) and the entry is skipped when its window is sent. An engine track has a synthetic id (22
lower-case hex characters, not a Spotify id), so it goes through the lookup. So Connect can play an engine track
**when Spotify has it**, and skips it silently when not.
**Decision: autoplay and stations run on Connect, limited to what Spotify has.** The planner's picks are looked up
*before* they are queued (`SpotifyConnectController.canPlay`, the same lookup and cache the window send uses, at most
24 searches a call), and a pick Spotify does not have never reaches the queue: it leaves the pool and is remembered as
failed for a while (`AutoplayController.keepWhatTheOutputPlays`). Library tracks on the library-only pool path go in
only when they carry a Spotify id (no room for a lookup there). The tail is **not re-scored** on a remote output
(replacing what the device was told to play means sending it the list again, which is audible), and the 1.5 s settle
rule reads no position there (the phone's own player sits paused at 0 while the device plays), so it is a plain delay.
One switch turns Connect autoplay off again: `BrainSwitch.AUTOPLAY_ON_CONNECT`.

**Files.** `AutoplayPolicy.kt` (`RemoteOutput`, `RemoteRule`, `remoteRule`, `isSpotifyTrackId`, `keepPlayable`; all
pure and tested), `AutoplayController.kt` (`Host.remoteOutput()`, `Host.playableOnRemote`, the rule read at every
place that used to ask `isRemoteSession`), `MusicService.kt` (the host), `SpotifyConnectController.kt` (`canPlay`),
`BrainSwitch.kt` (`AUTOPLAY_ON_CONNECT`), `BrainSessionStateHolder.kt` + `AutoplayDivider.kt` (the Cast line),
`StationStateHolder.kt` (no station while casting), `strings_brain.xml`, `strings_player.xml`,
`AutoplayPolicyTest.kt` (six new tests).

**Deviations.**

80. **Connect autoplay is on, Cast is off, by output and not by one flag.** The spec's owner question 7 default was
    "off on both until traced". The trace says Connect can take picks.
81. **A pick Spotify does not have is dropped at plan time**, not skipped by the device later. The cost is up to 24
    Spotify searches per batch (the lookup cache makes the window send free afterwards).

**Not verified on a device.** Everything: that a Connect device actually plays an engine pick; that the 24-search
bound is enough for a station's 20-track first batch on a cold cache (a miss leaves the batch short and the pool
refills); that the Cast line shows the moment a session starts; that a session ending mid-way brings the switch back.

---

### What was already in the tree when this pass began (a usage limit and a crash had cut the earlier ones short)

Tasks 1 and most of task 4's pure core were written but never compiled together: the Connect / Cast policy, and in
`:brain` `Snapshot.kt`, `MixPlanner.kt`, `Repetition.kt`, `Seeds.kt`, `Eval.kt`, the served-log penalty in
`ListBuilder`, `WhyLine`'s on-repeat rule and the new golden files. This pass compiled and ran them
(`:brain:test`: 109 green), kept them, and built everything from task 2 on. Commit `9125ee8` holds task 1 and that
pure core together (its subject names only the second half).

### Task 2: `brain.db` version 2

`MusicBrainDatabase` is version 2 with a real `Migration(1, 2)` (`MusicBrainMigrations`); there is no destructive
fallback and none was added. New tables, all in `store/BrainEntitiesV2.kt`:

- `graph_edge(src, dst, type, wQ16, rank, fetchedAt)`, PK `(src, dst, type)`: the radio and Related-tab edges of 3.6.
  `GraphEdgeStore` writes the edges of every radio `BrainRepository` stores (not of an artist station's list, which is a
  top-songs list and not a ranking by closeness), replaces a seed's edges when its radio is fetched again, converts
  the lists stage 1 left behind **once** (`backfillOnce`, flag `graph_edges_backfilled_v1` in `brain_state`, the
  edges keep the age of the list they came from) and reads edges back for a mix (`edgesFor`). Weights are
  `factor / log2(2 + rank)` times 65,536, tested equal to what `Graph.Builder.addList` computes to 16 bits. Edges
  expire with the list (30 days; pruned at the end of a refresh).
- `served_list(recId, surface, surfaceId, builtAt, seed, modelVersion, nuQ, itemsBlob)`: the served log, and the
  saved mixes (the newest row of a surface built on today's local day is today's mix). 60-day retention, pruned on
  every write. The blob is `MixCodec`'s gzip of `StoredMix` (`MixModel.kt`).
- `feedback(id, kind, targetKind, targetId, createdAtMs, undoneAtMs)`: **created and not used yet**. It is a truth
  table, so it had to exist before any phone gets real rows; "Don't learn from this" and Private session (the next
  milestone) use it without a third change to the file.

Tests: `MusicBrainMigrationsTest` (JVM) holds the migration statements against the exported `1.json` / `2.json`
(new tables exactly as Room wrote them, every old table byte-identical); `MusicBrainMigrationInstrumentedTest`
(androidTest) opens a version 1 file with real rows, migrates it with `MigrationTestHelper` and checks nothing was
lost. **The instrumented one has not run: no device.**

### Task 3: `BrainRefreshWorker`

One unique periodic job, `brain_refresh`: 24 hours, unmetered network, battery not low, random initial delay up to
60 minutes (`BrainRefreshScheduler`). It is scheduled by `BrainMixes` after the first mix build (the first time the
brain is used for something), **never at app start**; `KEEP` makes every later call a no-op. It defers while music
plays (retry, at most 5 times, as `AiDailyMixWorker` does).

The rules live in `BrainRefreshRunner` (plain class, unit-tested without WorkManager): off switches asked before every
request (Developer mode switch "Background music search", and `brain.refresh.enabled` in the remote config; `BrainRemoteFlags`
reads the copy `StreamRemoteFlags` already saves, no network of its own, missing means on), never while music plays,
one request at a time, stops when `requestsLeft()` is 0, idempotent (a fresh cached list is answered without a
request), cancellable.
Lane 1 = the day's seeds of 3.6 (8 recent chosen finished, 8 top by decayed engagement, 4 liked or saved, 4
pseudo-random library tracks with non-negative affinity; at most 2 per artist; only tracks the listener chose; a seed
without a matched YouTube id is skipped). Lane 2 = the artist page's top songs and the artist radio (`artistRadioFor`) for
the top 20 artists by affinity: these lists feed the ARTIST arm of the mixes.
Budget: `HostBudget` is 60 a day and a burst of 12 (stage 1's numbers); the worker may use 40 of the day and never the
last 4 tokens of the burst. A note of each run is kept in `brain_state` `refresh_last`.

### Task 4: the pure core for 2a

`Snapshot` (`brain-snapshot-v1`, golden `snapshot.jsonl`), `MixPlanner` (golden `mixes.jsonl`), `Repetition` (golden
`repetition.jsonl`), `Seeds` (tested; not golden because it needs only integer ids and a hash), `Eval` (golden
`eval.jsonl`), and the on-repeat reason: the line needs the 21-day rate above 1.0 **and** 21-day positive mass of at
least 2.5, so it is only said of a track finished about three times in the last three weeks. The string now says
"On repeat lately" (it said "this month", which the code never decided). `TasteConstraintAdapter` (app) resolves the
listener's blocked artists and taste changes to one `TasteConstraints` snapshot: the planner never sees a
constraint it did not apply first.

**GOLDEN FILES CHANGED TWICE in this pass** (`Brain.SPEC_VERSION` is 2): once with the new files (`why`, `mixes`,
`snapshot`, `repetition`, `eval`) and once more when the planner stopped removing a mix's own seeds from the pool
(deviation 85). The iOS port must copy `brain/src/test/resources/brain-golden` as it is now and match every line
bit for bit.

### Task 5: `BrainSwitch.engine`

`BrainSwitch.engine()` is `legacy` or `brain` (default `brain`), a DataStore int; Settings > Developer mode >
Maintenance has "Music engine" (a switch; the subtitle says to regenerate the daily mix to see the change) and
"Background music search". `BrainEngineChoice.useBrain(engine, hasState)`: the brain builds the mixes only when it is
on and has played at least 10 different tracks (deviation 90); otherwise the legacy mixes build exactly as before
(`DailyMixStateHolder.updateDailyMix` falls through to its old code unchanged; `DailyMixPersistenceTest` and the whole
`data/recommendation` suite are untouched). `AiDailyMixWorker` (which has no AI in it) does nothing while the brain
owns the mixes, because it would publish the legacy mixes over them.

### Task 6: Daily Mix and Your Mix with new music

`BrainMixes` builds a mix: a library shortlist (the legacy ranking's 300, favourites, 100 day-seeded deep cuts; about 500
tracks get a recording id, not the whole library), the cached radios of the day's seeds (`BrainRepository.cachedRadio`,
never the network), the artist lists, the persisted edges, the served log; `MixPool` filters (autoplay's filters, the
library already having the recording, ignored three times, blocked and taste changes), `MixPlanner` plans, `ServedListStore`
saves. Daily Mix is 30 and ends on a familiar track; Your Mix starts with 60. The share of new music is 0.25 (Your Mix 0.8 of it)
times the Familiar / Blend / Discover factor (0.4 / 1 / 2), capped at 0.6. A track is never in two mixes of one day (the
other surface's rows are excluded; the planner also takes a penalty of up to 1.2 for what was served often in 30 days);
a mix is the same all day and is read back after a restart without loading the brain (`BrainMixes.saved`); "Regenerate"
draws another list and counts against the log. A saved row whose song has left the library, or that a taste change or
a blocked artist now excludes, is dropped when the mix is read back. A mix built when no radio was cached is built again,
once, after 10 minutes if radios exist by then.

UI: `MixSongRow` (reason line under the row; Save for a track from outside the library, through `EngineTrackSaver`), in both
screens; a play of either mix announces itself (`expectMixPlay` -> `BrainPlayTags.expect`) so every row carries the
surface, the list, its arm and its reason into the queue and the event log. The two legacy saved-id keys keep the
library-only subset. The legacy `MusicDiscoveryRepository` eager import stays off.

### Task 7: the evaluation harness

`Eval` is pure (`:brain`): test 1 (`skipPrediction`: prequential, last 8 weeks or 15% held out, exact AUC, session-bootstrap
intervals with SplitMix64, calibration error) and test 5 (`listHealth`: 30 simulated daily builds with the listener playing
the Daily Mix, served log feeding the budget). `EvalTest` runs both on three synthetic listeners (loyal, explorer, repeater)
in CI; they prove the harness measures what it says and say nothing about any real listener. Measured on them with the
shipped settings: see the test output (`./gradlew :brain:test -i`); the served penalty of 1.2 is what brings the
distinct-track ratio over 0.60 (deviation 82).

**Test 1 on a real export** (the owner supplies a path; nothing in CI):

    export ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"
    PIXLAUDIO_EVAL_EXPORT="C:\path\to\play-events.jsonl" \
      ./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.BrainEvalTest" -i

The file is one `PlayEvent.encode()` line per play. On Android: Developer mode > Maintenance > "Export play events"
(writes `play-events.jsonl` to the app's folder: `adb pull /sdcard/Android/data/<package>/files/play-events.jsonl`). On iOS
`cat events-*.pbl > play-events.jsonl`. The report is printed and written to `app/build/reports/brain-eval.txt`. The legacy
score is rebuilt by `LegacyScoreReplay` from the real `MusicRecommendationEngine.rank` run once a day over every track the
export mentions, fed by the events before that day, with no imported history: see its comment for what that favours.
**No real-data number exists yet; none is claimed.**

What the synthetic listeners show (made-up, so only the harness is being checked): test 1 held-out AUC of the brain's prior
scorer 0.787 / 0.697 / 0.657 (loyal / explorer / repeater) against the artist-rate baseline 0.792 / 0.727 / 0.549 and
the track-completions baseline 0.631 / 0.559 / 0.655; calibration error 0.19 / 0.14 / 0.19. So on two of the three the prior
is not above the plain artist rate: that is what the real export has to answer, and spec 14 says what happens if the gap
is under 0.05 (the brain stays off for Home ranking and the gap is investigated before stage 3). Test 5 (30 days):
distinct-track ratio 0.668 / 0.837 / 0.713, largest artist share 0.100, novelty gap 0.017, same-day overlap 0, shortest list 30.

---

## Deviations 82 to 100 (continuing the list)

82. **The served penalty is 1.2, not 0.4.** Over 30 simulated days on the three synthetic listeners 0.4 gave a distinct-track
    ratio of 0.32 / 0.47 / 0.40, below the 0.60 that "done means" asks; 1.2 gives 0.67 / 0.84 / 0.72. The spec marks 0.4 as [E].
    `Repetition.MAX_PENALTY`; it needs the real export to be confirmed or changed.
83. **A mix draws a per-list jitter** (`MixPlanner.JITTER`, width 1.0 on the value, from `splitmix64(rid xor tieSeed)`), because the
    spec's tie-break only separates exact ties, so a mix built twice from the same state was the same list and "Regenerate" did nothing.
84. **Daily Mix is one list in 2a** (K = 1). Daily Mix 1..K is per taste island and needs 2b.
85. **In a mix the seeds are candidates.** `StationPlanner` removed every seed from the pool ("a seed is what the listener is
    hearing"). A mix's seeds are tracks the listener chose, so they belong in it; only `MODE_MIX` changed (golden `mixes` and `eval` lines moved).
86. **The second half of "ignored three times" is not done.** 6.1 says a serve that never started playing counts once per local day per
    surface when the listener opened that surface that day. That needs a "surface was opened" signal that does not exist yet (it is the
    `impression` table of stage 3). The log is written (`served_list`), so it can be added without a migration.
87. **Lane 2 is the artist's top songs and radio**, not the release diff of 6.3: the New releases row is not in this milestone, and the
    artist lists are what the ARTIST arm of the mixes reads today.
88. **Budget numbers follow the code**: `HostBudget` is 60 a day with a burst of 12 and 2 in parallel (stage 1), the spec's table says 80 and 6.
    The worker takes at most 40 of the day and leaves the last 4 tokens of the burst.
89. **Edges: only RADIO and RELATED types**, written per list (the list's old edges are replaced), none for an artist station's list;
    the cap of 64 per node is applied when the graph is built (`Graph.Builder`), not when an edge is written.
90. **"The brain has state" means 10 different tracks heard** (`BrainEngineChoice.MIN_HEARD_TRACKS`); the spec does not say.
91. **The remote switch is the existing config file**: `brain.refresh.enabled` is read from the copy `StreamRemoteFlags` saves. The file lives in
    the iOS repo (`remote/config.json`); adding the key there is the owner's one line, and until it is there the switch is on.
92. **`AiDailyMixWorker` is not deleted**: it does nothing while the brain owns the mixes and still builds the legacy mixes when the engine is `legacy`.
93. **The library shortlist is about 500 tracks** (legacy top 300, favourites, 100 day-seeded deep cuts), not the whole library: resolving every
    library track to a recording id would mint thousands of alias rows for music that may never be a pick.
94. **Your Mix is 60 tracks and then autoplay carries on**, not an endless station of its own; "endless" is what stage 1's autoplay already does at the end of a queue.
95. **`feedback` is created and unused** (above).
96. **The migration is tested against the exported schema on the JVM**; the device test could not run.
97. **On repeat** needs positive mass (see task 4); the line reads "On repeat lately".
98. **"Export play events"** (Developer mode) was added so test 1 can be run on an Android listener's history at all; it writes one file to the app's folder and uploads nothing.
99. **Mix rows are the listener's** (`chosen = true` in the tag): playing a mix is a choice, and the tracks are logged with their arm like a Home row's cards.
100. **Mix building loads the brain's state when today's mix is not saved yet** (`hasState`): that is the first open after 04:00, in the background. Reading a saved mix does not.

## Unverified on a device (all of it)

That a mix built on a phone has the shape the fakes give; that `brain.db` migrates on a phone that holds stage 1 data; that the refresh job is scheduled
(`dumpsys jobscheduler`) and runs on Wi-Fi while charging or idle; that a track from outside the library in a mix plays, restores after a restart
and can be saved; that the reason lines read well; that the Developer mode rows work; how long a mix build takes (`brain_mix_build` in the performance
report) and whether `brain_state_load` at the first open of the day is noticeable; that the worker's budget leaves autoplay enough; that Your Mix's 60
tracks plus autoplay feel endless. The 04:00 rollover and a time-zone change are tested only with a fixed zone.

## How to test (on a phone)

1. Install, listen to about ten different songs through, open Home: Daily Mix and Your Mix are the legacy ones (no state yet) or the brain's (state).
2. Play a song, let autoplay run once (this caches a radio), then in Developer mode press "Regenerate daily mix": the mix should hold some songs with a "Fans of ..." line and a Save button.
3. Kill the app, reopen: the same Daily Mix, and Settings > Developer mode > Music engine off + Regenerate gives the old mixes back.
4. `adb shell dumpsys jobscheduler | grep brain_refresh`: one job, a day period, unmetered.
5. Developer mode > Export play events, then `adb pull`, then the command above.

## What was run, and what it said (milestone 1 of 2a)

All on this machine, one Gradle build at a time, `ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"`:

    ./gradlew :brain:generateGolden :brain:test
    ./gradlew --continue :brain:test :app:testDebugUnitTest \
        --tests "com.theveloper.pixelplay.data.brain.*" --tests "com.theveloper.pixelplay.presentation.components.BrainCopyTest" \
        --tests "com.theveloper.pixelplay.presentation.viewmodel.*" --tests "com.theveloper.pixelplay.data.service.player.AutoplayPolicyTest" \
        --tests "com.theveloper.pixelplay.data.recommendation.*" :app:assembleDebug

Result: `:brain:test` 109 tests, 0 failures; `:app:testDebugUnitTest` (those filters) 457 tests, 1 skipped (the real-export test, which
needs `PIXLAUDIO_EVAL_EXPORT`), 0 failures; `:app:assembleDebug` built both split APKs. The whole `data/recommendation` suite
(including `RecommendationGoldenEqualityTest`) is in that run and untouched. The full `:app:testDebugUnitTest` (every test of
the app) was NOT run in one go; only the filters above.

Known nuisance seen four times: `Unable to delete directory ... app\build\kotlin\compileDebugUnitTestKotlin` or `hiltJavaCompileDebug`
(a Windows lock). Deleting the named folder and running again was enough.
