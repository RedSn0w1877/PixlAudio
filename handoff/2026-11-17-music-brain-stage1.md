# Music brain, stage 1 (Android): running handoff

Branch `music-brain` (cut from `new-ui`). The contract is `docs/brain-spec.md` (v1.1). This file is what is
actually in the repo: what was built, where it differs from the spec, what was cut, what nobody has run on a
phone. Each milestone appends a section. The next agent starts here.

**Nothing below has been run on a device.** No phone was available. Everything is verified by unit tests, by
compiling, and by reading the code paths end to end. "Works" in this file means exactly that.

---

## Milestone 1a: "a track from outside the library plays, and the queue does not end"

Spec section 14, stage 1, items 1 to 5.

### What the listener gets (once verified on a phone)

- When two or fewer tracks are left in the queue, five more are appended: radio neighbours of the last tracks
  the listener chose. Rows the library already has play from the library; the rest play from YouTube audio
  with no library row.
- The queue shows an Autoplay line with a switch above the engine's picks (or at the end of the queue while
  there are none).
- Offline, or with the YouTube source off, picks come from the library and the line says "From your library".
- A track the engine played shows in Recently Played. Like or "Add to playlist" makes it a library track.
- Kill and reopen: engine items come back as engine items (the Autoplay line is drawn again).

### Files

**New module `:brain`** (Kotlin JVM, stdlib only; `./gradlew :brain:test`, 31 tests)

| File | What |
|---|---|
| `brain/build.gradle.kts`, `settings.gradle.kts`, `gradle/libs.versions.toml` (`jetbrains-kotlin-jvm`), root `build.gradle.kts` | the module; `app` depends on it |
| `BrainHash.kt` | `fnv1a64`, SplitMix64 (`splitmix64`, `Rng.uniform`, `Rng.gaussian12`) |
| `BrainText.kt` | `fold`, `cleanTitle`, the ten `FLAG_*` bits, `CleanTitle.identityFlags` |
| `FoldTable.kt`, `TitleRules.kt` | GENERATED from `brain/tables/fold-v1.tsv` (1,278 rows) and `title-rules-v1.tsv` (46 rules) |
| `Ids.kt` | `nameKey`, `mint`, `resolveTrack` (the four steps + merge), `resolveArtist`, `MemoryAliasTable` |
| `Arms.kt` | `Arm` and `Surface` codes and names (the codes `play_event` stores; never renumber) |
| tests | `BrainHashTest`, `BrainTextTest`, `IdsTest`, `ArmsTest`, `PurityTest`, `TablesInSyncTest` |
| `tools/brain/gen_tables.py` | `seed` writes fold-v1.tsv from Unicode data; `kotlin` writes the two generated files |

**Brain database** `app/.../data/brain/store/`: `MusicBrainDatabase` (`brain.db`, version 1, schema exported to
`app/schemas/com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase/1.json`), `BrainEntities.kt` (the
eight stage 1 tables), `BrainDaos.kt`, `RadioRowCodec.kt` (gzip JSON of parsed rows). `di/BrainModule.kt`
provides it; no destructive fallback in any build type.

**InnerTube** `data/youtube/`: `InnerTubeClient.nextRadio`, `nextRadioContinuation`, `browseMusic`, private
`postText`; `InnerTubeMusicModels.kt`; `InnerTubeMusicParsers.kt` (`parseRadio`, `parseRelated`,
`parseArtistPage`, `isJunkRadio`, `RadioPager`). Fixtures: `app/src/test/resources/innertube/*.json` with
`*.expected.json`, cut by `tools/brain/cut_fixtures.py <raw folder>`.

**Playable path** `data/brain/`: `EngineTrack.kt`, `PlayableStore.kt` (implements
`source/EphemeralTrackLookup`), `EngineTrackSaver.kt`, `BrainExtras.kt` (`BrainItemTag`, the `brain.*` extras),
`BrainIdentity.kt` (`TrackResolver` over the alias tables), `BrainRepository.kt` (radio cache, junk
fallbacks), `LibraryIndex.kt`, `BrainSwitch.kt`, `source/RadioSource.kt`, `source/YouTubeRadioSource.kt`,
`source/HostBudget.kt`.

**Autoplay** `data/service/player/AutoplayPolicy.kt` (pure), `AutoplayController.kt`.

**UI** `presentation/viewmodel/BrainSessionStateHolder.kt`, `presentation/components/AutoplayDivider.kt`.

**Changed**

| File | Change |
|---|---|
| `data/spotify/SpotifyStreamProxy.kt` | `storedVideoIdFor`: `spotify_songs` first, then `EphemeralTrackLookup`, then the on-demand match |
| `data/spotify/SpotifyRepository.kt` | companion `syntheticIdForVideo`, `unifiedArtistId`, `unifiedAlbumIdByName`; `importYouTubeMusicTracks` now writes only the rows it touched (`onlySpotifyIds`, `onlyPlaylistId = BROWSE_ID`) |
| `data/service/player/DualPlayerEngine.kt` | `appendToQueueEnd`: keeps the full queue when the active player is windowed |
| `data/service/MusicService.kt` | injects `AutoplayController`; calls `attach`/`detach`, `onTransition` (after `enforceBlockedArtists`), `onQueueEnded` (STATE_ENDED), `onTransitionFinished`, `onPlayingChanged`; snapshot save/restore of the brain tag |
| `data/model/PlaybackQueueSnapshot.kt` | five optional fields `brainSurface`, `brainSurfaceId`, `brainRecId`, `brainSlot`, `brainArm` |
| `data/media/MediaMapper.kt` | the fallback `Song` built from a media item now carries `spotifyId` (from the extra, else from the `spotify://` uri) |
| `data/repository/MusicRepositoryImpl.kt`, `di/AppModule.kt` | `setFavoriteStatus(true)` imports an engine track first |
| `presentation/viewmodel/PlayerViewModel.kt` | `brainSession`; `observeSongs` adds engine songs (Recently Played) |
| `presentation/viewmodel/PlaylistViewModel.kt` | adding to a playlist imports engine tracks first |
| `presentation/viewmodel/MediaControllerSyncStateHolder.kt` | reports which queue items are engine-owned |
| `presentation/viewmodel/PlaybackDispatchStateHolder.kt` | "Add to queue" inserts above the Autoplay line |
| `presentation/components/QueueBottomSheet.kt` | the Autoplay line |
| `data/preferences/UserPreferencesRepository.kt` | `brain_autoplay_enabled` (default on), `brain_youtube_source_enabled` (default on) |
| `res/values/strings_player.xml` | five `queue_autoplay_*` strings (English only; other locales fall back) |
| `CLAUDE.md`, `app/compose_stability.conf`, `.gitignore`, `docs/brain-spec.md` (copy of the spec) | |

### Item 1: the playable path (spec 7.2, decision D4). Verdict: taken as specified, not the fallback

How it plays, traced in code:

1. `AutoplayController.buildPool` turns a radio row into an `EngineTrack` and calls
   `PlayableStore.register` (a `playable` row; also an in-memory map) BEFORE any media item is built.
2. The item is `MediaItemBuilder.build(track.toSong())`: media id `unifiedSongId(synthId)`, uri
   `spotify://<synthId>`, title, artist, album, length and artwork in the metadata and extras, plus the
   `brain.*` extras.
3. ExoPlayer opens the uri; `DualPlayerEngine.resolveStreamUri` asks `SpotifyStreamProxy`;
   `validateId` passes (22 hex characters); `resolveStreamUrl` tries SpotiFLAC (returns null: no
   `spotify_songs` row) then `resolveViaYouTube`, where `storedVideoIdFor` finds the video in `playable`.
4. No row is written to `spotify_songs`, `songs`, `albums`, `artists` or any playlist.

Every reader that assumes a `songs` row, and what it does with an engine track:

