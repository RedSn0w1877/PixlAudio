# Handoff — offline UX, recommendation explanations, storage (September 10, 2026)

Written by Claude (Opus 5). **This supersedes the status sections of
`2026-09-09-plus-features-claude.md`** — that file still describes the Plus tests as unverified
and predates everything below.

Active tree: `C:\Users\Hoa Vo\Downloads\Code Projects\PixelPlayer-master\beta2-release`
(there is an older, unrelated checkout at `Downloads\PixelPlayer-master\PixelPlayer-master` —
do not work there; it is version code 11 and has no `data/premium/` at all).

## Two environment facts that cost previous sessions a lot of time

**1. The recurring `AccessDeniedException` / "Unable to delete directory" is NOT Android Studio.**
The `app/build` tree acquires the **ReadOnly** attribute, and Gradle then cannot clean its own
incremental directories. Verified directly: the failure reproduced with *no* `studio64`, `java`
or Kotlin daemon process running at all, and `(Get-Item ...).Attributes` reported `ReadOnly,
Directory`. Fix:

```powershell
attrib -R "app\build\*" /S /D
```

It **comes back**, so re-run it whenever a build fails on directory deletion. Every build in this
session was preceded by it. Closing Android Studio was never the actual remedy.

**2. Gradle runs fine from an agent shell** given the environment Codex documented:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='"-Djdk.net.unixdomain.tmpdir=C:\Users\Hoa Vo\Downloads\Code Projects\PixelPlayer-master"'
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain '-Pksp.incremental=false'
```

An earlier session concluded builds were impossible from a sandboxed shell because of
`java.io.IOException: Unable to establish loopback connection`. That conclusion was wrong — the
`unixdomain.tmpdir` flag above is exactly what works around it. Do not re-derive this.

## Verification status (what is actually proven)

- **Plus suite: 12 tests, 0 failures** (`PremiumEntitlementsTest` 7, `PremiumSmartToolsTest` 3,
  `CloudStudioAndExportTest` 2). This closes the open question from the previous handoff:
  Codex's `java.net.URI` fix for `CloudStudioConfig` URL validation **works**. The test run that
  was interrupted has now completed successfully.
- **Cache suite: 9 tests, 0 failures** — `OfflineAudioRetentionTest` (5, pre-existing, still
  green after the DAO change below), `AudioDownloadCancellationTest` (1, pre-existing),
  `AudioDownloadProgressTest` (3, new this session).
- **Everything below compiles** (`:app:compileDebugKotlin` green) and is packaged in a debug APK.
- **Nothing below has been seen running.** No device was connected this session and no emulator
  exists yet. Every UI change is compile-verified only. This is the single largest open risk.

## The pattern worth knowing before you add anything

Three separate features in this codebase had a **complete data layer and no UI**. Each was
written on every run and read by nothing:

- `SongCacheStateCache` — per-song download state. Zero UI consumers.
- `SongDownloadWorker.OUTPUT_FAILURE_REASON` — written on every permanent failure. Zero readers.
- `SpotifyMatchStateCache` — pre-warmed at startup in `PixelPlayApplication`, with a comment
  explaining it exists so the per-row indicator is ready. That indicator did not exist.

The check that found all three is just: **does anything read this?** Run it before building
something new — finishing what is already half-built is far cheaper.

## Work done this session

### Offline downloads (the headline bug)

`SongDownloadWorker` returns `Result.success()` when it gives up after 4 attempts — deliberately,
because a real failure would cancel every download queued behind it in the shared
`song_download_queue`. It wrote a failure reason into `outputData`, which nothing read. Net
effect: **a failed download reported success, left a stuck row (`is_permanent=1, is_complete=0`),
and was indistinguishable from a song you had never touched.** Tapping Download appeared to do
nothing, forever, with no error and nothing to retry.

- `SongCacheState` gained `DOWNLOADING` (3) and `FAILED` (4).
- `SongCacheDao.getAllCompleteFlow()` → **renamed** `getAllCacheStatesFlow()` and no longer
  filters `is_complete = 1`. That filter was the reason in-progress and failed downloads could
  never be displayed. `SongIdAndCacheFlags` gained `isComplete`.
- `AudioCacheManager` persists failure reasons in SharedPreferences (`offline_download_failures`).
  WorkManager prunes its history, so `outputData` would lose the reason exactly when the user
  went looking. Cleared on retry (`requestDownload`) and on success.
- Only *explicit* downloads are marked failed; auto-cache fails silently by design.
- `SongCacheStateCache` also publishes `failureReasons`.

### Download progress

`AudioDownloadTransfer` already tracked `bytesWritten`/`expectedBytes` and told nobody. It now
takes an `onProgress(percent)` callback, fired **only when the integer percent changes** (64KiB
chunks would otherwise fire hundreds of times per song for a 100-step bar). `DownloadProgressCache`
holds live percentages **in memory only** — unlike state and failure reason, which persist. If the
process dies the download dies with it, so restoring "47%" at next launch would report progress on
something that is not happening. No `Content-Length` → no callbacks → the UI shows an
indeterminate bar rather than inventing a number. Covered by `AudioDownloadProgressTest`.

### UI surfaces (new)

- `EnhancedSongListItem` — `SongAvailabilityBadge`. Unmatched Spotify track (red cloud-off) takes
  priority over download state, because a track with no playable source cannot play at all.
  Then downloaded / downloading / failed. Subscriptions are per-row and `distinctUntilChanged`,
  so one song changing does not recompose the whole list.
- `OfflineDownloadCard` (new file) — in the song ⋮ sheet, after `TaisStudioProgressCard`.
  Download / Try again / Remove download, the real failure text, and a progress bar.
  `SongInfoBottomSheetViewModel` gained `downloadSong` / `removeDownloadedSong`.
  Note `removeDownload` had **no callers at all** before this.
- Settings → Music Management → **Offline storage**. Shows downloaded size + count and
  auto-cache size; "Free up space" clears **only** the auto-cache. Deleting what the user
  explicitly saved is not freeing space. Uses `Formatter.formatShortFileSize` for locale-correct
  units. Backed by new `AudioCacheManager.storageUsage()` / `clearAutoCache()` and two DAO queries.
- `AutomaticStudioSettingsCard` — the live status line was only rendered when the "Queue and
  diagnostics" expander was open. This feature emits no notifications by design, so hiding its
  only status behind an expander meant there was no way to know it was working. Status is now
  always visible; the verbose explanation stays behind the expander.

### Recommendation explanations

Every `Pick` already carried a human-written reason ("One of your favorites", "You often finish
this song", "Discover more from an artist you enjoy"); Muselle 2 adds its own. `HomeMusicSection`
kept only `it.song` and dropped them, so they appeared **only** in the Settings ranking preview.
`HomeMusicSection` now carries `reasons: ImmutableMap<String, String>` and the shelf card shows it
under the artist.

Negative reasons are filtered: `MusicRecommendationEngine.REASON_PREFIX_DEMOTED` ("Less often")
marks reasons that explain why a song surfaces *less*. Showing "frequently skipped" on the card
that is recommending that song contradicts itself. Use that constant rather than matching the
string.

### Home shelf starvation (real bug, found by running the full suite)

`HomeRecommendationPlannerTest > back in rotation excludes recently played and never played
songs` was failing. Not a flaky test and not caused by the `reasons` change — a genuine product
bug from the September 9 shelf additions.

`addShelf` marks every song it places into `used`, so a recording only appears on one shelf. But
`recently_added`, `on_repeat` and `artist_radio` were each handed the **entire local library**,
merely sorted differently. Those three therefore consumed everything (up to `limit` each) and
starved every shelf below them: **Favorites, Back in rotation and Waiting to be heard would come
up empty or disappear entirely on a real library.**

Fixed by making each shelf actually select what its title claims:

- `recently_added` — only songs added within 30 days. Uses `normalizeTimestampMs`, matching
  `DailyMixManager.computeNoveltyScore`'s seconds-vs-milliseconds heuristic, so "30 days" means
  the same thing in both places. A shelf called "Recently added" showing the whole library sorted
  by date is not telling the truth.
- `on_repeat` — needs `plays + completions >= 2` **and** a play within 7 days. The recency half
  matters: "songs you keep coming back to" and "Back in rotation" ("haven't played in a while")
  are opposites, so without it `on_repeat` was stealing exactly the songs `rediscover` looks for.
  They are now mutually exclusive by definition.
- `artist_radio` — requires `MIN_ARTIST_RADIO_SONGS` (3). A "radio" around a one-song artist is
  not a radio, and with one song per artist it consumed a song from every later shelf.

**Lesson for adding shelves:** anything passed to `addShelf` is claimed exclusively. A new shelf
that receives an unfiltered `local` will silently empty every shelf declared after it, and the
only signal is this test. Run `:app:testDebugUnitTest` after touching the planner.

## Hazard: `SettingsUiState` uses a positional combine

`SettingsViewModel` builds `SettingsUiState` from an **indexed** `combine` — `values[17] as Float`
and similar. Adding a flow to that combine silently shifts every later index and mis-assigns
settings **at runtime, with no compile error**. Storage usage was deliberately put in a separate
`StateFlow` for this reason. Do the same for any new setting.

## What is left

**Do this first: get eyes on the UI.** Three sessions of work (lyrics rewrite, Codex's Plus/Home
work, this session) are validated only by "it compiles". Either connect the phone
(`adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`) or create an AVD.

For an emulator: `$env:LOCALAPPDATA\Android\Sdk` has system images (android-34/36/37 x86_64) and
`emulator.exe`, but **no AVD exists and `cmdline-tools`/`avdmanager` are not installed** — create
the AVD through Android Studio's Device Manager. Also note the current APKs are **arm64-only**
(ABI splits on); an emulator needs a build with `-Ppixelplay.enableAbiSplits=false`.

Not done, roughly by value:

- **Now-playing polish and motion** — Codex's stated next item, never started. Biggest remaining
  visible-quality work, and the one that most needs a screen to judge.
- **Queue reasoning** — deliberately skipped. The queue is a bare `List<Song>` threaded through
  `MusicService`, the single source of playback truth. Adding per-song reasons there is deep
  surgery on the most fragile part of the app and should not be attempted without on-device
  playback verification.
- "Available offline" filter, smart charging downloads, Music DNA, smart radio, listening
  memories, session builder, energy curve, version awareness, one-tap repair, artist pages.

**Unchanged constraints:** no live Plus checkout (no URL, no backend, no verifier — safe preview
mode, keep it that way until the hosted side exists), and no verified Tensor G5 NPU execution
(no AOT-compiled model; the vendor compiler download still 404s).
