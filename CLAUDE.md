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
- `local.properties` supplies `SPOTIFY_CLIENT_ID` → `BuildConfig.SPOTIFY_CLIENT_ID`. If absent it is an empty string and Spotify sign-in is disabled at runtime (`SpotifyAuthManager.hasClientId()`).
- A `benchmark` build type (release, non-debuggable) exists for the macrobenchmark/baseline-profile modules.

## Module layout

- `:app` — the phone app; everything below lives here unless noted.
- `:shared` — Android library holding only serializable DTOs for the phone↔Wear Data Layer protocol (`Wear*.kt`). No platform APIs; keep it dependency-free.
- `:wear` — standalone Wear OS app, its own Compose UI + Hilt graph, talks to the phone via `:shared` DTOs.
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

`MusicRepository`/`MusicRepositoryImpl` is the aggregate library API over Room. `MediaStoreSongRepository` reads local files; `SyncWorker`/`SyncManager` (WorkManager) do incremental/full library syncs, artist parsing (`ArtistParsingUtils`, configurable delimiters) and album grouping (`AlbumGroupingUtils`).

**Spotify is the only remote source.** The six upstream sources (Telegram, Navidrome, Jellyfin, Netease, QQ Music, Google Drive) were removed from this fork along with TDLib.

- `data/spotify/` — `SpotifyAuthManager` (OAuth 2.0 PKCE via Custom Tabs, redirect `pixelplay://spotify-callback`, tokens in `EncryptedSharedPreferences`), `SpotifyRepository`, `SpotifyStreamProxy`. **Spotify rotates the refresh token on every refresh; `saveTokens` must persist the new one or the account dies after one rotation.**
- `data/network/spotify/` — Retrofit services for `accounts.spotify.com` (OAuth) and `api.spotify.com`.
- `presentation/spotify/{auth,dashboard}` — the login bridge activity and the account dashboard.
- `data/worker/SpotifySyncWorker` imports metadata, then chains `SpotifyMatchWorker`.

Spotify's Web API returns **metadata only** — no audio, and preview URLs are gone for new apps. `data/youtube/` matches each track to a YouTube Music video (`TrackMatcher` scores title/artist/duration and penalises live/remix/cover variants) and resolves playable audio (`InnerTubeClient`, `YouTubeStreamResolver` strategies, `SignatureCipherSolver` running `base.js` through the `JsEvaluator` WebView). That layer breaks whenever YouTube changes something — swap a `YouTubeStreamResolver` implementation, not the feature. `PlaybackDiagnostics` walks the whole chain on one track and reports which step failed; the Spotify dashboard's "Test playback" button surfaces it.

> **Never inject the default `OkHttpClient` into anything that talks to YouTube.** It carries an interceptor that *overwrites* `User-Agent` with `PixelPlayer/1.0`, which contradicts the client identity in the InnerTube request body and gets the call rejected. Use `@YouTubeOkHttpClient`.

