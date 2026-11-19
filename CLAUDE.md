# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

PixelPlayer — an Android music player (Jetpack Compose + Material 3, 100% Kotlin). Package `com.theveloper.pixelplay`. minSdk 30, compile/targetSdk 37, Java/JVM target 21.

## Build & test

Gradle wrapper (`./gradlew` / `gradlew.bat`). Configuration cache is on; `org.gradle.jvmargs` is tuned to 4 GB heap in `gradle.properties`.

```bash
./gradlew :app:assembleDebug
```

```bash
./gradlew :app:assembleRelease -Ppixelplay.enableAbiSplits=true
```

```bash
./gradlew :wear:assembleDebug
```

Unit tests (JUnit Platform — JUnit 5 plus the vintage engine for legacy JUnit 4 tests; `unitTests.isReturnDefaultValues = true`):

```bash
./gradlew :app:testDebugUnitTest
```

Single test class or method:

```bash
./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.worker.ArtistParsingUtilsTest"
```

Instrumented tests (Room migration tests, DAO tests, sync worker, startup benchmark) need a device/emulator:

```bash
./gradlew :app:connectedDebugAndroidTest
```

Lint is not enforced on release builds (`lint.checkReleaseBuilds = false`); there is no ktlint/detekt/spotless.

### Build flags

- `-Ppixelplay.enableAbiSplits=true|false` (default true) — per-ABI APKs for `arm64-v8a` / `armeabi-v7a`, no universal APK. CI verifies both split outputs.
- `-Ppixelplay.enableComposeCompilerReports=true` — emits Compose stability/metrics reports under `app/build/compose_compiler_reports`.
- Release signing reads `keystore.properties` at the repo root and `vz-pixelplay.jks`; if the keystore is absent the release build silently falls back to the debug signing config.
- `SPOTIFY_CLIENT_ID` → `BuildConfig.SPOTIFY_CLIENT_ID` is read from the **`SPOTIFY_CLIENT_ID` environment variable first** (the CI secret of that name, passed in by every workflow that builds the app), then from `local.properties`. If both are absent it is an empty string and Spotify sign-in is disabled at runtime (`SpotifyAuthManager.hasClientId()`); the login page and the dashboard say so instead of opening a dead browser tab.
- A `benchmark` build type (release, non-debuggable) exists for the macrobenchmark/baseline-profile modules.

## Module layout

- `:app` — the phone app; everything below lives here unless noted.
- `:shared` — Android library holding only serializable DTOs for the phone↔Wear Data Layer protocol (`Wear*.kt`). No platform APIs; keep it dependency-free.
- `:wear` — standalone Wear OS app, its own Compose UI + Hilt graph, talks to the phone via `:shared` DTOs.
- `:brain` — the music brain's pure logic (`docs/brain-spec.md`). **Kotlin JVM, standard library only**: no Android class, no I/O, no clock, no `kotlin.math`, no regex, no `hashCode()`. `PurityTest` enforces it. Test with `./gradlew :brain:test` (no Android build needed).
- `:baselineprofile` — baseline-profile generator and macrobenchmarks. `automaticGenerationDuringBuild = false`, profiles are checked into source (`saveInSrc = true`).

## Architecture

MVVM with StateFlow/SharedFlow, Hilt for DI, Room for persistence, Media3 for playback.

### Playback

`MusicService` (`data/service/MusicService.kt`, ~2.9k lines) is a `MediaLibraryService` and the single source of playback truth — it also serves Android Auto browsing, the Glance widgets, the quick-settings tile, and the Wear bridge (`data/service/{auto,tile,wear,cast,http}`).

`DualPlayerEngine` (`data/service/player/`) wraps two ExoPlayer instances so crossfades/transitions can overlap tracks; `TransitionController` drives per-song transition rules stored in Room. Audio behavior is split into small, unit-tested policy objects (`AudioDecoderPolicy`, `AudioOffloadPolicy`, `AudioFocusResumePolicy`, `LoadControlBufferProfile`, `HiResSampleRateCapAudioProcessor`, `SurroundDownmixProcessor`, `ReplayGainProcessor`) — prefer adding logic there rather than in the service.

UI talks to the service through a Media3 `MediaController` obtained from an injected `SessionToken` — never by binding to the service directly.

### State holders (important)

