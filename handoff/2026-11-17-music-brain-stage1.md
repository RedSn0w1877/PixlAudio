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