| Reader | Code | Result |
|---|---|---|
| Queue list, mini player, player screen | `MediaControllerSyncStateHolder.resolveSongFromMediaItem` falls back to `MediaMapper.resolveSongFromMediaItem` | shows title, artist, album, artwork, length from the item. `artistId` and `albumId` are -1, so "Go to artist / album" behaves as it does for any external item |
| Current-song hydration | `PlayerViewModel` `getSong(id)` collectors (`:961`, `observeSong`, `selectSongForInfo`) | return early on null; the item's own `Song` stays |
| Notification, lock screen, Auto, Wear now-playing | built from the media item's metadata | fine |
| Favourite heart | not in `favorites` so it shows off; pressing it goes through `MusicRepositoryImpl.setFavoriteStatus`, which imports the track first | Like = Save |
| Notification / widget / watch Like | `MusicService` `:1441`, `:3339` call the same repository method | imports first |
| Lyrics | `LyricsStateHolder.loadLyricsForSong(song)` works on the `Song` (title, artist, length, `spotifyId`) | online lookup works without a row. **A fetched lyric is cached in the main database's `lyrics` table under the engine track's id** (a cache row, not a library row) |
| Wear lyrics | `resolveWearLyrics`: `getSong` null | no lyrics on the watch for an engine track |
| Artwork | `artworkUri` on the item (an https thumbnail) | fine; `resolveRepositoryArtworkUri` is only the fallback |
| Blocked artists | `enforceBlockedArtists` reads `EXTRA_ARTIST_IDS` and the artist text | works: engine tracks carry `unifiedArtistId(name)`, the same id a library artist of that name has; blocked artists are also removed when the pool is built |
| Spatial audio, Sing, ReplayGain | keyed by `spotifyIdOf(item)`; their lookups return null | they do nothing for an engine track |
| Automatic studio | `AutomaticStudioManager.candidates`: `getSong` null | skipped |
| Stream prefetch and the listening cache | `spotifyStreamProxy` + `audioCacheManager` by spotify id | prefetch works through `playable`. **The listening cache may add a `song_cache` row** for an engine track that played (a cache table) |
| Queue snapshot save/restore | id, uri, title, artist, album, artwork, length were already saved; the five brain fields now too | restored `spotify://<synthId>` resolves through the `playable` table (its row survives the restart) |
| Recently Played | `PlayerViewModel.observeSongs` adds `PlayableStore.songsFor(missing ids)` | listed and replayable |
| Listening stats tracker | records by song id, no join | **the legacy counters (`feedback_v1`, `song_engagements`) are still written for engine tracks**: skipping them is item 6 (milestone 1b) |
| Stats screens | join on the library | engine plays do not show (spec: until stage 2) |
| Android Auto browse | `AutoMediaBrowseTree`: `getSong ?: return null` | not browsable; plays if it is in the queue |
| Cast file server | `MediaFileHttpServerService.resolveSongForServing` returns null | **an engine track cannot be cast.** Autoplay is off during Cast, but an engine item already in the queue will fail there (spec [S2]) |
| Spotify Connect | not traced (spec [S2]) | Autoplay is off during Connect |
| `resolveMediaItemsWithTrustedArtworkGrants` | passes unknown ids through | fine |

Restart with an engine item in the saved queue: covered by `PlaybackQueueSnapshotBrainFieldsTest` (fields) and
`PlayableStoreTest` ("after a process restart the saved queue still resolves, from the table").

Appending onto a windowed queue (7.2 point 5c): the reviewer's worry was right. A plain `addMediaItems` on a
windowed player makes `DualPlayerEngine.onTimelineChanged` rebuild the full queue from the 200-item window.
`appendToQueueEnd` now records the expected full queue and the timeline listener adopts it when the window
still ends where the queue ends. **Reasoned from code only; reproduce with a 200+ queue after a crossfade.**
User edits on a windowed queue (add, remove, move from the UI) still collapse the queue to the window: that
was already so and is not changed here.

### Item 2: InnerTube

Done: `next` (song radio, artist radio), continuation (token in the body), the Related tab, the artist page.
Parsers follow the rule list of 3.3 (tree walk by renderer name, wrapper unwrap, `unplayableText` skipped,
missing `musicVideoType` = UNKNOWN, shelves without a header skipped, both browse layouts). `RadioPager`
merges by video id, counts a page by its new ids, stops on a missing token, after 3 continuations, or after
two pages in a row with fewer than 5 new ids. `BrainRepository.store` then dedupes by recording id.
Junk detector as specified, with fallbacks Related tab, another audio id of the recording, artist radio.

Not done (the spec lists them for stage 2 or on demand): tuning-chip radios (chips are parsed and stored as
text), `MPAD` discography, album tracks, `get_queue`, charts, moods.

### Item 3: `:brain` and the database