`PlayerViewModel` is huge but is mostly a facade: playback, queue, lyrics, cast, search, theme, AI, library, multi-selection etc. each live in a **`@Singleton` state holder** in `presentation/viewmodel/*StateHolder.kt`, injected into the ViewModel. Because they are process-scoped singletons, state survives ViewModel recreation and is shared with the service/widgets. When adding player-related state, create or extend a state holder — do not add fields to `PlayerViewModel`.

### Data & sources

`MusicRepository`/`MusicRepositoryImpl` is the aggregate library API over Room. `RoomSongRepository` backs `SongRepository` and pins every query to `StorageFilter.ONLINE`.

**There is no on-device music library.** The app is Spotify-only: MediaStore audio scanning, the folders/"songs on device" views, the import-from-device flow and the `READ_MEDIA_AUDIO`/`READ_EXTERNAL_STORAGE` permissions were all removed (see `handoff/2026-10-12-android-no-local.md`). Spotify tracks downloaded for offline playback are files on disk but are *not* "local songs" — they carry a `spotifyId` and `source_type != 0`. `SyncWorker`/`SyncManager` are now just housekeeping: a one-shot purge of leftover `source_type = LOCAL` rows plus album-art cache cleanup. Artist parsing (`ArtistParsingUtils`) and album grouping (`AlbumGroupingUtils`) still run on imported Spotify metadata. Do not reintroduce a MediaStore audio query.

**Spotify is the only remote source.** The six upstream sources (Telegram, Navidrome, Jellyfin, Netease, QQ Music, Google Drive) were removed from this fork along with TDLib.

- `data/spotify/` — `SpotifyAuthManager` (OAuth 2.0 PKCE via Custom Tabs, redirect `pixelplay://spotify-callback`, tokens in `EncryptedSharedPreferences`), `SpotifyRepository`, `SpotifyStreamProxy`. **Spotify rotates the refresh token on every refresh; `saveTokens` must persist the new one or the account dies after one rotation.**
- `data/network/spotify/` — Retrofit services for `accounts.spotify.com` (OAuth) and `api.spotify.com`.
- `presentation/spotify/{auth,dashboard}` — the login bridge activity and the account dashboard.
- `data/worker/SpotifySyncWorker` imports metadata, then chains `SpotifyMatchWorker`.

Spotify's Web API returns **metadata only** — no audio, and preview URLs are gone for new apps. `data/youtube/` matches each track to a YouTube Music video (`TrackMatcher` scores title/artist/duration and penalises live/remix/cover variants) and resolves playable audio (`InnerTubeClient`, `YouTubeStreamResolver` strategies, `SignatureCipherSolver` running `base.js` through the `JsEvaluator` WebView). That layer breaks whenever YouTube changes something — swap a `YouTubeStreamResolver` implementation, not the feature. `PlaybackDiagnostics` walks the whole chain on one track and reports which step failed; the Spotify dashboard's "Test playback" button surfaces it.

> **Never inject the default `OkHttpClient` into anything that talks to YouTube.** It carries an interceptor that *overwrites* `User-Agent` with `PixelPlayer/1.0`, which contradicts the client identity in the InnerTube request body and gets the call rejected. Use `@YouTubeOkHttpClient`.

