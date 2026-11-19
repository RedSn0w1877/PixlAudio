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

---

# Milestone 2 of 2a: "more ways in, more control"

Same branch, same rules: nothing below has run on a phone. "Works" means unit tests, compiling, and reading the code
path end to end. Deviations continue at **101**.

## What was built (per task)

### Task 1: InnerTube additions (spec 3.3)

- `InnerTubeMusicParsers`: `parseDiscography` (`browse MPAD<channelId>`, no params: the grid, newest first, each card a
  `ReleaseRef` with kind, year and the audio playlist id from its "Shuffle play" menu item), `parseAlbumAudioPlaylistId`
  (`microformat...urlCanonical`, the `OLAK5uy_` list), `parsePlaylistPage` (a `browse VL<id>` page **and** a browse
  continuation: the playlist shelf only, never an album page's own tracks; the old and the new continuation shape),
  `parseGetQueue` (`music/get_queue`), `parseSongSearch` (rows keep the artist run's channel id and read the length from the
  byline). `ReleaseRef` gained `kind`, `audioPlaylistId`, `thumbnailUrl`; `parseListItemRow` falls back to
  `playlistItemData.videoId`.
- `InnerTubeClient`: `browseContinuation`, `getQueue` (max 100 ids), `searchSongsRaw`, all on the `@YouTubeOkHttpClient`.
- `RadioSource` (`YouTubeRadioSource`): `discography`, `releaseTracks` (card playlist id, else one album page request),
  `playlistRows` (up to 3 pages), `hydrate` (bisects a 404 batch, at most 3 extra requests), `channelIdFor` (a songs search).
  Default bodies return null so existing fakes still compile.
- Fixtures cut by `tools/brain/cut_fixtures.py <raw> --2a` from `builds/music-brain-2026-11-17/yt/raw`
  (`discography`, `album_audio`, `playlist_album`, `playlist_top_songs`, `playlist_cont`, `get_queue`, `search_songs`, each
  with an `.expected.json` from the script's own plain extraction). No new probe was needed: every capture existed.
  `InnerTubeMusicParsers2aTest` holds them. The iOS parsers read the same files.

### Task 2: New releases (6.3)

`NewReleases` (`data/brain/NewReleases.kt`) keeps one small file (`brain_new_releases_v1.json`, no Room change): per
artist the `MPREb_` ids and folded titles seen, plus the releases found with their tracks. `NewReleaseRules` is pure and
tested: the first look at an artist is a **baseline** and surfaces nothing; an id first seen later is news unless its folded
title matches an older release of that artist (a re-release) or repeats inside one answer; the year must be this year
(or last year during January). A full carousel (10 albums or 10 singles/EPs on the artist page) reads the whole
discography (`MPAD`). Tracks of a new release (at most 2 releases per artist per check) come from the audio playlist.
The worker's **lane 2** (`BrainRefreshRunner.releaseTargets` / `releases`) checks the six artists checked longest ago,
ahead of the artist lists, with its own share of the day (14 requests, counted in the file) inside the worker's 40.
Home: `HomeDiscoveryStateHolder` shows "New from artists you play" after the Because rows, 12 cards at most, 2 per
artist and 3 per release, hidden under 6 cards; when it has cards the legacy "New from artists you enjoy" shelf is not
passed to the planner. A card's reason reads "New from <artist>" (`WhyLine.NEW_RELEASE`, arm NEW); playing it logs
surface `homeShelf`, id `brain_new_releases`.

### Task 3: stations

- **Album and playlist stations.** `Station.source` (`SOURCE_SONG/ARTIST/ALBUM/PLAYLIST`) and `Station.seedMediaIds`;
  `StationSeeds.pick` (most played, else the first, at most 2 per artist, 3 for an album, 5 for a playlist);
  `StationStateHolder.startAlbumStation/startPlaylistStation`; the controller seeds the pool from all of them
  (`seedingOf`), `StationPlanner` takes the closest of its seeds (not only `seeds[0]`: **golden unchanged**, a single seed
  gives the same bits), and none of the source's own songs lead the first batch (`StationStore.sourceIds`, memory only).
  Entries: an icon on the album page header, "Start station from this playlist" in the playlist options sheet.
- **An artist with no song in the library.** `BrainRepository.beginArtistStation`: the channel (a songs search, unless a cached
  radio names it), the artist page, the radio its own button starts; its first track is the seed, registered in `playable`
  and played; the page's top songs and the radio are stored as the artist list under the seed, so the first pool is a cache
  hit. Entry: "Start artist station" in the song options sheet (any song, any artist). The message for nothing found is
  now "Couldn't find music for X right now".
- **Similar artists' top songs.** `BrainRepository.similarArtistSongs`: the artist page's "Fans might also like", the top 3,
  their pages' top songs, cached 14 days under `radio_list` chip `+similar`. Pure core: `Candidate.fromSimilarArtist` gives arm
  SIM and `WhyLine.SIMILAR_ARTIST` names the artist whose list brought the pick ("Close to <artist>").
- **Re-seed.** When a station's pool runs under 15, the track it did best with (the latest finished or liked station play,
  from `Reaction.played`) joins the seeds (at most 2 per station); the pool is rebuilt from the cached radios plus its radio.

### Task 4: feedback controls (8.4, 8.5)

- **More like this**: a 14-day boost on the track's artist (a taste change, listed in Taste changes, undoable); the controller
  re-reads the boost into its pool and tail (`onTasteChanged`), so the next batch leans that way. Rows in the song options
  sheet and in the Why sheet.
- **Don't count this**: the control reads "Don't count this" (no "learn"). `LearnExclusion` flags the plays `learnExcluded`,
  takes their weight out of the tables at once (`Affinity.subtract`, pure, golden), writes a `feedback` row, and asks for a
  full rebuild (which makes the tables exact). Scopes in the UI: the song (song options, Why sheet) and the last hour
  ("How picks work"). The data layer also takes an album or a playlist (a set of recordings) and is tested; no screen offers
  those two yet. Undo is on the bar that follows.
- **Private session**: a switch in "How picks work", a "Private" pill at the top of the screen while it is on (a tap ends it),
  **off at every app start** (memory only). Every play that overlaps a span is logged `learnExcluded`; each span is a `feedback`
  row (`PRIVATE_SPAN`) with its end.
- **Less from X, second tap**: if the artist already has a reduce, the bar says so and offers "Mute X"; after a first reduce
  the bar offers "Mute X" next to Undo.
- **Swipe on an engine row** = `Session.weakNegative` (`h = 0.2`, pure, golden): `recentNeg` only, no event, no block.
  Only while the brain is in memory.
- **Refresh picks** on the Autoplay line: the tail is replaced by the next best picks in one edit (the old tail is excluded
  from that plan); with under 10 in the pool a deeper pool is built first.
- **Save on Recently Played** for a track the engine played that is not in the library (`EngineTrack.isEngineOnly`), on the
  full list; not on Home's pills.

### Task 5: repeat activation (5.6)

`RepeatActivation` in `:brain`: `History` (last 30 plays + one aggregate bucket), `b`, `feature` (`clip((B+4)/6, 0, 1)`);
`Candidate.actr` feeds features 10 and 11 (`Features.ACTR`, `ACTR_SQ`); both stay 0 when the caller gives nothing, so
every earlier golden line is unchanged. In the app, `ActivationFeatures` gives the mixes' library tracks their feature 10. **Golden file `stage2a.jsonl` is new** (see the loud note below).

### Task 6: Spotify facts sink

`BrainFactsSink` (`RoomBrainFactsSink`): `SpotifyRepository.toSpotifySongEntity` hands every track to it; it queues in
memory and writes `spotify_fact` (brain.db **version 3**, migration `V2_TO_V3`, schema `3.json`, migration test) in batches
2 s later. `SpotifyTrack.explicit` and `album.release_date_precision` are new fields and `PLAYLIST_TRACK_FIELDS` asks for
`explicit` and the album's release date; `getArtistAlbums` asks for 10, not 50. `getArtistTopTracks` no longer calls the removed
endpoint: the browse screen gets the tracks of a catalogue search for the artist that credit them. **The off switch**:
Developer mode "Use my Spotify music to shape picks" (default on); off, the sink drops what it holds and the next rebuild
leaves out the imported Spotify plays and the top lists and follows (`AppStateRebuilder`). Nothing is named "training".
**Chips**: `ChipLabels` (pure, golden) says whether a radio chip names a genre, a decade, a mood or only steers the radio;
chip labels were already kept in `radio_list.chipsBlob`, `BrainRepository.chipTagsOf(seedRid)` reads them mapped. No atlas
ids yet: unknown stays unknown.

### Task 7: "Why this song" facts

`WhyFacts` (pure, golden): up to two true facts, in order: how many of the last plays by the artist were finished, how often
the track was finished, the year. `WhyFactsLoader` reads them when the sheet opens. A genre or mood fact needs the atlas
(2b) and is not shown. An engine track in the queue has no year (the media item carries none), so its sheet usually shows
the first two.

### Task 8: the AutoplayController harness

`AutoplayControllerHarnessTest`: the controller built over mocks and fakes, with a queue the test owns. It holds the
trigger (two left asks for a radio around the playing song; three left does not; repeat and the off switch ask nothing;
Cast asks nothing and Spotify Connect does; a queue that is still being attached waits). What it cannot reach: the media items
the controller builds carry their `brain.*` extras in an Android `Bundle`, which the JVM stubs do not hold, so the engine's
rows and the built items' extras stay with the phone checklist.

## Deviations 101 and on

101. **Release checks rotate**: six artists a run, the one checked longest ago first, so the top 20 are all covered in about
     four days, not daily (the host budget is 60 a day, deviation 88). Spec 6.3 says daily.
102. **The releases lane runs before the artist lists** (spec lane 2 is releases; milestone 1 had put the artist lists there).
103. **The releases lane has its own ceiling**, 14 requests a day, counted in the file and subtracted from the worker's 40.
104. **A release's audio playlist id comes from the card's "Shuffle play" menu item first**, which saves the album page request;
     the album page's canonical URL is the fallback, as the spec says.
105. **The row needs 6 cards and shows at most 2 per artist** (the page rules of 8.1), so a listener with fewer than three
     artists with news sees no row. It is not a Home hero or a mix ("Just Dropped" is 2b).
106. **The artist's channel id for a release check** comes from the file, a cached radio row, else one `next` for the seed; that
     request is spent outside the releases lane's count (like deviation 79).
107. **Album and playlist stations use the song station's rules in the pure core** (`SEED_STATION_SONG`); the source is app-only.
108. **A station's closest seed is a flag**, `StationPlanner.Request.closestOfSeeds` (default false): with it a station-mode track
     must be near the nearest of the seeds, without it near the first one as before. The app sets it for an album or playlist station
     and for one that was re-seeded. Default false keeps every earlier plan, so **no existing golden file changed**.
109. **The source's own songs are out of the whole first batch** (20), not only the first 10, because the plan is one batch.
110. **Re-seed**: the best-received track is the latest finished or liked station play (not "best by affinity").
111. **An artist station for an unknown artist costs up to 3 requests before sound** (songs search, artist page, radio) and 4 more
     for the similar artists at the first pool build.
112. **Don't count this scopes in the UI**: song and last hour. Album and playlist exist in the data layer only.
113. **The quick subtraction grades the play without its context** (past completions, skip streak), so it can differ from what
     was applied; the full rebuild that follows is exact.
114. **Private session is memory only**; a span is written to `feedback` when it opens and closed when it ends. A play that
     overlaps a span at all is private.
115. **More like this is an artist boost for 14 days** (no tags yet); the spec's "genre controls" wait for 2b.
116. **A swipe writes no event**: the session nudge is all it does, so the log stays raw facts.
117. **Refresh picks** excludes the current tail from the re-plan; with a pool under 10 it builds a deeper one first.
118. **Save on Recently Played is on the full screen only.** Home's pills have a width calculation that a button would break.
119. **Repeat activation reaches the mixes, not yet autoplay or stations.** `ActivationFeatures` reads the last 90 days of a track's
     plays (`PlayEventDao.stampsOf`, one query per 800 recordings), grades each without its moment's context for its trust weight
     `c`, and gives `BrainMixes`' library shortlist its `Candidate.actr`. The spec keeps the last 30 plays plus an aggregate
     bucket; the pure `History` does that, the app's 90 days stands in for the bucket. Autoplay and station candidates still
     carry `actr = 0` (their library tracks come from a different path); no golden line depends on it.
120. **`getArtistTopTracks` takes the artist name** and uses a catalogue search; Spotify's own top tracks are gone.
121. **Chips are mapped, not turned into atlas ids** (there is no atlas in 2a).

## For the iOS port (milestone 2)

- **GOLDEN FILES: one new file, `stage2a.jsonl`, and README.md / MANIFEST.sha256 changed to list it. No existing golden file
  changed** (the new `Candidate` fields and `Features` 10 and 11 are zero by default, `Request.closestOfSeeds` is false by default, and `WhyLine`'s SIM argument falls back to the candidate's own artist when no list named
  one: the old behaviour for the old inputs). Copy `brain/src/test/resources/brain-golden` as it is and match every line
  of `stage2a.jsonl` bit for bit: `subtract`, `repeat`, `chip`, `weakNegative`, `whyFacts`, `plan` (README says how to replay).
- New pure types to transliterate: `Affinity.subtract`, `RepeatActivation`, `ChipLabels`, `WhyFacts`, `Session.weakNegative`
  and `Session.SWIPE_H`, `Candidate.fromSimilarArtist` and `actr`, `Features.ACTR`/`ACTR_SQ`, `StationPlanner.Request.closestOfSeeds`.
- Parsers and fixtures: the seven `*.json` + `*.expected.json` pairs in `app/src/test/resources/innertube` (copy them);
  the discography card's audio playlist id sits under `menu.menuRenderer.items[].menuNavigationItemRenderer...
  watchPlaylistEndpoint.playlistId` (`OLAK5uy_`), and a playlist page's continuation token is the last
  `continuationItemRenderer`.
- Behaviour to mirror: the New releases rules (`NewReleaseRules`), the rotation and the budget; the second tap on "Less from"
  offers mute; "Don't count this" is flag + subtract + rebuild + a `feedback` row; Private session is off at app start;
  a swipe on an engine row is `weakNegative` only; the station sources (album 3 seeds, playlist 5, 2 per artist, the
  source's songs out of the first batch); re-seed under 15; similar artists' top songs as arm SIM; the "Use my Spotify music
  to shape picks" switch.
- Words (Android strings, English): "New from artists you play", "New from %1$s", "Close to %1$s", "Start artist station",
  "Start station from this album / playlist", "More like this", "Don't count this", "This won't shape your picks.",
  "Don't count the last hour", "Mute %1$s", "Private session", "Private", "Refresh picks", "You finished %1$d of your last
  %2$d plays by this artist", "You've finished this %1$d times", "Out in %1$d". None says "learn".

## Still not verified on a device (new in this milestone)

That a release check finds a real new release and that its audio playlist plays; that the row shows after a day on Wi-Fi;
that an artist station starts for an artist the library lacks, and how long it takes before sound (three requests); that an
album or playlist station's first batch leaves the source's songs out and feels like the album; that a re-seed happens when a
long station runs low; that "More like this" visibly changes the next batch; that "Don't count this" leaves the picks after the
rebuild and that Undo brings the plays back; that the Private pill shows above every screen and a private play leaves
the picks alone; that a swipe lowers similar songs; that Refresh picks changes the tail; the Save button of Recently Played; the
Settings row for Spotify; the `brain.db` 2 to 3 migration on a phone holding stage 1 or milestone 1 data (the JVM test holds
the statement against the exported schema; the instrumented migration test was extended and has not run).

## How to test (on a phone), milestone 2

1. Developer mode on. Play ten songs through; wait a day on Wi-Fi (or run the job from Developer options): `dumpsys jobscheduler | grep brain_refresh`.
2. Home: "New from artists you play" after a day or two, once an artist you play has a release this year the file had not seen.
3. A song menu: "Start artist station" on a song of an artist you never played; watch for sound within a few seconds.
4. Album page: the radio icon at the top right; playlist: the options sheet. Skip around; let the station run long.
5. Song menu: "More like this", "Don't count this" (then Undo), "How picks work": Private session on, play, off.
6. Queue: swipe an engine row away, then "Refresh picks".
7. Recently Played (the full list): a track the engine played has "Save".

## What was run, and what it said (milestone 2 of 2a)

One Gradle build at a time, `ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"`. Milestone 1 was first confirmed: it compiled and its tests
passed before anything was added.

    ./gradlew --continue :brain:generateGolden :brain:test :app:testDebugUnitTest :app:assembleDebug

- `:brain:test`: **126 tests, 0 failures**. `git status` after `generateGolden`: only `stage2a.jsonl` (new), `README.md` and `MANIFEST.sha256`
  changed; every earlier golden file is byte for byte what it was. (A first draft of `StationPlanner` changed `session.jsonl`; that is why
  the multi-seed rule is the flag `closestOfSeeds`, off by default, and not the new default.)
- `:app:testDebugUnitTest`, the whole task, once: **2310 tests, 1 failed, 2 skipped**. The failure was `BrainCopyTest > a reason line is a true
  statement or nothing`, which still asserted that `new_release` shows nothing (true in stage 1; this milestone serves it). The test was
  updated for "New from X" and "Close to X" and the class passes in a second run (`--tests BrainCopyTest`, `NewReleasesTest`,
  `AutoplayControllerHarnessTest`: BUILD SUCCESSFUL). `RecommendationGoldenEqualityTest` and the rest of `data/recommendation` ran in the
  whole task and are untouched. The 2 skipped are the real-export test (needs `PIXLAUDIO_EVAL_EXPORT`) and one older skip.
- `:app:assembleDebug`: both split debug APKs built (`app-arm64-v8a-debug.apk`, `app-armeabi-v7a-debug.apk`).
- Earlier in the milestone the whole thing was built and tested four times; each time the failures were mine and small: a test name with a `;`
  or a `:` (the JVM rejects them), a stale count in `NewReleasesTest` (requests older than a day leave the count, on purpose), a
  forgotten title for a release whose tracks could not be read (a real bug: it was then taken for a re-release of itself; fixed in `check()`),
  and a smart-cast error. `HedgedRaceTest > no strategies means no winner` failed once with "uncaught exceptions before the test started": my
  harness test's coroutines had thrown on a background thread (building an Android media item on the JVM); the harness now keeps them.
- **Not run**: the instrumented migration test (extended for 2 to 3), anything on a device.
- Windows file locks (`Unable to delete directory ...`) did not appear this time.

# Review pass of 2a (Android): findings checked, fixed, and what changed

An independent reviewer read the whole 2a diff. Each finding was checked against the code; all of the majors were real. This
section is what changed and why. Deviations continue at 122.

## What changed

1. **Private session and "Don't count this" now reach the legacy counters too.** `PlaySink.isPrivate(startMs, endMs)` (the
   recorder answers from `PrivateSession`); `ListeningStatsTracker.finalizeCurrentSession` passes `learnExcluded` (the play's
   `LEARN_EXCLUDED` flag or a private overlap) into `persistPlayback`, which then skips `MusicTasteRepository.record` and
   `DailyMixManager.recordPlay`. The play still goes to `PlaybackStatsRepository.recordPlayback`, so it stays in Recently Played
   (that is what "Don't count" promises: the plays stay in the log and in the history).
2. **The seed queries skip `learnExcluded` plays.** `SeedDao.recentChosen` (`flags & 4104`), `recentlySaved` and
   `PlayEventDao.ignoredThreeTimes` (`flags & 4096`, so an excluded play is as if it never happened: not a seed, not a skip
   toward the 60-day ban, not a "heard for 30 s" that lifts one). **SQL only, no schema change.** The JVM has no SQLite
   here, so these three statements are checked by reading, not by a test (see "Unverified").
3. **"Don't count this" reaches imported Spotify plays.** `ReplayMerger.rebuild(..., excluded = Map<survivor rid, cutoff ms>)`:
   every play of a recording that started at or before the time of an active `DONT_COUNT` / `TARGET_SONG` feedback row replays as
   `learnExcluded`, stored or imported, through the merge table (so a later merged recording is still found). `AppStateRebuilder`
   reads `FeedbackDao.active()` for it (and an Undo makes the row inactive, so the plays count again at the next rebuild).
   `ListeningHistoryDao.countOfSpotifyTrack(spotifyId)` lets the control say how many imported plays it left out. Album,
   playlist and last-hour rows have no imported side (no screen for the first two; imports are old for the third).
4. **Don't count: no row and no rebuild for nothing, and Undo gives the play in flight back.** `excludeRecordings` and
   `excludeLastHour` return null when there is no stored play, no imported play and no play in flight. `markFlag` returns whether
   a play was flagged, `unmarkFlag` takes it off, and `Excluded` remembers the song for Undo. A play in flight alone needs no rebuild.
5. **App start does no brain work.** `DailyMixStateHolder.updateDailyMix` (non-forced, engine brain): with a mix saved today it
   restores the saved ids from the preferences (a small cache) and stops; the brain is read when a surface that shows a mix opens:
   `onMixSurfaceOpened(full = true)` from Daily Mix and Your Mix (reads and, if needed, builds), `full = false` from Home (waits out
   the first 4 s, then reads only today's saved served-log rows: no state load, no build). A new day builds as before, after the
   quiet period. `BrainSwitch.brainStateSeen()` (a DataStore flag set the first time the brain has state) tells app start whether
   the brain may own the mixes at all, so an install without state builds the legacy mixes at once instead of after 4 s.
6. **`brain.db` trouble is the legacy mixes, not a crash.** `publishBrainMixes`, `publishSavedBrainMixes`, `removeItem`
   and Regenerate catch everything but cancellation and fall back.
7. **The request budget counts requests.** New `RequestLedger` (a timestamp per request in `brain_state` `request_ledger`, read
   only when a request is about to go out): `HostBudget(onSpend, dayHasRoom)` records every token it gives out and refuses the
   63rd request of a day whoever asks, a process restart included. `AppRefreshWork.requestsLeft` stops the worker at 40 of the 60
   requests (all callers counted), so the foreground keeps at least 20. Lane 1 now leaves `RELEASES_RESERVE` = 4 requests of the
   window for lane 2 (`RefreshWork.releasesReserve`), so a long list of uncached radios cannot starve the releases lane.
8. **Minor fixes.** (a) A play in flight when a private session was on is saved as excluded in the open-play record, so recovery
   after a process death keeps it out. (b) The channel id fallback of the releases path needs the same folded name, and
   "Various Artists" / "Unknown Artist" are never a top-artist seed. (c) The New releases row drops a card the library has
   since (`NewReleases.row(..., library)`). (d) A saved mix is kept while it has at least 3 songs (`MIN_SAVED_SONGS`), not 10,
   so it stays the same list all day. (e) The shortlist cache is one immutable `Slice` in a `@Volatile` field and its key
   includes the favourites. (f) Spotify Connect autoplay lookups spend a bucket (24 burst, 600 a day) and a pick that is not
   looked up is "not now". (g) Edges: a chip or continuation list has its own edge type (`TYPE_CHIP` = 3, weight `Graph.CHIP`),
   the new rows go in before the stale ones are deleted (`GraphEdgeDao.deleteStale`), so a kill leaves too many edges, never none.

## Deviations 122 and on

122. **A launch on a day that has a saved mix reads no brain data**; Home shows the saved library ids until a mix screen opens (full
     read) or Home's mix card reads the saved served-log rows after 4 s (reasons and outside tracks then appear). Spec 8.2 says the
     mixes are read from the saved plan; the plan here is `brain.db`'s served log, so a first-frame read of it is what was cut.
123. **Don't count on an album or a playlist reaches stored plays only**, as before; the rebuild reads `TARGET_SONG` rows only.
124. **A private play still writes `PlaybackStatsRepository`** (Recently Played, listening time), not the counters that rank.
125. **`graph_edge.type` 3 is "chip"**: no schema change (the column was already an Int), but iOS must write it the same way.
126. **The daily request ledger is a new `brain_state` key** (`request_ledger`, up to 240 timestamps), not a table.
127. **`UserPreferences` `brain_state_seen`** (DataStore) is new: iOS needs an equivalent cheap "brain has state" flag if it wants
     the same start-up behaviour.

## For the iOS port (review pass)

- **No pure-core change, no golden file changed.** `./gradlew :brain:test` is the check.
- Mirror: `isPrivate` skipping the legacy counters, the three SQL changes of (2) (exclude `learnExcluded` from seeds, saved and
  ignored-three-times), the rebuild's `excluded` map, the request ledger, the lane-1 reserve, `TYPE_CHIP` edges.

## How to test on a phone (the stage 2a "Done means", as steps)

Developer mode on. Spotify connected, at least ten songs played through, Wi-Fi.

1. **Mixes with new music.** Open Daily Mix and Your Mix: some rows say why ("Fans of X also play this") and offer Save; one song is in
   one mix only; play a row from outside the library: it must play. Kill the app and open it again: the same list (nothing new
   drawn). Regenerate on Your Mix draws another list and leaves the Daily Mix alone.
2. **The first seconds.** Cold start the app on a day that already has a mix: no pause, Home shows a mix at once, and in
   Developer options `brain_state_load` has no timing until a mix screen is opened or a track ends (that is "no brain work at app start").
   Settings > Developer: engine "Legacy" gives the old mixes; with a fresh install (no plays) the mixes appear at once, built the old way.
3. **The one job.** `adb shell dumpsys jobscheduler | grep brain_refresh`: unique, periodic, unmetered, battery not low, scheduled only
   after the first mix was opened. Turn the switch off (Settings): the next run does nothing. The ledger: in Developer options
   (or `brain_state` `request_ledger`) the day's requests never pass 60.
4. **New from artists you play.** After a day or two on Wi-Fi: the Home row appears with six or more cards, each with a "New from X" line;
   a card you Save leaves the row.
5. **Stations.** Song menu > Start artist station for an artist you have never played (sound within a few seconds); the radio icon on an
   album; the options sheet of a playlist. Let one run long.
6. **Controls.** "More like this" (next batch leans that way); "Don't count this" on a song you have played, with Undo; on a song whose
   plays came from your Spotify history only (the sheet should say it left plays out); Private session on, play three songs of one
   artist ten times, off: **Daily Mix and Home stay as they were**, the songs are in Recently Played (and in the listening time of Stats: deviation 124).
7. **Cast and Connect.** Cast: Autoplay is off with its words; Connect: picks that Spotify does not have never reach the queue.

## Nobody has verified on a device

Everything above. In particular: the first-frame behaviour of Home (saved ids, then the brain's rows ~4 s later), the whole request
ledger on a real day (the bucket, the daily gate and the worker's 40), that lane 2 now reaches a real release check, the three SQL
statements that skip excluded plays (no SQLite on the JVM here), `ReplayMerger`'s imported-play exclusion on a real Spotify export, the
`brain.db` 2 to 3 migration on real data, and the instrumented migration test (not run).

## What was run, and what it said (review pass)

One Gradle build at a time with `ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"`.

- `./gradlew :app:testDebugUnitTest --tests "...data.brain.*" --tests "...presentation.viewmodel.*" --tests "...data.service.player.*"`: BUILD SUCCESSFUL
  (the first two attempts failed to compile: a lost line in `DailyMixStateHolder`, a broken insert in `MixTestFakes`, `markFlag` now returns Boolean).
- `./gradlew --continue :brain:test :app:testDebugUnitTest :app:assembleDebug`: **BUILD SUCCESSFUL** (9m 32s). `:brain:test` 126 tests, 0 failures (no pure-core change, so no golden file was regenerated and `git status brain` is clean); `:app:testDebugUnitTest` (the whole task, once) **2326 tests, 2 skipped, 0 failures, 0 errors** (the new tests: `RequestLedgerTest`, and additions to `LearnExclusionTest`, `DailyMixBrainPathTest`, `ListeningStatsTrackerBrainTest`, `BrainMixesTest`, `GraphEdgeStoreTest`, `NewReleasesTest`, `BrainRefreshReleasesLaneTest`); `:app:assembleDebug` built.
- Not run: the instrumented tests (migration; the three changed SQL statements have no JVM test).