Browsing the Spotify catalogue (search, artist top-tracks and albums, the user's most-played) lives in `presentation/spotify/browse`. Drill-down is ViewModel state rather than nav routes, so going back doesn't re-fetch. Tracks found there are imported into a synthetic `SpotifyPlaylistEntity.BROWSE_ID` playlist and then go through the same matcher as everything else. Reading most-played needs the `user-top-read` scope, which was added after the first release — accounts linked before it must reconnect once.

Audio tops out at roughly 256 kbps: YouTube serves no lossless stream by any route, so `pickBestAudio()` just takes the highest bitrate on offer.

**Which InnerTube client resolves the audio is the whole ballgame** (`InnerTubeContexts.kt`). Playback works because of `VISIONOS` — the only client in yt-dlp's table declaring neither a `GVS_PO_TOKEN_POLICY` (so its audio needs no PoToken and never arrives truncated) nor `REQUIRE_JS_PLAYER` (so it returns direct URLs, no `base.js`). It leads `PLAYER_PROFILES` for that reason. The others each toll: `IOS`/`ANDROID_VR` return direct URLs but require a GVS PoToken, so their audio **cuts off around 47-53 s**; `WEB` now returns audio as SABR-only entries (`initRange`/`indexRange`/`contentLength` but no `url` and no `signatureCipher`), i.e. nothing playable. Two invariants:

- **Never send the cookie to a client that doesn't accept it.** `VISIONOS`/`ANDROID_VR`/`IOS`/`ANDROID_MUSIC` are absent from yt-dlp's `SUPPORTS_COOKIES` and answer `HTTP 400` if one is attached — signing in is what used to break exactly the clients that return ready-to-play URLs. Gated by `ClientProfile.supportsCookies`.
- **Always send a fresh `visitorData`, anonymous requests included.** Without one YouTube answers `LOGIN_REQUIRED — Sign in to confirm you're not a bot` even for public videos; it's bot detection, not the account. `PixelPlayPoTokenProvider.anonymousVisitorData()` fetches a real one once per process — the value stored by the login WebView may still be a placeholder YouTube rejects.

`YouTubeAudioFormat.isMuxedFallback` marks itag 18 (muxed 360p mp4), which yt-dlp hardcodes as PoToken-exempt so it always arrives complete. It's the backstop if `VISIONOS` dies; `pickBestAudio()` ranks it last since its bitrate counts video and its audio is 22 kHz. When playback breaks, check these client versions and policy flags against current yt-dlp before debugging on-device.

A `Song` carries a nullable `spotifyId` rather than a source enum. Unified-library rows use negative ids: `-(OFFSET + FNV1a(key) % 1e12)` with offsets `3/4/5 × 10¹²` for song/album/artist.

Cloud audio is streamed through a **local Ktor CIO HTTP proxy**: subclass `data/stream/CloudStreamProxy<K>` (route, id type, validation, allowed hosts, URL resolution); the base class handles server lifecycle, URL caching and security checks in `CloudStreamSecurity`. `MediaFileHttpServerService` + `CastSessionSecurity` serve local files to Chromecast the same way.

### Database

`PixelPlayDatabase` — Room, **version 1**, no migrations. The upstream chain of 39 hand-written migrations was dropped when the remote sources were removed; this is a private fork with no installs to upgrade. Schemas are exported to `app/schemas` and `PixelPlayDatabaseMigrationTest` is now a create-and-open smoke test. `createRuntimeArtifactsCallback()` installs the FTS and favorites triggers Room cannot generate. Any entity change means bumping the version and adding a migration from then on.

### Theming

Material You plus album-art color extraction: `ThemeStateHolder` + `ColorSchemeProcessor` derive schemes from artwork, cached in the `AlbumArtThemeEntity` table and reused by the Glance widgets (`ui/glancewidget/`) and the Wear app.

### AI

`data/ai/` — provider-agnostic (`AiProvider`, `AiClientFactory`, `GeminiAiClient`, `GenericOpenAiClient` for OpenAI-compatible endpoints). Playlist generation and metadata work run through `AiWorker`/`AiWorkerManager` with results cached in `AiCacheEntity` and usage tracked in `AiUsageEntity`.

## Conventions

- **Compose stability:** `app/compose_stability.conf` force-marks domain models and UI state classes as stable. If a new model type causes recompositions, add it there rather than restructuring the model.
- Immutable collections (`kotlinx.collections.immutable`) are used for anything held in Compose state — queues are `PersistentList<Song>`; see the `toPlaybackQueue()`/`replaceSong()` helpers in `PlayerViewModel.kt`.
- Logging goes through Timber; `ReleaseTree` strips it in release builds.
- Diagnostics: `data/diagnostics/PerformanceMetrics.kt` is a process-wide, allocation-light recorder feeding the user-exportable performance report. See [docs/performance-report.md](docs/performance-report.md) before adding instrumentation.
- Comments and some docs are a mix of English and Spanish; match the surrounding file.
- `message.txt` at the repo root is a stale build log, not documentation.

## CI

GitHub Actions (JDK 21, Temurin): `phone-debug.yml` on push/PR to `master` (assembles + verifies split APK signatures), `phone-release.yml`, `wearos-apk.yml`, `nightly-apk.yml`, and CodeQL (`java-kotlin`) which builds `:app:assembleDebug :wear:assembleDebug`. No workflow runs the test tasks — run them locally.

## License

Proprietary (see [LICENSE](LICENSE)); contributions before 2026-05-12 remain MIT — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), which is copied into the APK assets by the `copyThirdPartyNotices` Gradle task.