Browsing the Spotify catalogue (search, artist top-tracks and albums, the user's most-played) lives in `presentation/spotify/browse`. Drill-down is ViewModel state rather than nav routes, so going back doesn't re-fetch. Tracks found there are imported into a synthetic `SpotifyPlaylistEntity.BROWSE_ID` playlist and then go through the same matcher as everything else. Reading most-played needs the `user-top-read` scope, which was added after the first release — accounts linked before it must reconnect once.

YouTube audio tops out at roughly 256 kbps (no lossless stream by any route), so `pickBestAudio()` just takes the highest bitrate on offer.

**SpotiFLAC (`data/spotiflac/`) is tried before YouTube** when it finds the track and the audio quality setting allows. The tiers (`AudioQuality`) are:
- **Low / Medium / High:** YouTube capped at 96 / 160 / 256 kbps. SpotiFLAC is used only as a last resort, when YouTube has nothing.
- **Pro (the default):** SpotiFLAC first if the file is at most 48 kHz.
- **Studio Pro:** SpotiFLAC first at any rate.

Old stored values map over in `AudioQuality.fromStoredName`: Standard becomes Medium, Ultrasound becomes Pro.

`SpotifyStreamProxy.resolveStreamUrl` follows that order. SpotiFLAC is also skipped for manual YouTube matches, and for two minutes after its URL failed. The lookup gets 6 s, then the YouTube chain runs.

The only live provider is Monochrome (`tracks.monochrome.st`):
- `/search/tracks?q=isrc:<ISRC>` finds the exact recording in one request; a bare ISRC is matched as text, not as the code. The fallback is a text search scored by `SpotiFLACMatcher`.
- The audio comes from `/track/<trackId>` with byte ranges, always sent as `application/octet-stream`. Its catalogue mixes 16-bit/44.1 kHz FLAC, 24-bit FLAC up to 176.4 kHz (~6 Mbps) and a few AAC-in-MP4 files.
- So the proxy reads the first 64 bytes before serving anything (`CloudStreamProxy.acceptStreamHead` → `AudioFileFacts`). It labels the response from the real format, remembers the rate per track, and on Pro turns a file over 48 kHz down for YouTube. Each Monochrome request takes about 1 s to answer, and each connection is capped near 0.3 MB/s, about twice what FLAC plays at. So the proxy fetches SpotiFLAC URLs as 1 MB pieces, three at a time (`upstreamChunkSize` / `upstreamParallelism` → `ParallelPieces`), and still never splices another source in mid-song. Tidal HiFi instances are a second provider slot, but none ship: all four original instances were dead or parked by 2026-11-11. A parked domain left in `TRUSTED_AUDIO_HOST_SUFFIXES` would let whoever buys it feed audio through the proxy, so keep that list to hosts a resolver can actually return. Same rules as iOS (`PixlNet/SpotiFLAC/`).

**Which InnerTube client resolves the audio is the whole ballgame** (`InnerTubeContexts.kt`). Playback works because of `VISIONOS` — the only client in yt-dlp's table declaring neither a `GVS_PO_TOKEN_POLICY` (so its audio needs no PoToken and never arrives truncated) nor `REQUIRE_JS_PLAYER` (so it returns direct URLs, no `base.js`). It leads `PLAYER_PROFILES` for that reason. The others each toll: `IOS`/`ANDROID_VR` return direct URLs but require a GVS PoToken, so their audio **cuts off around 47-53 s**; `WEB` now returns audio as SABR-only entries (`initRange`/`indexRange`/`contentLength` but no `url` and no `signatureCipher`), i.e. nothing playable. Two invariants:

- **Never send the cookie to a client that doesn't accept it.** `VISIONOS`/`ANDROID_VR`/`IOS`/`ANDROID_MUSIC` are absent from yt-dlp's `SUPPORTS_COOKIES` and answer `HTTP 400` if one is attached — signing in is what used to break exactly the clients that return ready-to-play URLs. Gated by `ClientProfile.supportsCookies`.
- **Always send a fresh `visitorData`, anonymous requests included.** Without one YouTube answers `LOGIN_REQUIRED — Sign in to confirm you're not a bot` even for public videos; it's bot detection, not the account. `PixelPlayPoTokenProvider.anonymousVisitorData()` fetches a real one once per process — the value stored by the login WebView may still be a placeholder YouTube rejects.

`YouTubeAudioFormat.isMuxedFallback` marks itag 18 (muxed 360p mp4), which yt-dlp hardcodes as PoToken-exempt so it always arrives complete. It's the backstop if `VISIONOS` dies; `pickBestAudio()` ranks it last since its bitrate counts video and its audio is 22 kHz. When playback breaks, check these client versions and policy flags against current yt-dlp before debugging on-device.

A `Song` carries a nullable `spotifyId` rather than a source enum. Unified-library rows use negative ids: `-(OFFSET + FNV1a(key) % 1e12)` with offsets `3/4/5 × 10¹²` for song/album/artist.

Cloud audio is streamed through a **local Ktor CIO HTTP proxy**: subclass `data/stream/CloudStreamProxy<K>` (route, id type, validation, allowed hosts, URL resolution); the base class handles server lifecycle, URL caching and security checks in `CloudStreamSecurity`. `MediaFileHttpServerService` + `CastSessionSecurity` serve local files to Chromecast the same way.

**Spotify Connect output** (`data/spotify/connect/`, shared spec with the iOS port) plays the queue on a Spotify Connect device (Echo, TV, speaker) through the Web API Player endpoints; the device streams from Spotify. `SpotifyConnectController` owns the session; while one runs MusicService swaps `SpotifyConnectSessionPlayer` (a `ForwardingSimpleBasePlayer` over the local player) into the MediaSession, so every controller drives the device and the local ExoPlayer stays paused with its queue. The rules live in pure, unit-tested pieces: `SpotifyConnectWindow` (≤100 URIs from the current entry, misses skipped, stop at the first unresolved), `SpotifyConnectReducer` (poll → track change / takeover / next window, grace after commands), `SpotifyConnectMatcher`/`SpotifyConnectResolver` (direct id, then `isrc:`, then strict title+artist; JSON cache in filesDir, no Room). The UI entry point is the Spotify Connect section of `CastBottomSheet` via `SpotifyConnectStateHolder`.

### Database

`PixelPlayDatabase` — Room, **version 5**, with real migrations (`MIGRATION_1_2` to `MIGRATION_4_5`). Schemas are exported to `app/schemas`. `createRuntimeArtifactsCallback()` installs the FTS and favorites triggers Room cannot generate. Any entity change means bumping the version and adding a migration from then on.

### Music brain

`docs/brain-spec.md` is the contract; `handoff/2026-11-17-music-brain-stage1.md` says what is built. The engine that makes the app feel like a streaming service: Autoplay continues a queue with related music, some of it not in the library; a station starts from any song or artist; Home has rows of music the listener does not have yet; every pick says why, and "Not for me" sticks.

- **Its own database**, `MusicBrainDatabase` (`brain.db`, version 5, `data/brain/store/`, real migrations in `MusicBrainMigrations`, held against the exported schema by `MusicBrainMigrationsTest`). Never add its tables to `PixelPlayDatabase`. `play_event`, `track_alias`, `track_merge`, `artist_alias` and `feedback` are the listener's history: real migrations only, no destructive fallback. A table change means a version bump, a written migration, the exported schema and a migration test (versions: 1 stage 1; 2 `graph_edge`, `served_list`, `feedback`; 3 `spotify_fact`; 4 `track_dna`, `artist_dna`, `source_miss`; 5 `node_embedding`, `island`, `island_member`).
- **A track from outside the library has no row in the main database.** It plays as `spotify://<synthId>` and `SpotifyStreamProxy` finds its video through the brain's `playable` table (`EphemeralTrackLookup`) after `spotify_songs` said nothing. Register it in `PlayableStore` BEFORE its media item reaches the player. Like, "Add to playlist" and Save import it for good (`EngineTrackSaver`).
- **Queue items the engine added carry a `brain.arm` extra** (`BrainExtras`). They sit under the Autoplay line, are saved with the queue (`PlaybackQueueItemSnapshot.brain*`, with the reason line), and are never anchors for more picks. A pick the listener queued themselves (a Home row's card) also carries `brain.chosen`: it is logged with its arm but is the listener's item, not the engine's tail. Always ask `BrainExtras.isEngineOwned`, never test `brain.arm` yourself.
- `AutoplayPolicy` (pure, unit-tested) decides; `AutoplayController` carries it out inside `MusicService`. Count the queue with `engine.getFullQueue()` / `getCurrentAbsoluteIndex()`, never the player's own count: after a crossfade the player can hold a 200-item window. Append through `DualPlayerEngine.appendToQueueEnd`, never during a transition.
- YouTube Music radios go through `InnerTubeClient.nextRadio` / `browseMusic` (the `@YouTubeOkHttpClient`), anonymous, under `HostBudget` (at most 60 a day, 2 at a time), and are parsed by `InnerTubeMusicParsers` (fixture-tested: `app/src/test/resources/innertube`, cut by `tools/brain/cut_fixtures.py`).
- **Every play goes into the event log**, `play_event`, through `ListeningStatsTracker` -> `PlaySink` (`BrainEventRecorder`), with no five-second floor. The service gives each play a `PlayContext` (start reason, the `brain.*` extras) and each finalisation an `EndReason` (`PlayReasons` is the table of spec 4.2). A new finalisation site in `MusicService` must pass a reason, or the play is recorded as `unknown` and never counts as a skip. The legacy stores (`playback_history`, `feedback_v1`, `song_engagements`) keep their five-second floor; the counters are skipped for a track from outside the library.
- **Everything that decides which music plays is in `:brain`** (`Reward`, `Affinity`, `Session`, `Features`, `Ranker`, `ListBuilder`, `StationPlanner`). The app side only gathers ids and carries out the answer. A change to that logic changes the golden vectors: bump `Brain.SPEC_VERSION`, run `./gradlew :brain:generateGolden`, commit `brain/src/test/resources/brain-golden`, and copy the folder to the iOS fixtures. Never edit a golden file by hand; `BrainGoldenTest` compares byte for byte.
- **The affinity rows are a cache of the log.** `BrainEngine` holds them in memory behind one lock (`withState`), loads them on first use, and rebuilds them in the background (`AppStateRebuilder`: the one-shot `playback_history` copy, the imported plays, the library facts). Imported plays are read from `listening_history.db`, never copied.
- **A re-score of the queue is one edit** (`DualPlayerEngine.replaceQueueTail`), only of the unbroken run of engine items at the end, never of the next track (`AutoplayPolicy.rescoreStart`), never during a transition.
- **A station is autoplay with one fixed anchor** (`Station`, `StationStore`, `StationStateHolder`). It lasts while its seed track is in the queue; any play the dispatch path starts that is not the station's own start ends it (`BrainPlayTags.take`, called first thing in `internalPlaySongs`). A new way of replacing the queue from the UI must go through that path or call `take` itself. A station keeps going with the Autoplay switch off.
- **What a play carries beyond a song list goes through `BrainPlayTags.expect`** just before the play is asked for (a station's surface, a Home row's per-card arm and reason). Do not add parameters to the dispatch chain for it.
- **Home's "Because you listened to" rows** are `BrainHomeRows`: read from a small file (`brain_home_rows_v1.json`), rebuilt only once the brain is in memory in this process, from the anchors `HomeAnchorStore` wrote beside the event. Their tracks are not library tracks: `prepareForPlayback` registers them in `playable` before the play. `HomeDiscoveryStateHolder` puts the rows in front of the legacy planner's shelves; the planner itself is untouched and gets no `discoveries`.
- **The legacy discovery paths**: `MusicDiscoveryRepository` (the eager import into the library) is deleted (stage 2b). `HomeCatalogRepository` stays because the legacy Home (engine `legacy`, or no brain state yet) still uses its releases and `playableSongs`; its "Beyond your library" text search stays off (`BrainSwitch.LEGACY_EAGER_DISCOVERY = false`). The Muselle 2 gate on Home is gone: `HomeDiscoveryStateHolder` always plans the full variant. Stage 3 deletes the legacy engine.
- **Feedback is a taste change** (`BrainFeedback`): "Not for me" is a song mute in `TasteAdjustmentRepository` (so it shows in Taste changes) plus the `NOT_FOR_ME` flag on the play in flight; flag first, then skip. `AutoplayController` drops what a taste change excludes from its pool and from the engine's tail.
- **Reason lines**: the key and its argument come from `:brain` (`WhyLine`), the sentence from `strings_brain.xml` through `WhyLineText`, which shows nothing when a sentence needs a name and has none. Song menus, queue rows and the artist page reach the brain's actions through `LocalBrainUi` (provided in `MainActivity`), not through new parameters.
- **Copy** (spec section 8): no brain string may say lossless, hi-res, Dolby, Atmos, 360 Reality, Claude, Anthropic, BiniLyrics or Enhanced, and nothing says "learning" before stage 3 ("based on what you play"). `BrainCopyTest` reads `strings_brain.xml` and fails on any of them.
- **Autoplay never looks at a queue that is still being attached.** Tapping a song in a playlist sets a one-item queue and attaches the rest a moment later; `QueueAttachSignal` (driven by `PlaybackStateHolder.beginQueueAttach` / `endQueueAttach`, opened BEFORE the tapped song is set) makes `AutoplayPolicy.decide` answer `ATTACHING` meanwhile. A top-up there would cost the listener their playlist. The same signal tells Autoplay about a Next pressed with nothing queued.
- **One plan at a time in `AutoplayController`**: a top-up waits for a re-score that is being planned and the other way round; a batch held during a crossfade goes in only if the queue is still the one it was planned for (`AutoplayPolicy.heldBatchStillFits`).
- **A change of direction is a detour** (`Direction`, in `BrainEngine.Loaded`): only three ENGINE picks skipped in a row turn the engine, and the listener's next own track ends the turn. `SessionState.reseedCount` is not read for this.
- **Offline the pool is the library alone, and only tracks with a copy on the phone** (`AutoplayPolicy.playsWithoutNetwork`, `song_cache`). With the YouTube source off it is the library alone.
- **Shuffle leaves the engine's picks alone** (`EngineTail`): it mixes the listener's part of the queue, and a rebuilt queue reuses the player's `MediaItem`s so their `brain.*` extras survive. A `PLAYLIST_CHANGED` onto the same track at the same position is the same play going on (`PlayReasons.continuesSamePlay`), and a cancelled crossfade is not a finished play (`PlayReasons.isCancelledHandOver`).
- **The knowledge layer (stage 2b, `docs/brain-spec.md` 3.2 to 3.5, 3.7)**: the genre atlas is `app/src/main/assets/genre-atlas-v1.json.gz`, built offline by `tools/atlas/build_atlas.py` from CC0 data (ids are an append-only registry, `tools/atlas/sources/ids.tsv`: a stored tag id must never move), pinned by `AtlasTest`'s SHA-256, parsed by `GenreAtlas` (in `:brain`) off the main thread by `GenreAtlasStore` the first time something needs a genre, never at app start. Unknown genres are ABSENT, never guessed: no tag, no genre chip. The five open-data hosts (Deezer, MusicBrainz, ListenBrainz, Wikidata, iTunes) go ONLY through `KnowledgeHttp` (one door: the source's own off switch, local and remote `brain.sources.<name>.enabled`; its `HostBudget` and `KnowledgeLedger`; spacing; back-off) with `@KnowledgeOkHttpClient`, whose User-Agent names the app. Never inject the default or the YouTube client for them. Enrichment (`DnaEnricher`, `DnaTargets`) runs as lanes 3 to 5 inside the one `BrainRefreshWorker` run, never as a second job; `DnaAssembler` is the pure part. Third-party terms per source are in `KnowledgeHost.terms` and the stage 2b handoff.
- **It knows your music (stage 2b, milestone 2; `handoff/2026-11-19-music-brain-stage2b.md`)**: `:brain` has `FastRp` (Int8[64] embeddings of the persisted graph), `Ppr` (personalised PageRank), `Islands` (weighted spherical k-means over the listener's best 1,500 tracks, ids stable across refits by `Islands.matchIds`; `IslandNames` names an island from real atlas genres, never invented), `ArmRules` (SIM, BACK, EDGE), `ListMixes` (On Repeat, Rediscover, Just Dropped), `Calibration` and `SimilarityIndex` (5.3, 6.5), features 5, 6, 19, 20 and 39 behind `IslandContext`, and `HomePlanner` (the page rules). All of it is pure and golden-tested (`fastrp`, `ppr`, `islands`, `arms`, `listmixes`, `islandfeatures`, `islandmixes`, `homeplan`): changing it means regenerating the golden folder and copying it to iOS. The app side: `IslandBuilder` fits embeddings and islands (weekly or after 200 qualified plays; called at the end of the one refresh job and by the Home plan, never at app start), `IslandStore` keeps them in `brain.db` v5, `CandidateDna` adds tags, popularity and embeddings to a pool, `BrainMixes` builds a list by `MixSpec` (Daily Mix of an island, Your Mix across islands, First Listen, Just Dropped, On Repeat, Rediscover; surface id in `served_list.surfaceId`; First Listen is weekly), `HomePlanBuilder` plans the page and `HomePlanStore` saves `files/brain/home_plan.json` (under 50 KB), `HomePlanStateHolder` reads it before anything else and shows a new plan on the next Home appearance. `HomeDiscoveryStateHolder` does not rank the library when the plan owns Home. Genre and mood stations are `GenreStations` plus a `Station` with source `SOURCE_GENRE` / `SOURCE_MOOD`; the Search tiles are stations when the atlas knows the genre and the listener has songs under it. Cold start: `ColdStart` (eight radios, the strip), `PickedArtists` ("Pick three artists you love"), the setup page `Taste`. Jump back in reads `RecentContexts`, written from `PlaybackDispatchStateHolder`. A new Home row must be keyed, drawn from the plan, and listed in `compose_stability.conf`.
- No brain work at app start. Stage 2a has ONE background job, `BrainRefreshWorker` (unique periodic `brain_refresh`, 24 h, unmetered, battery not low): it is scheduled after the first mix build, never at app start, and its rules (budget, off switches, never while music plays, idempotent) are `BrainRefreshRunner`. Recently Played waits out the app's first seconds before it asks `brain.db` (`BrainSessionStateHolder.engineLookupWaitMs`). Timings: `brain_state_load`, `brain_pool_build`, `brain_recent_lookup`, `brain_mix_build`, and (stage 2b) `brain_atlas_load` and `brain_dna_round` (+ the counter `brain_dna_tracks`) in `PerformanceMetrics`.
- **Daily Mix and Your Mix with new music** (stage 2a, `BrainSwitch.engine = legacy | brain`, default brain, a Developer mode switch; legacy is used when there is no state): `BrainMixes` builds them from a library shortlist plus the cached radios of the listener's own tracks (never the network), `MixPlanner` in `:brain` plans, `ServedListStore` saves the list in `served_list` (that IS the day's mix, restored after a restart without loading the brain, and the repetition budget: no track in two mixes of a day). A mix row from outside the library is registered in `playable` before it is shown (`playableStore.register`), plays through `expectMixPlay`, and offers Save. `graph_edge` holds the radio edges (`GraphEdgeStore`, written when a radio is stored). `AiDailyMixWorker` does nothing while the brain owns the mixes. Handoff: `handoff/2026-11-18-music-brain-stage2a.md`.
- **Stage 2a, milestone 2** (`handoff/2026-11-18-music-brain-stage2a.md`, second half): *New releases* are `NewReleases` (one file, `brain_new_releases_v1.json`, rules in `NewReleaseRules`: a first look is a baseline, a re-release is dropped, the year must be current) found by lane 2 of `BrainRefreshRunner` (six artists a run, its own 14-request share) and shown as Home's "New from artists you play" (replaces the legacy releases shelf when it has 6 cards). Stations come from a song, an artist (also one the library lacks: `BrainRepository.beginArtistStation`), an album and a playlist (`Station.source`, `StationSeeds`); similar artists' top songs are arm SIM; a station under 15 in its pool re-seeds from its best-received track. Feedback: "More like this" is a 14-day artist boost, "Don't count this" is `LearnExclusion` (flag, `Affinity.subtract`, rebuild, a `feedback` row), Private session is `PrivateSession` (memory only, OFF at every app start, spans in `feedback`), a swipe on an engine row is `Session.weakNegative`, "Refresh picks" is `AutoplayController.refreshPicks`. None of those labels may say "learn". The Spotify facts the sync returns go to `spotify_fact` through `BrainFactsSink` (the switch "Use my Spotify music to shape picks" empties the sink and removes Spotify's history and lists at the next rebuild; nothing is named "training"). `AutoplayControllerHarnessTest` drives the controller's trigger over mocks (no Robolectric): extend it rather than reading the controller to check a change.

### Theming

The Android UI is Material 3 Expressive only (tonal `surfaceContainer*` roles, Expressive shapes, springy motion, standard M3 components). There is no Liquid Glass / blur / lens mode on Android (removed 2026-10-12; iOS keeps its own). Do not add translucent glass panels or a glass theme switch.

Material You plus album-art color extraction: `ThemeStateHolder` + `ColorSchemeProcessor` derive schemes from artwork, cached in the `AlbumArtThemeEntity` table and reused by the Glance widgets (`ui/glancewidget/`) and the Wear app.

The app scheme is Material You ("Dynamic", the default) or the accent the user picks in Settings › Appearance › Accent Color: `accent_color_v1` (`"#RRGGBB"`, `""` = Dynamic, same key and format as iOS) → `ThemeStateHolder.accentScheme` → `PixelPlayTheme(accentSchemePair)`. Accent schemes are the vivid seed-chroma variant (`generateAccentColorSchemePair` in `ColorRoles.kt`, memoised by `AccentColorSchemes`; `ColorScheme` has no `equals`, so always go through the memo). The player keeps album colours under Player Theme › Album Art; Player Theme › Accent Color (stored `"dynamic"`) uses the app scheme, and so do the widgets and the watch.

### AI

`data/ai/` — provider-agnostic (`AiProvider`, `AiClientFactory`, `GeminiAiClient`, `GenericOpenAiClient` for OpenAI-compatible endpoints). Playlist generation and metadata work run through `AiWorker`/`AiWorkerManager` with results cached in `AiCacheEntity` and usage tracked in `AiUsageEntity`.

**Ask Taizo and taste** (`data/ai/claude/taizo/`, `data/recommendation/`). `get_taste_profile` is built by code (`TasteProfileBuilder`, fed by `TasteProfileProvider`, cached until a new play or taste change), never by the model; the chat context carries its short digest. Explicit **taste adjustments** (boost / reduce / mute / block of an artist, song, genre or album) live in `TasteAdjustmentRepository` (a DataStore JSON array, no Room table) and reach the engine as a `TasteAdjustments` snapshot: `MusicRecommendationEngine.rank` (and `Muselle`, `HomeRecommendationPlanner`, `DailyMixManager`, `HomeDiscoveryStateHolder`, the prepare-ahead ranker) take it as a defaulted parameter, and with none the output is bit-identical to before. Boost/reduce are capped multipliers on the artist/genre/song affinity (`TasteAdjustmentMath`); mute keeps a song out of mixes and shelves, block keeps it out of everything automatic and out of what Taizo queues. Only the user's own message may justify a change: `adjust_taste` needs `user_asked` plus a real user message in the turn (`TaizoToolRunner.beginTurn`), and block / big batches / clear-all ask for a tap. Users see and undo every change in Settings › Library › Ask Taizo's taste changes. `search_library` is the one non-strict tool (a dozen optional filters would pass the API's 24-optional-parameter cap for strict tools); keep the strict tools' optional parameters under that cap (`TaizoTasteToolsTest` checks).

## Conventions

- **Compose stability:** `app/compose_stability.conf` force-marks domain models and UI state classes as stable. If a new model type causes recompositions, add it there rather than restructuring the model.
- Immutable collections (`kotlinx.collections.immutable`) are used for anything held in Compose state — queues are `PersistentList<Song>`; see the `toPlaybackQueue()`/`replaceSong()` helpers in `PlayerViewModel.kt`.
- Logging goes through Timber; `ReleaseTree` strips it in release builds.
- Diagnostics: `data/diagnostics/PerformanceMetrics.kt` is a process-wide, allocation-light recorder feeding the user-exportable performance report. See [docs/performance-report.md](docs/performance-report.md) before adding instrumentation.
- Comments and some docs are a mix of English and Spanish; match the surrounding file.
- `message.txt` at the repo root is a stale build log, not documentation.

## CI

GitHub Actions (JDK 21, Temurin): `android-ci.yml` on every push and PR ("Compile, test, build debug APK": runs `:app:testDebugUnitTest`), `phone-debug.yml` on push/PR to `main` (assembles + verifies split APK signatures), `phone-release.yml`, `wearos-apk.yml`, `nightly-apk.yml`, and CodeQL (`java-kotlin`) which builds `:app:assembleDebug :wear:assembleDebug`. Instrumented tests still only run locally.

## License

Proprietary (see [LICENSE](LICENSE)); contributions before 2026-05-12 remain MIT — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), which is copied into the APK assets by the `copyThirdPartyNotices` Gradle task.
- **Review pass of 2a** (handoff, last section): a play that does not count (Private session, "Don't count this") reaches NEITHER the brain's affinity NOR the legacy counters (`PlaySink.isPrivate`, the tracker's `learnExcluded`) and is not a seed or a skip toward the ignored-three-times ban (`flags & 4096` in `SeedDao` / `PlayEventDao`); a rebuild re-applies active `DONT_COUNT` rows to imported plays (`ReplayMerger` `excluded`). Every YouTube request is a token of `HostBudget`, which tells `RequestLedger` (persisted, 60 a day, all callers); the worker stops at 40 and lane 1 leaves `RELEASES_RESERVE` for the releases lane. A launch on a day with a saved mix reads no brain data: `DailyMixStateHolder.onMixSurfaceOpened` reads it when a mix screen opens (`full`) or, after the quiet period, from Home (saved rows only), and `publishBrainMixes` never throws (a bad `brain.db` is the legacy mixes). `graph_edge.type` 3 is a chip or continuation list.