A `kotlin("jvm")` module builds beside AGP 9.5.0-alpha07 with the configuration cache on ("Configuration
cache entry stored / reused" on `:brain:test` and on `:app` tasks). No fallback source set was needed.

### Item 4: Autoplay

Done: trigger on the full-queue count; anchors; pool in memory; batch of 5 in one edit; deferral during a
transition; the 1.5 s settle rule; backstop with the 10 s window; library-only fallback; off under repeat,
Cast, Connect and during a restore; hard constraints of 6.4 and filters of 6.1; radio budget.

### Item 5

Snapshot fields: done. Recently Played: done (rows resolve; see deviation 11 for "Save"). Like and "Add to
playlist" import through `importYouTubeMusicTracks`, whose two full rewrites are narrowed.

### Deviations from the spec, and why

1. **Tables are generated by a Python script, not by `./gradlew :brain:generateGolden`.** That task belongs
   with the golden vectors (item 7, milestone 1b). `TablesInSyncTest` fails when the generated Kotlin and
   the `.tsv` files disagree. The `.tsv` files are the source for the iOS port too.
2. **`track_alias` has a third column, `durationMs`.** Step 2 of the resolve compares "the two durations"
   and stage 1 has no `track_dna` to read the stored one from. It is the length known when the key was stored.
3. **`playable` has an extra column, `songId`** (`unifiedSongId(synthId)`, indexed). The history stores media
   ids, and that id is a one-way hash of `synthId`, so Recently Played could not find the row without it.
4. **fold-v1 has rows the spec does not list:** U+0080..U+00BF (NBSP and Latin-1 punctuation) to a space,
   `ª º ¹ ² ³` to `a o 1 2 3`, `µ` to Greek mu, `× ÷` to a space. A Latin letter with no ASCII base (open o,
   schwa, ...) is at least lower-cased so both cases fold together.
5. **`cleanTitle`: a segment that matches several flag rules sets every one of them** (the spec says first
   match wins). "(Acoustic Cover)" must set `cover`, or the cover filter of 6.1 misses it. Kotlin is
   normative for the iOS port, so port this behaviour.
6. **Junk detector:** when the seed row has no channel id at all, the artist test falls back to the folded
   artist name instead of calling every such radio junk.
7. **Anchors in 1a** are the last 3 distinct tracks the listener chose at or before the current position.
   "Heard with r >= 0.5" and "engine picks they liked" need the recorder and session state (1b).
8. **No bridge track.** The first pick is the best radio row of the newest anchor (the bridge rule's own
   fallback). "A familiar track by the same primary artist" needs the completion history (1b).
9. **FAV / DEEP on autoplay** are the radio rows the library already has (FAV when `song_engagements` has a
   play, DEEP when not). Library tracks that are not in a radio only appear in the library-only fallback.
   **ARTIST** is used only for rows that came from the artist-radio fallback; the affinity rule needs 1b.
10. **The 4-hour rule reads the existing playback history** (`ListeningStatsTracker.playbackHistory`, which has
    the 5-second floor) because `play_event` is empty until 1b. The "ignored three times" query is in place
    and reads `play_event`; it returns nothing until 1b writes events.
11. **No separate "Save" button on Recently Played.** The row's sheet already has Like and "Add to playlist",
    and both now import the track. A dedicated button is UI work for 1c.
12. **The Autoplay line is drawn inside the first engine row (and as a trailing item when there are no engine
    items), not as its own row between songs.** The queue list's reordering maps list indices to queue
    indices; a row in the middle would shift them.
13. **"Add to queue" now inserts above the Autoplay line** (7.1 "Ownership" asks this of "Play next"; without
    it a track the listener queued would land under the engine's picks).
14. **A radio stored with its continuation pages lives in `radio_list` under `chip = "+pages"`**, beside the
    first-page list, so a deeper request is fetched once.
15. **Radio budget:** `HostBudget` is a token bucket (burst 12, one token per 24 minutes = 60 a day, 2 in
    parallel) in memory, plus a check that fewer than 60 lists were stored in the last 24 hours. Requests
    that stored nothing (failures, the retry, fallback pages) are counted only by the in-memory bucket.
16. **`BrainSwitch` has two local flags** (autoplay, YouTube source). There is no `engine = legacy | brain`
    yet: nothing in 1a has a legacy twin to switch to. The YouTube source flag has no UI and no remote flag.
17. **When the radios run dry** (pool under 15 after the continuation pages) the library fallback is appended
    behind the radio rows instead of ending the queue. The line still says "Taizo picks more after this".
18. **An empty radio is treated as junk** (remembered for a day, fallbacks tried), not as "retry forever".
    The spec's "an empty radio is retried once" is done inside `YouTubeRadioSource` first.
19. **The backstop does not run when "stop after this track" was set for the track that ended**
    (`MusicService`, STATE_ENDED). The spec says a timer is untouched; without this the backstop would have
    restarted playback for a one-song queue.
20. **A song radio with fewer than 10 official-audio rows is treated as junk** (`BrainRepository.isJunk`).
    A library track can be matched to a music video, and the radio of a music video is music videos, which
    stage 1 does not play. The junk path's Related tab lists official-audio songs.
21. **`InnerTubeClient.browse` is named `browseMusic`** and `next` is `nextRadio` / `nextRadioContinuation`
    (the client already has a `post`-based search; the names say what they are for).

### Cut or left for the next milestone

- Everything in 1b: the event recorder, `PlayContext`, start and end reasons, `brain.*` extras through
  dispatch (only engine items carry them today), **skipping the legacy counter writes for engine tracks**,
  `BrainMath`, `Reward`, `Affinity`, `Session`, `Features`, `Ranker`, `Retrieval`, `ListBuilder`,
  `StationPlanner`, `WhyLine`, the golden vectors and `generateGolden`, the migration, the skip reactions.
- Everything in 1c: stations, the Home row, the why-line UI (each engine item already carries `brain.arm`
  and `brain.whyArg` = the anchor's primary artist), "Not for me", "How picks work", the novelty switch,
  "Refresh picks".
- `artist_alias` is created but nothing resolves artists yet: 1a joins artists by folded name in memory.
- `affinity`, `play_event`, `brain_state` are created (so 1b needs no migration) and not written.
- No instrumented (device) tests for `brain.db`.

### Not verified on a device (all of it; the specific risks)

1. An engine track actually playing end to end: proxy resolve through `playable`, audio, seek, crossfade.
2. Autoplay appending during real playback; the 1.5 s settle; the backstop resuming after STATE_ENDED
   (`seekToNextMediaItem` + `play` on an ended player).
3. `appendToQueueEnd` on a windowed queue (200+ items after a crossfade).
4. Which transition reason a crossfade hand-over reports; whether `onTransitionFinished` always fires
   (`AutoplayController.evaluate` also releases a held append when no transition is running). Whether
   `TransitionController` schedules a crossfade INTO the first appended track when the batch arrives while
   the last listener-chosen track is already playing (it was scheduled with no next item).
5. The queue UI: the Autoplay line's look, the switch, reordering and swipe-to-remove around it, the
   trailing item under the mini player padding.
6. Restart with engine items in the saved queue (unit-tested at the data level only). Note the snapshot
   saves the player's own items, as before: on a windowed player that is the window.
7. YouTube Music radio from a phone, on cellular, and outside the US (all probes were one US IP).
8. Radio parse time and memory for a 550 KB response on a phone (parsed on `Dispatchers.Default`).
9. Like / Add to playlist importing an engine track, and unlike removing it again
   (`removeFromExploredCatalog`, existing behaviour).
10. `cleanTitle` and `fold` over the titles of a real library (spec [S1]); decomposed Hangul.
11. App start: no brain code runs before a queue exists (traced: `attach` only starts two DataStore
    collectors; `evaluate` returns at `NO_QUEUE`). Not measured with `PerformanceMetrics`.
12. Cast and Connect with an engine item already in the queue.

### How to test

```
export ANDROID_HOME="C:\\Users\\Hoa\\AppData\\Local\\Android\\Sdk"
./gradlew :brain:test
./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsersTest" \
  --tests "com.theveloper.pixelplay.data.service.player.AutoplayPolicyTest" \
  --tests "com.theveloper.pixelplay.data.brain.*"
./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.recommendation.*"
./gradlew :app:assembleDebug
```

Last run on the committed code (2026-11-18), one invocation:
`./gradlew :brain:test :app:testDebugUnitTest :app:assembleDebug --continue` gave BUILD SUCCESSFUL in 15m 44s.

- `:brain:test`: 31 tests, 0 failed.
- `:app:testDebugUnitTest` (the whole suite): 2,091 tests in 270 classes, 0 failed, 1 skipped (it was
  skipped before this work). Of those, 67 are new: `InnerTubeMusicParsersTest` 15, `AutoplayPolicyTest` 20,
  `PlayableStoreTest` 8, `PlaybackQueueSnapshotBrainFieldsTest` 5, `BrainRepositoryTest` 13,
  `LibraryIndexTest` 6. `data.recommendation.*`: 61 tests, 0 failed, untouched
  (`RecommendationGoldenEqualityTest` 5 of 5).
- `:app:assembleDebug`: both split APKs built (the Hilt graph compiles with the new bindings).

Regenerate tables after editing a `.tsv`: `python tools/brain/gen_tables.py kotlin`.
Re-cut parser fixtures: `python tools/brain/cut_fixtures.py <folder with the raw captures>`.

On a phone, in this order: tap one song and let it end (related music should follow, the queue should show
the Autoplay line); tap one song and skip it at once (the backstop); turn the switch off; airplane mode
("From your library"); kill the app mid-autoplay and reopen; Like an engine track and find it in the library.

Build note (Windows): Gradle sometimes cannot delete a stale output directory under `app/build` or
`shared/build` ("AccessDeniedException", "Unable to delete directory"). Deleting that directory by hand, or
`rm -rf app/build shared/build brain/build`, and running again fixes it. It is not caused by this work.

---

## Milestone 1b: "it listens"

Spec section 14, stage 1, items 6 to 9. Built on 1a after confirming 1a still compiled and passed
(`:brain:test` + the filtered app tests: 159 tests, 0 failed, before any change).

**Nothing below has been run on a device either.**

### What the listener gets (once verified on a phone)

- Every play is recorded with how it started, how it ended and where it came from, including a skip
  after two seconds. Nothing is shown for it yet; every later stage learns from this log.
- A skip changes what comes next: a hard skip keeps that artist out of the engine's picks for ten
  tracks (a second one, for the rest of the session), and the engine's tail of the queue is re-scored
  at once. Two skips in a row bring a track from the library by the artist last finished. Three in a
  row change direction, and the Autoplay line says "Skipped three. Changing direction." for 12 seconds.
- A like puts one more track by that artist (or its nearest neighbour) into the next picks.
- Autoplay's picks are now ordered by the scorer (prior weights) instead of plain radio order, with
  library tracks close to what is playing mixed in (two familiar openers, then about one new track
  in four). With no history at all the order is the radio's own.
- An engine track the listener has not saved no longer lands in `feedback_v1` or `song_engagements`.

### Files

**`:brain`** (`./gradlew :brain:test`: 82 tests)

| File | What |
|---|---|
| `BrainMath.kt` | `ln`, `exp`, `sigmoid`, `log2`, `powNeg`, `logit`; `toQ` / `fromQ` (Q20); `orderKey`; `floorDiv`, `localDay`, `weekday`; `clip`. Constants as bit patterns. The expression order is the definition |
| `BrainJson.kt` | the canonical text form: ordered keys, Int64 as decimal strings, doubles as 16 hex digits, non-ASCII escaped. Writer and a small reader |
| `PlayEvent.kt` | the record of 4.1, `Origin`, `StartReason`, `EndReason`, `Route`, `EventFlags` (codes are part of the log: never renumber), `encode` / `decode`, `mintId` |
| `Reward.kt` | `grade(event, Context(pastCompletions, skipStreak)) -> Graded(r, c, cOther, rule, isSkip, h)`, `isSkip`, `skipStrength` |
| `Affinity.kt` | `Family` codes, `State`, `apply`, `applyEvent`, `aff`, `affRecent`, `share`, `p0`, `weight`, `clockJumpedBack` |
| `Replay.kt` | `LibraryFacts`, `GradeTracker`, `Replay.rebuild(events, facts, nowMs)`, `applyFacts` |
| `Brain.kt` | `SPEC_VERSION`, `onEvent` (grade, affinity, session: the live step) |
| `Graph.kt` | the in-memory graph (`Graph.Builder.addList` / `addTrack`), `Similarity.sim` (the `adj` branch) |
| `Session.kt` | `SessionState`, `Session.begin` / `isExpired` / `apply` / `seedOrder` / `patchQueue` |
| `Features.kt` | `Candidate`, the 51-entry vector (stage 1 computes 0, 1, 2, 3, 8, 12-18, 22, 23, 25, 26, 36, 37, 38) |
| `Ranker.kt` | `M0`, `SD0`, `weights(p0)`, `z`, `p` |
| `Retrieval.kt` | `cooc`, `percentiles` |
| `ListBuilder.kt` | the slot plan, the hard constraints of 6.4, `lambda`, quantised ordering with the SplitMix64 tie-break |
| `StationPlanner.kt` | `plan(request, affinity, session, graph)` for autoplay, stations and the Home row; `buildGraph`; `consume` |
| `WhyLine.kt` | the reason keys of 8.8 and `pick` |
| tests | `BrainMathTest`, `RewardAffinityTest`, `SessionPlannerTest`, `BrainGoldenTest`, `GoldenGenerator` (the generator, in the test source set), `PurityTest` (rewritten scan) |
| `brain/src/test/resources/brain-golden/` | the stage 1 golden vectors (13 files, 4.0 MB) with `README.md` (how each `fn` is replayed), `MANIFEST.sha256`, `SPEC_VERSION` |
| `brain/src/test/resources/brain-corpus/` | `names.txt` (1,000), `titles.txt` (300, 108 Vietnamese / Korean / Japanese): the text the fold and title vectors run over; cut by `tools/brain/cut_corpus.py <raw captures>` |

**App, new** (`data/brain/`)

| File | What |
|---|---|
| `PlayContext.kt` | `PlayContext`, `FinishedPlay`, `PlaySink`, `PlayReasons` (the table of 4.2), `BrainSurfaces` |
| `BrainEventRecorder.kt` | the recorder: one worker, in order; resolve, stamp, write, apply; `reactions` flow; the `killed` checkpoint |
| `BrainEngine.kt` | the in-memory state behind one lock (`withState`), lazy load, background rebuild and swap |
| `BrainRebuilder.kt` | `ReplayMerger` (log + imports, duplicates, merges), `AppStateRebuilder` (the migration, the facts) |
| `LegacyMigration.kt` | the one-shot `playback_history` copy |
| `ImportedPlayAdapter.kt` | `imported_plays` as events (read, never copied) |
| `TrackCatalog.kt` | `TrackFacts`, `LibraryTrackCatalog` (library, then `playable`, then the item itself) |
| `store/BrainStore.kt` | the storage seam (`RoomBrainStore`), row and entity mapping |

**App, changed**

| File | Change |
|---|---|
| `presentation/viewmodel/ListeningStatsTracker.kt` | `PlaySink` (4th constructor argument, default none); a brain play beside the legacy session; `context` and `endReason` on `onTrackChanged` / `ensureSession` / `finalizeCurrentSession` / `onPlaybackStopped`; `markFlag`, `onSeek`; the legacy counters skipped for a track outside the library |
| `data/service/MusicService.kt` | `brainPlayContext`, `brainRoute`, `brainEndReason`; reasons from `onMediaItemTransition`, the hand-over publish, `STATE_ENDED`, both teardown sites, the player error; `onSeek` |
| `presentation/viewmodel/PlaybackDispatchStateHolder.kt` | `brain.surface` / `brain.surfaceId` on every item a play from the UI builds (the start item too); `manualQueue` on "Add to queue" and "Play next" |
| `data/service/player/AutoplayController.kt` | planner instead of radio order; session anchors; reactions; `rescoreTail`; the change of direction; library tracks by the anchors' artists as candidates |
| `data/service/player/AutoplayPolicy.kt` | `rescoreStart`, `warmedAhead`, `anchorIndices(max)` |
| `data/service/player/DualPlayerEngine.kt` | `replaceQueueTail(fromAbsoluteIndex, items)`: one edit, windowed-queue aware |
| `data/brain/BrainExtras.kt` | `brain.primaryArtist`, `brain.whyKey` |
| `data/brain/BrainIdentity.kt` (`TrackResolver`) | `resolveArtistNames`, `survivorOf`, `merges` |
| `data/brain/EngineTrackSaver.kt`, `MusicRepositoryImpl.setFavoriteStatus`, `BrainSessionStateHolder.ensureSaved` | liked / saved / added-to-playlist flags on the play in flight |
| `data/brain/store/BrainDaos.kt`, `data/history/ListeningHistoryDatabase.kt` (one query: `maxPlayId`) | queries only; no entity changed, no schema changed |
| `di/BrainModule.kt` | `BrainStore`, `TrackCatalog`, `StateRebuilder`, `PlaySink` |
| `presentation/components/AutoplayDivider.kt`, `strings_player.xml` | `queue_autoplay_changed_direction` |
| `CLAUDE.md`, `.gitattributes` (golden files are LF), `brain/build.gradle.kts` (`generateGolden`) | |

### Item 6: the event recorder

How one play reaches the log, traced in code:

1. `MusicService.syncLocalListeningStatsFromPlayer` builds a `PlayContext` from the current `MediaItem`
   (title, artist line, `brain.primaryArtist`, Spotify id, the `brain.*` extras, shuffle, repeat-one, route)
   and hands it, with the reasons, to `ListeningStatsTracker`.
2. The tracker keeps a `BrainPlay` beside its legacy `ActiveSession`. When the play ends it calls
   `PlaySink.onPlayFinished` BEFORE the five-second floor, which now only guards the legacy stores.
3. `BrainEventRecorder` (one worker coroutine, plays in order) asks `TrackCatalog` what the media id is,
   resolves it to a rid and its artists to aids, stamps it (`Session.begin`), builds the `PlayEvent`,
   and inside `BrainEngine.withState` writes the event row and the affinity rows it touched in one
   transaction after `Brain.onEvent` updated the in-memory state.
4. A `Reaction` goes to `AutoplayController` on the main thread.

End and start reasons, per finalisation site:

| Site | End reason of the play that ends | Start reason of the next |
|---|---|---|
| `onMediaItemTransition`, `AUTO` | `trackDone` | `autoplay` when the new item has `brain.arm`, else `autoAdvance` |
| `onMediaItemTransition`, `SEEK`, absolute index +1 / -1 / other | `next` / `prev` / `pickedOther` | `next` / `prev` / `tap` |
| `onMediaItemTransition`, `PLAYLIST_CHANGED` | `queueEdit` when the full queue is exactly one shorter, else `pickedOther` | `tap`; `restore` while a snapshot is restored |
| `onMediaItemTransition`, `REPEAT` | `trackDone` | `autoAdvance` (the `repeatOne` flag comes from the player's repeat mode) |
| `publishMediaSessionPlayer` with another song (crossfade hand-over) | `trackDone` | `autoplay` / `autoAdvance` |
| `onPlaybackStateChanged(STATE_ENDED)` | `trackDone` | - |
| no current item, idle or ended | `stopped` | - |
| `onDestroy`, `stopPlaybackAndUnload` | `stopped` | - |
| `onPlayerError` | `error`, once, at whatever finalisation follows | - |
| any other `ensureSession` on a new song | `unknown` (never a skip) | `unknown`; `restore` while restoring |
| an open play found by the next process | `killed`, with what was heard by its last pause | - |
| `PlayerViewModel.onCleared` | none: the legacy session closes as before, the brain's play stays open | - |

### Item 7: the pure core and the golden vectors

`./gradlew :brain:generateGolden` rewrites `brain/src/test/resources/brain-golden/`; `BrainGoldenTest`
regenerates in memory and compares byte for byte, checks the manifest and the version file, checks the
counts the spec asks for (2,000 each for `ln`, `exp`, `sigmoid`, `log2`, `powNeg`; 1,000 names; 300
titles; 300 graded events), and generates twice to prove determinism. `README.md` in the folder is the
replay recipe for the iOS port. Hand-rolled `ln` and `exp` agree with the JVM's own within 4 ulp over
600 binades (`BrainMathTest`); that is a sanity check, not the contract: the bits in `math.jsonl` are.

**For the iOS port:** copy the whole folder; transliterate the Kotlin operation by operation (no
`addingProduct`); hashes are `UInt64` with wrapping operators; the tie-break comparison is unsigned;
`Session.begin` must be called before `Brain.onEvent` where the README says so.

### Item 8: the migration

Runs in the background the first time the brain is needed (the first play that ends, or the first
autoplay plan), never at app start, with the stored state serving meanwhile:

1. `LegacyMigration.copy`: every `playback_history` row that ended before the brain's first use becomes
   a `migratedHistory` event (start = end minus listened, length from the library, reasons and surface
   unknown). Idempotent: the event id is a function of the row and the insert ignores an existing id.
2. `ImportedPlayAdapter`: `imported_plays` as events, resolved once per distinct track, not stored.
3. `ReplayMerger`: an imported play that ended within 90 s of a stored play of the same recording is
   dropped; both streams are merged in ascending event id; merged recordings map to their survivor;
   `Replay.rebuild` clamps every event time to now.
4. `LibraryFacts`: favourites, Spotify top artists / top tracks / followed artists (`taste_seeds`),
   boost and reduce taste adjustments for artists and songs, as pseudo-counts.
5. The rows replace the `affinity` table in one transaction; plays recorded while it ran are applied
   to the new state first, and not twice.

It runs again when `Brain.SPEC_VERSION` changes, when `imported_plays` has a new highest row id, when a
live update sees the clock 48 hours behind the newest counter, and once a week (to refresh the facts).

### Item 9: session reactions

`AutoplayController.onReaction`, after each recorded play:

- A skip (`h > 0`), a like or a change of direction re-scores the engine's tail: `rescoreTail` asks
  `Session.patchQueue` for the same number of tracks from the tail's own tracks plus the pool, and
  when the answer differs applies it with ONE `DualPlayerEngine.replaceQueueTail`.
- The re-score starts at `AutoplayPolicy.rescoreStart`: after the current track and the next one (the
  next two on unmetered Wi-Fi), and only over the unbroken run of engine items at the end of the queue.
  It is held while a transition runs, and dropped when the queue changed while it was planned.
- Anchors come from the session (`Reaction.anchors`): the listener's own choices heard with r >= 0.5,
  plus engine picks they liked, saved or replayed. The listener-chosen track that is playing right now
  leads them (tap one song after an album, and autoplay follows the song), three at most. Before the
  first play of a session is recorded, only the last listener-chosen track at or before the current one.
- Third skip in a row: the next anchor alone seeds the pool. With no other anchor, a matched library
  track by the library artist with the highest affinity, outside the artists skipped this session.

### Deviations from the spec, and why (continuing the list)

22. **`StartReason` has no `pickedOther`.** 4.1's enum does not list it; 4.2's table says "the same
    word". Picking an item of the queue starts as `tap`.
23. **The affinity tables are held in memory in full**, loaded on first use (one `SELECT *`), not
    point-read per event. A listener with a large import has over 10,000 rows: a few megabytes and
    tens of milliseconds, off the main thread. Rows an event touched are still upserted with it.
24. **`affinity.family` codes beyond the families:** 16 + family is the family's sum row, 32 + family
    its pseudo-count rows, 48 the per-track completion counts (`pastCompletions`). No schema change.
25. **`affRecent` (feature 3) leaves the pseudo-counts out:** a follow is not recent evidence.
26. **The bias weight is `logit(p0)` read when a list is planned**, not frozen "at first rebuild". It
    is the same for every candidate of a list.
27. **The slot plan counts the novelty share from the third slot:** `novelSoFar < nu * (slot - 2)`.
    The formula as printed (`nu * slotIndex`) gives F F N F N at 0.25; the spec's own example is
    F F N F F F N, which this reproduces.
28. **Arms:** a library candidate is FAV with at least one completion, else DEEP ("not played in 90
    days" is not checked; the 4-hour rule applies to everything). ARTIST needs `aff > 0.5` and a
    decayed weight of 5, not five counted plays: the counters do not hold counts.
29. **`nu0` is 0.25**, not the listener's first-play share. Autoplay's factor is 1.0 after a single
    listener-chosen track and 0.8 otherwise.
30. **No Thompson draw over arms.** A novel slot takes the best-valued novel candidate. `Bandit.kt`,
    `MixPlanner.kt` and `Snapshot.kt` (listed for stage 1 in 13.1, absent from 10.1's stage 1 list)
    are not written.
31. **Reward, read literally:** "x 0 for a live play that stopped under 30 s" also zeroes a play in
    which the listener pressed Like. The like still counts as a library fact.
32. **The skip streak** counts live plays of one session whose ends are at most 60 s apart, and
    restarts with a new session, so a replay grades exactly as the live path did. "Directly after a
    skip" is a streak of 1 or more.
33. **Session details:** `passiveRun` is the run of plays started by `autoAdvance` / `autoplay` (a
    pause or seek inside a track does not reset it). `nuSession * 0.67` needs a hard skip (`h >= 0.6`).
    "A second skip of that artist" counts hard skips. A process restart starts a new session.
34. **A change of direction follows one anchor**, the one turned to. The same anchors in another order
    would be the same pool.
35. **`Session.begin` runs when a play is recorded, at its end.** Plays are sequential, so the stamps
    are the same as if it ran at the start.
36. **Golden files:** `math.jsonl` also pins `toQ`, `orderKey` and `localDay`; `hash.jsonl` pins the
    unsigned remainders. The generator is in the test source set and reads a committed text corpus.
37. **A play that never made a sound** is logged only when the listener or an error ended it, flagged
    `neverAudible` (so it is worth nothing). A paused, restored item that is stopped is not an attempt.
38. **"No library row"** is: no `songs` row and a `playable` row, looked up off the main thread, and
    only for engine-owned items or synthetic ids.
39. **Surface threading:** playlist (by id, stored as a hash), Liked Songs, Your Mix, Daily Mix, Taizo
    (by the queue names the app passes, English only), and `manualQueue`. An album, an artist page and
    search record `unknown`, which Reward treats like any surface the listener picked from.
40. **Migration:** history rows whose song is gone are skipped. "Liked" is the in-app favourites;
    Spotify's Liked Songs are not read separately. Genre and album taste adjustments give no
    pseudo-counts (they need stage 2's ids). A backup restored later is not copied (the copy is one-shot).
41. **A rebuild holds the imported plays in memory** (sorted by event id) instead of streaming them.
42. **`killed`:** the open play is written down when it first sounds and at each pause, so a killed
    play carries what was heard by its last pause, not by the moment of death.
43. **Cast and Connect:** a local play that continues into a Cast session keeps recording under its
    local context; a play that starts on Cast, and anything on Connect, is not recorded.
44. **`TasteConstraintAdapter.kt` is not written.** Boost / reduce reach the planner as `boostLn`
    from the existing `TasteAdjustments.combinedMultiplier`; mute and block are applied when the pool
    is built, as in 1a. Matching by aid, rid and atlas id is stage 2.
45. **The library-only pool keeps 1a's in-order pick:** its tracks have no recording ids.
46. **`replaceQueueTail` refuses on a windowed queue whose window does not reach the queue's end.**
47. **The queue snapshot was not extended:** `brain.primaryArtist`, `brain.whyKey`, `brain.whyArg`,
    `brain.retrQ` and `brain.radioRank` are lost on a process restart (1c needs the first three for
    the why-line; the recorder falls back to the artist line and records -1).

### Cut or left for 1c

- No UI for "Not for me" (the flag, the reward rule and the session effect are in the core and the
  recorder accepts the flag through `ListeningStatsTracker.markFlag`) and none for Familiar / Blend /
  Discover (`StationPlanner.Request.nuMultiplier`).
- The why-line UI. Items now carry `brain.whyKey` (`radio_fans`, `artist_more`, `because_liked`,
  `on_repeat`) and `brain.whyArg` (the name to show).
- Stations and the Home row: `StationPlanner` has `MODE_STATION` and `MODE_HOME_ROW` (golden-tested);
  nothing in the app calls them.
- `BrainSwitch.engine = legacy | brain` still does not exist.
- No instrumented tests for `brain.db` (the DAO queries are compiled by Room, not run).

### Not verified on a device (all of it; the specific risks)

1. The reasons: which callbacks really arrive, and in what order, for a tap, next, previous, a queue
   edit, repeat, an error, and above all a crossfade hand-over (`PlayReasons.forHandOver` assumes the
   publish of another song is the hand-over; the tracker merges a second announcement of the same
   track within 2 s). A wrong order would show as plays cut in two or `unknown` reasons.
2. That `play_event` rows are written at all on a phone (Room compiles the queries; no query was run).
3. The cost of the first load, the migration and a rebuild on a real library and a real import
   (budget in the spec: under 3 s for 50,000 imported plays). Memory for a large import.
4. `rescoreTail` on a real player: `replaceMediaItems` behind the current track during playback, with
   and without crossfade; whether the prefetcher's warmed track is ever inside the replaced range;
   `replaceQueueTail` on a windowed queue.
5. Whether the reaction is fast enough to be felt (the plan is made off the main thread after the
   event is written).
6. The change of direction end to end, including the fallback to a library artist.
7. The Autoplay line showing the note; the queue UI while its tail is replaced under the thumb.
8. Audio route detection (`AudioManager.getDevices`), read at most twice a minute.
9. Bit-equality of `BrainMath` on ART (argued in the spec; proved only by running `BrainGoldenTest`
   on a device, which was not done) and in Swift.
10. That nothing runs at app start: traced (the recorder's worker starts with the first audible
    play; a restored, paused queue sends nothing), not measured.
11. Cast and Connect with the recorder in place.

### How to test

```
export ANDROID_HOME="C:\\Users\\Hoa\\AppData\\Local\\Android\\Sdk"
./gradlew :brain:test
./gradlew :brain:generateGolden        # only after an intended change; then commit the folder
./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.*" \
  --tests "com.theveloper.pixelplay.data.service.player.AutoplayPolicyTest" \
  --tests "com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker*" \
  --tests "com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsersTest" \
  --tests "com.theveloper.pixelplay.data.recommendation.*"
./gradlew :app:assembleDebug
```

Last run on the committed code (2026-11-18), one invocation:
`./gradlew :brain:test :app:testDebugUnitTest :app:assembleDebug --continue` gave BUILD SUCCESSFUL in 14m 58s.

- `:brain:test`: 82 tests in 10 classes, 0 failed (31 from 1a; new: `BrainMathTest` 7, `RewardAffinityTest` 16,
  `SessionPlannerTest` 22, `BrainGoldenTest` 5; `PurityTest` 1 to 2).
- `:app:testDebugUnitTest` (the whole suite): 2,132 tests in 274 classes, 0 failed, 1 skipped (the same one as
  before). New: `BrainEventRecorderTest` 10, `BrainMigrationTest` 11, `PlayReasonsTest` 8,
  `ListeningStatsTrackerBrainTest` 9; `AutoplayPolicyTest` 20 to 23. `ListeningStatsTrackerTest` (2) and
  `data.recommendation.*` (61, with `RecommendationGoldenEqualityTest`) pass untouched.
- `:app:assembleDebug`: both split APKs built, so the Hilt graph compiles with the new bindings.

Build note, worse than in 1a: on this machine Gradle failed about one run in two with "Unable to delete
directory" or `AccessDeniedException` somewhere under `app/build` or `brain/build` (a different directory
each time: `packageDebugResources`, `built_in_kotlinc`, `merged_res_blame_folder`,
`transformDebugClassesWithAsm`, `test-results/.../binary`, `generated/source/buildConfig`,
`brain/build/reports`). Deleting the named directory by hand and running again got past it every time.
Nothing was killed. It is not caused by this work.

On a phone, after 1a's list: play a song, skip the next one after two seconds, and look at `play_event`
in `brain.db` (Database Inspector) for a row with `endReason = 1` and `playedMs` near 2000; skip two
engine picks by one artist and check that artist is gone from the tail; skip three in a row and read
the Autoplay line; like an engine pick and look for a track by that artist in the next batch; let an
engine track play and check `song_engagements` has no row for it.

---

## Milestone 1c: "it shows"

Spec section 14, stage 1, items 10 to 13, with 8.1 (the stage 1 row), 8.3, 8.4, 8.5, 8.8, 8.9 and 13.1.
Built on 1b after confirming 1b still compiled and passed (`:brain:test` 82 tests and the filtered app
tests 171 tests, 0 failed, before any change).

**Nothing below has been run on a device either.** No layout was seen on a screen: every composable here
was written and compiled, never looked at.

### What the listener gets (once verified on a phone)

- **Start station** in every song menu (the song options sheet, which is also the player's more-menu) and
  in the artist page's menu. The seed plays first (the song itself; for an artist, the listener's
  most-played library track by them); twenty picks are appended once the seed is 1.5 s in and its radio
  has answered, then five at a time.
  The queue's line reads "Station · Espresso". A station keeps going with the Autoplay switch off.
- **"Because you listened to X"** on Home, at most two rows, twelve cards each of music the listener does
  not have. A tap plays the card with the row as the queue; autoplay continues from there.
- **Not for me** in every song menu and on the sheet behind a reason line: the song is muted (it shows
  in Taste changes), skipped if it is playing, and a bar offers Undo and "Less from <artist>".
- **A reason under every engine pick** in the queue ("Fans of d4vd also play this", "More from X",
  "Because you liked X", "On repeat this month", "Because you listened to X"). A tap opens "Why this
  song": the reason, Like, Not for me, Less from the artist, and "How picks work".
- **How picks work**: the stage 1 promises, the Autoplay switch, the way to Taste changes. Reached from
  the (i) on the Autoplay / Station line and from "Why this song".
- **Familiar / Blend / Discover** under the Autoplay / Station line. It scales how much of the next picks
  is new music and re-plans the engine's tail at once.
- The reasons, and everything else an engine pick carries, now survive a restart.
- The legacy "Beyond your library" shelf and its text search are gone, and the mixes no longer import
  catalogue tracks into the library behind the listener's back.

### Files

**New, data** (`app/.../data/brain/`)

| File | What |
|---|---|
| `Station.kt` | `Station` (kind, seed media id, title, artist; `encode` / `decode`), `Novelty` (0.4 / 1 / 2), `StationStore` (the station of the queue and the novelty switch, in memory and in the preferences) |
| `BrainPlayTags.kt` | what the next dispatched play carries (`expect` / `take`); `take` also ends a station |
| `HomeAnchorStore.kt` | `HomeAnchor`, `qualifies`, `push`, `pick`; the short list in `brain_state` (`home_anchors_v1`) |
| `BrainHomeRows.kt` | builds, saves (`brain_home_rows_v1.json`) and presents the Home rows; `BrainHomeRow.tags()` |
| `BrainFeedback.kt` | `flagNotForMe`, `mute`, `undo`, `lessFrom` over the existing taste changes |

**New, UI**

| File | What |
|---|---|
| `presentation/viewmodel/StationStateHolder.kt` | picks the seed, announces the play, hands it to the player |
| `presentation/viewmodel/BrainActionsStateHolder.kt` | `BrainNotice` (the bar), `WhySheetState`, the two sheets' visibility |
| `presentation/utils/BrainUiLocals.kt` | `BrainUi`, `LocalBrainUi` (provided in `MainActivity`) |
| `presentation/components/NoveltySwitch.kt` | the three-stop connected group |
| `presentation/components/WhyLineText.kt` | reason key to sentence; `whyLineText()` |
| `presentation/components/WhyThisSongSheet.kt`, `HowPicksWorkSheet.kt` | the two sheets |
| `presentation/components/BrainSongActions.kt` | the two rows of the song options sheet; `BrainNoticeBar` |
| `res/values/strings_brain.xml` | every string of this milestone (English only) |

**Changed**

| File | Change |
|---|---|
| `data/service/player/AutoplayController.kt` | station mode (one fixed anchor, `MODE_STATION`, first batch 20, `nu` x 1.2, the session's seed fields, the artist radio); the novelty factor; `refreshStation`; `onTasteChanged`; `AutoplayUiState.stationTitle` / `stationIsArtist` / `novelty` |
| `data/brain/BrainRepository.kt` | `artistRadioFor` (artist page top songs + artist radio, stored under chip `+artist`), `channelIdFor` |
| `data/brain/BrainExtras.kt` | `BrainItemTag` gains `whyKey`, `whyArg`, `primaryArtist`, `chosen`, `retrQ`, `radioRank`; `brain.chosen`; `isEngineOwned` is false for a chosen pick |
| `data/model/PlaybackQueueSnapshot.kt` | six more optional `brain*` fields (closes 1b's deviation 47) |
| `data/brain/BrainEventRecorder.kt` | writes a home anchor beside a qualifying event |
| `data/brain/BrainSwitch.kt` | `LEGACY_EAGER_DISCOVERY = false` |
| `data/recommendation/HomeCatalogRepository.kt` | the "Beyond your library" text search is not run; saved finds are dropped |
| `presentation/viewmodel/DailyMixStateHolder.kt`, `data/worker/AiDailyMixWorker.kt` | no longer call `MusicDiscoveryRepository.augment` |
| `presentation/viewmodel/HomeDiscoveryStateHolder.kt` | the brain rows in front of the legacy shelves; no `discoveries` to the planner; a brain row plays through `BrainHomeRows.prepareForPlayback` + `BrainPlayTags` |
| `presentation/viewmodel/PlaybackDispatchStateHolder.kt` | `brainPlayTags.take(queueName)` first thing in `internalPlaySongs`; per-song tags |
| `presentation/viewmodel/BrainSessionStateHolder.kt` | `queueWhy`, `setNovelty`, `stations`, `actions` |
| `presentation/viewmodel/PlayerViewModel.kt` | `startSongStation`, `startArtistStation`, `notForMe` (facade) |
| `presentation/components/AutoplayDivider.kt` | "Station · X", the (i), the novelty switch; `WhyLineLabel` |
| `presentation/components/QueueBottomSheet.kt` | the reason line on a row, the new divider parameters |
| `presentation/components/SongInfoBottomSheet.kt` | one line: `BrainSongActions` |
| `presentation/screens/ArtistDetailScreen.kt` | "Start station" in the hero's menu |
| `MainActivity.kt` | `LocalBrainUi`, the notice bar, the two sheets |
| `data/preferences/UserPreferencesRepository.kt` | `brain_station`, `brain_novelty` |
| `app/compose_stability.conf`, `CLAUDE.md` | |

### Item 10: stations

How a station runs, traced in code:

1. `StationStateHolder.startSongStation` writes the `Station` to `StationStore`, then either (the seed is
   already playing) drops what is queued behind it, or announces the play to `BrainPlayTags` and asks
   for a queue of just the seed, tagged `surface = station`.
2. `AutoplayController.refreshStation` reads the queue at every trigger: the queue is the station's for
   as long as it holds the seed's media id. `anchorsOf` then returns the seed alone, whatever the
   session's anchors are, and `active` is true even with Autoplay switched off.
3. The pool is the seed's radio (continuation pages when it runs low, as for autoplay) and, for an
   artist station, in front of it the artist page's top songs plus the artist radio.
4. `planOnBrain` runs `StationPlanner.MODE_STATION` with `nu x 1.2 x` the switch, 20 picks the first
   time and 5 after, the tie seed of 6.2, and sets the session's `seed` / `seedAid` / `seedKind` so the
   seed artist is thinned out after skips instead of blocked (5.5).
5. Picks are tagged `surface = station`, `surfaceId = Station.id` (a hash), and carry reasons like any
   engine pick.
6. The station ends when any other play is dispatched (`BrainPlayTags.take` in `internalPlaySongs`), or
   when a queue arrives that does not hold the seed. It is saved in the preferences, so a restored queue
   is still a station.

### Item 11: the Home row

1. When a play is recorded, `HomeAnchorStore.qualifies` decides whether it may head a row: a live play
   with no arm and `r >= 0.8`, or an engine pick the listener liked, saved, added or replayed.
2. `HomeDiscoveryStateHolder.refresh` first shows `BrainHomeRows.cached()` (one small file), in front of
   the legacy shelves, then plans the legacy Home exactly as before (minus `discoveries`), then calls
   `refreshIfDue()`.
3. `refreshIfDue` returns at once unless the brain is already in memory in this process, and unless the
   two newest anchors by different artists, the taste changes or the blocked artists moved (or the rows
   are six hours old). It then reads each anchor's radio (one request per anchor at most, only when it
   is not cached), filters (6.1), plans `MODE_HOME_ROW` against a FRESH session, and saves.
4. A rebuilt row is what the next visit shows; only a Home with no row yet takes the new rows at once.
5. A tap registers the row's tracks in `playable`, announces their tags, and plays through the normal
   dispatch. The tracks carry `brain.arm` (they are logged as recommendations) and `brain.chosen` (they
   are the listener's items in the queue, so autoplay anchors on them like on a playlist's tracks).

### Deviations from the spec, and why (continuing the list)

48. **A station is "the queue holds its seed", not a queue header flag.** Starting a station from the
    track that is playing keeps it playing and removes what was queued behind it; any other seed starts a
    new one-song queue. The station's `pool` and `served` of 8.3's state live in `AutoplayController`,
    not in `StationStateHolder`, which only holds the seed choice.
49. **Artist station: only for an artist with a library track.** The seed is the most-played library
    track whose primary artist is that artist (else one that credits them). The "videoId of the artist
    page's radio button" path for an artist with nothing in the library is not built: the artist is
    told "Nothing to start from yet. Add a song by X first." Entry is the artist page's menu only.
50. **Artist station pool:** the artist page's top songs and the artist radio, then the seed track's own
    song radio. "Similar artists' top songs" (more requests per station) is left to stage 2 with the
    SIM arm. The channel id comes from a cached radio row by that artist, else from one `next`.
51. **"At most 1 in 3 by the seed artist" is not a separate rule:** 6.4's "same artist not within 4
    slots, 2 per 20" is stricter and already applies, so an artist station holds LESS of its artist than
    6.2 allows. Changing that is a change to `:brain` (and the golden vectors) for stage 2.
52. **A station does not change direction.** Three skips in a row still block what was skipped (the
    session rules), but the station stays on its seed and the line does not say "Changing direction".
    "Re-seed from the best-received station track's radio when the pool runs under 15" is not built: the
    pool deepens with continuation pages and then falls back to the library, as autoplay does.
53. **The session's seed fields are set when a batch is planned**, not when the station starts. A hard
    skip of the seed artist before the first plan of a new session blocks instead of thinning.
54. **Home anchors come from a cache written beside the event, not from a `play_event` query.** An
    event has ids, no title and no media id, and the row needs both. `home_anchors_v1` in `brain_state`
    holds the last eight qualifying plays. It cannot be rebuilt from the log (it is seven days of data).
55. **The Home row is all new music (12 of 12), not "up to 9 novel".** `StationPlanner.MODE_HOME_ROW`
    as golden-tested in 1b drops library tracks; section 14 calls it "one Home row of music they do not
    have yet". At most 2 per artist comes from 6.4.
56. **The row is planned against a fresh session**, so a skip a minute ago does not reshuffle Home.
57. **Cards show the pick's arm reason** ("Fans of <X's artist> also play this", "More from <artist>"),
    not the row's own "Because you listened to X", which is the row's title; the track carries
    `because_listened` into the queue.
58. **The rows sit in front of the legacy shelves**, not after them. A row with fewer than six cards is
    hidden. With "discovery" switched off in the existing setting, the rows are hidden too.
59. **`MusicDiscoveryRepository.augment` is not edited; its two callers stop calling it** (and
    `HomeCatalogRepository` skips the search), behind `BrainSwitch.LEGACY_EAGER_DISCOVERY = false`.
    The spec says "it returns its input unchanged", but `MusicDiscoveryRepositoryTest` (which must
    stay untouched) tests the import.
60. **"Not for me" is reached from the song options sheet and from "Why this song"**, not from a
    long-press on a queue row (the row's menu button opens the sheet). It removes the listener's choice from the engine's picks at once; a track
    the listener queued themselves stays in the queue (a mute keeps it playable by hand).
61. **A "Not for me" on a track that is not playing writes no event**: the mute is the whole effect, and
    the "half-strength negative on its primary artist" only happens through the flagged play.
62. **Undo takes the mute back, not the flagged play.** The event keeps its `NOT_FOR_ME` flag
    (`Affinity.subtract` is stage 2).
63. **"Less from X" is the existing reduce at the default strength.** "A second tap offers mute" is not
    built.
64. **"Why this song" shows no supporting facts** (stage 2) and offers Like, Not for me and Less from
    the artist.
65. **Familiar / Blend / Discover is one global switch**, shown on the Autoplay line and on a station's
    line alike, not a per-station `nuSwitch`. It is hidden while the picks come from the library alone.
66. **`BrainItemTag.chosen` / `brain.chosen`** is new: a Home row's card has an arm and is still the
    listener's item. The event log has no such field; the play is logged with its arm and
    `surface = homeShelf`.
67. **The (i) on the Autoplay line** is how "How picks work" is reached; the spec does not say where.
68. **The promise sheet says "Less repetition: a new song you skipped three times stays away for 60
    days"** and does not mention mixes (8.9).

69. **A pick appended in pool order** (the library-only pool, or the mixed pool once the radios ran
    dry) has no planner reason: a row that came from an anchor's radio says "Fans of <anchor's artist>
    also play this"; a plain library pick shows no line (the Autoplay line says "From your library").

### For the iOS port

`:brain` did not change in 1c: the golden vectors are 1b's. What iOS has to copy is on the app side:

- A station is the autoplay machinery with ONE anchor (the seed), `MODE_STATION`, a first batch of 20 and
  then 5, `nuMultiplier = 1.2 x switch`, the tie seed `seedRid xor localDay xor batch`, and the
  session's `seed` / `seedAid` / `seedKind` set before each plan (and cleared for plain autoplay).
- The switch is 0.4 / 1 / 2 (Familiar / Blend / Discover), multiplied into `nuMultiplier` for autoplay
  and stations alike; changing it re-plans the engine's tail.
- A Home anchor: a live play with no arm and `r >= 0.8`, or an engine pick with a liked / saved /
  added-to-playlist / replayed flag; never "Not for me", never a play that made no sound. Rows: the two
  newest by different artists within seven days; `MODE_HOME_ROW`, 12 cards, a fresh session, the tie
  seed `seedRid xor localDay`; fewer than 6 cards hides the row; a track once per page.
- A card the listener plays is logged with its arm and `surface = homeShelf`, but is the listener's
  item in the queue (Android's `brain.chosen`): it does not sit under the Autoplay line and autoplay
  may anchor on it.
- The strings are `app/src/main/res/values/strings_brain.xml`; the reason keys map as `WhyLineText` does
  (a sentence that needs a name is not shown without one).

### Cut (say so to the owner)

- **"Refresh picks"** on the Autoplay line (8.4).
- **Swipe on an engine row as a weak negative (`h = 0.2`)** (8.4): a swipe removes the row as it always
  did and is logged only if the track was playing.
- **A dedicated "Save" on Recently Played** for an engine track (1a's deviation 11): Like and "Add to
  playlist" still save it.
- **The artist station for an artist with no library track**, similar artists' top songs, the station
  re-seed (deviations 49, 50, 52).
- **The second tap on "Less from X"** (deviation 63) and the supporting facts (64).
- `Snapshot.kt`, `Bandit.kt`, `MixPlanner.kt`, `TasteConstraintAdapter.kt`, `BrainSwitch.engine`: still
  not written (nothing in stage 1 calls them).
- No instrumented tests for `brain.db`. No Compose UI tests.

### Not verified on a device (all of it; the specific risks)

1. **Every screen of this milestone**: the Station / Autoplay line with the switch under it inside a
   queue row, the reason line's height in a queue row (a third line: rows with a reason are taller),
   the two rows in the song options sheet, the two sheets, the bar's position above the mini player
   and over the expanded player, dark and light, long titles, large font sizes, TalkBack.
2. **Station start**: that the seed plays and twenty picks follow; the in-place start (the playing track
   stays, the rest of the queue goes, the 750 ms re-check sees it); a station started from an autoplay
   pick; ending a station by playing something else; a station across a restart.
3. **Artist station**: the channel id lookup, the artist page parse and the artist radio on a real
   artist; what it sounds like with 6.4's spacing (deviation 51).
4. **The Home row end to end**: that an anchor is written, that the row appears on the next visit,
   that a card plays (its tracks are not in the library), that the queue shows its reasons, that
   autoplay continues after it, that nothing is read from `brain.db` at app start (traced: `cached()`
   reads a file; `refreshIfDue` returns before any query until the brain is loaded).
5. **"Not for me"**: the skip carrying the flag (flag first, then `nextSong()`), the engine's tail losing
   the muted track in one edit, Undo, "Less from X", the Taste changes screen showing both.
6. **The novelty switch**: that Discover audibly changes the next batch; the re-plan of the tail.
7. **A restart mid-autoplay**: the reasons on the restored rows.
8. That Home still shows its legacy shelves in the same order, and that the mixes are unchanged apart
   from holding no imported catalogue tracks.
9. Icons `Icons.Rounded.Radio` and `ThumbDown` compile; nobody saw them. Within one Home row most cards
   carry the same reason line ("Fans of <X's artist> also play this"): the owner may prefer no line there.
10. Cost: nothing here was measured. Traced only: a taste change is matched off the main thread; a
    Home rebuild folds the whole library once (`LibraryIndex`) on a background thread; the queue's reason
    map is rebuilt off the main thread when the queue's timeline changes.
11. Everything on 1a's and 1b's lists.

### How to test

```
export ANDROID_HOME="C:\\Users\\Hoa\\AppData\\Local\\Android\\Sdk"
./gradlew :brain:test
./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.*" \
  --tests "com.theveloper.pixelplay.presentation.components.BrainCopyTest" \
  --tests "com.theveloper.pixelplay.data.service.player.AutoplayPolicyTest" \
  --tests "com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker*" \
  --tests "com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsersTest" \
  --tests "com.theveloper.pixelplay.data.recommendation.*"
./gradlew :app:assembleDebug
```

Last run on the committed code (2026-11-18), one invocation:
`./gradlew :brain:test :app:testDebugUnitTest :app:assembleDebug --continue` gave BUILD SUCCESSFUL.

- `:brain:test`: 82 tests in 10 classes, 0 failed. `:brain` was not changed in 1c, so the golden vectors
  are the ones of 1b.
- `:app:testDebugUnitTest` (the whole suite): 2,168 tests in 279 classes, 0 failed, 1 skipped (the same
  one as before). New: `StationAndPlayTagsTest` 9, `HomeAnchorStoreTest` 8, `BrainHomeRowsTest` 7,
  `BrainFeedbackTest` 7, `BrainCopyTest` 3; `PlaybackQueueSnapshotBrainFieldsTest` 5 to 7.
  `data.recommendation.*` (61, with `RecommendationGoldenEqualityTest` 5 and
  `MusicDiscoveryRepositoryTest` 5) pass untouched.
- `:app:assembleDebug`: both split APKs built, so the Hilt graph compiles with the new bindings.

What the new tests cover: a station written down and read back, what ends it, the novelty factors; which
plays may head a Home row; a Home row built end to end against a fake radio (twelve cards, none from the
library, two per artist at most, a track once per page, nothing before the brain is loaded, nothing
rebuilt that did not change, a muted track gone after a taste change); the mute and the reduce behind
"Not for me" and "Less from", the flag set before the skip; the copy against the forbidden words.
What they do not cover: `AutoplayController`'s station mode and `onTasteChanged` (traced by reading
only), and every composable.

Only the final tree was built and tested, not each commit.

Build note, as in 1a and 1b: twice a run failed in `:app:packageDebugResources` with
"AccessDeniedException" / "Unable to delete directory" under `app/build/intermediates`; deleting the named
directory and running again got past it. Nothing was killed.

On a phone, after 1a's and 1b's lists: open a song's menu and tap "Start station" (the song should
play, the queue should say "Station · <title>" and fill with twenty picks, each with a reason); do the
same on the track that is playing; open an artist and start a station from the menu; tap a reason and
read the sheet; tap "Not for me" on an engine pick that is playing (it should skip, the bar should
offer Undo and "Less from ..."), then look at Settings > Taste changes; move the switch to Discover and
watch the tail change; finish a song you chose, go back to Home twice and look for "Because you
listened to ..."; play a card, then check the library does not have it; kill the app mid-autoplay and
reopen the queue.
