# Android: Spotify-only — local songs removed, 2026-10-12

Branch `android-no-local`, one PR into `main`. The phone app no longer has an on-device music library:
Spotify (plus the YouTube audio resolution behind it, and Spotify tracks downloaded for offline play)
is the only source of songs.

## What "local song" was decided to mean

Ambiguous by nature, so the line drawn is:

- **Removed — "local" = a song discovered by scanning the device's MediaStore audio index.** Anything whose
  existence came from `MediaStore.Audio` (library scan, folders, excluded directories, min-song-duration
  filter, rescan/rebuild, the file explorer, the `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` prompts,
  the "open an audio file with PixlAudio" intent filters).
- **Kept — files on disk that belong to a Spotify track.** Offline downloads of Spotify songs are still
  files in app storage, still played from disk, still work offline. They are not "local songs": they have a
  `spotifyId` and a `source_type != 0` row, and nothing about them was touched.
- **Kept — everything that operates on a Spotify song.** Cloud Studio, lyrics (LRCLIB + stored), Sing,
  Connect/Cast, equalizer and audio processing, Glance widgets, quick-settings tile, Android Auto, Wear.
- **Kept — the local HTTP proxies.** `CloudStreamProxy` / `MediaFileHttpServerService` serve Spotify-resolved
  and downloaded audio; unrelated to MediaStore.

## Code

**Deleted:** `MediaStoreSongRepository`, `MediaStorePagingSource`, `MediaStoreObserver`, the instrumented
`SyncWorkerTest`, and `MusicRepository.getAllUniqueAudioDirectories()` (the last caller-less MediaStore audio
scan; without `READ_MEDIA_AUDIO` it would now throw `SecurityException`).

**New:** `RoomSongRepository` — the `SongRepository` implementation, Room-only, every query pinned to
`StorageFilter.ONLINE` (`source_type != 0`) so no leftover on-device row can reach the UI even before the purge
below runs. Replaces `MediaStoreSongRepository` in `AppModule.provideSongRepository`.

**`SyncWorker`** went from ~1.2k lines of MediaStore scanning to a housekeeping worker: it calls the new
`MusicDao.purgeLocalSongs()` (one-shot delete of `source_type = LOCAL` songs plus orphaned albums/artists) and,
on maintenance runs, cleans the album-art cache. `SyncManager` lost its MediaStore ContentObserver,
its foreground catch-up sync and its debounce machinery.

**Onboarding** (`SetupScreen`): the Media Permission page and the Directory Selection page are gone; the
Spotify link page moved to position two, right after Welcome. `MainActivity` no longer gates the app on a
media permission. `AndroidManifest` drops `READ_MEDIA_AUDIO` / `READ_EXTERNAL_STORAGE` and the
`ExternalPlayerActivity` audio `VIEW`/`SEND` intent filters (the activity is now `exported="false"`).

**Library/Settings:** the storage filter button ("All / Local / Cloud") and the "Cloud only" toggle are gone
(meaningless now); the Folders tab is filtered out of `libraryTabsFlow`; Settings lost Excluded Directories,
Minimum Song Duration, the whole Sync-and-Scanning subsection (full rescan / rebuild database / auto-scan LRC)
and the Folder back-gesture switch. Empty states in Library and Home now offer a **Connect Spotify** button
(wired through a `LocalConnectSpotify` composition local provided in `AppNavigation`, so no callback threading).

**Strings** for the removed surfaces were deleted across all 13 locales; the ones that remained were rewritten
away from "your device" wording.

## SPOTIFY_CLIENT_ID

`app/build.gradle.kts` now reads the client id from the **`SPOTIFY_CLIENT_ID` environment variable first**, then
`local.properties`, then empty — so cloud/CI builds can ship a working sign-in. All workflows that build the app
(`android-ci`, `phone-debug`, `phone-release`, `nightly-apk`, `codeql`) pass
`SPOTIFY_CLIENT_ID: ${{ secrets.SPOTIFY_CLIENT_ID }}` into the build env.

**The repo secret `SPOTIFY_CLIENT_ID` must be set by a human** (not done here). Until it is, CI APKs build fine
but sign-in is disabled; the Spotify link page and the dashboard account card now say
"Spotify sign-in is not configured in this build." and disable the button instead of opening a dead browser tab.

The value is sanitised (trim + `[A-Za-z0-9-_]`) before being baked into `BuildConfig`. An empty value never
fails the build.

## Deliberately left alone

- **Dead-but-compiling plumbing**: `FileExplorerBottomSheet` / `FileExplorerStateHolder`, the folder branches in
  `LibraryScreen`/`PlayerViewModel`, `ExternalPlayerActivity` + `ExternalMediaStateHolder`,
  `MediaStorePermissionHelper`, `MediaStoreSelectionUtils`, `SongMetadataEditor`, and the
  `hideLocalMedia` / `minSongDuration` / `folderBackGestureNavigation` / `autoScanLrcFiles` preference flows.
  Nothing in the UI reaches them any more; ripping them out is a large mechanical refactor best done on its own
  branch rather than mixed into this one.
- **Room schema unchanged.** `source_type` and the folder/`parent_dir` columns stay — Spotify rows use them, and
  dropping them means a migration on a v1-no-migrations database.
- **`:wear` still declares `READ_MEDIA_AUDIO`** for its own Downloads screen. Wear is a separate APK with its own
  library story and was out of scope.
- **Unused string resources** left behind by the removed settings rows were not swept (lint is not enforced).
- `baselineprofile/BenchmarkEnvironment` still grants the audio permissions in its benchmark setup; harmless
  (granting a permission the app does not request is a no-op in the shell command path it uses), but it can be
  trimmed next time the baseline profiles are regenerated.

## Verified

`./gradlew :app:compileDebugKotlin` and `./gradlew :app:testDebugUnitTest` green locally.

## To test on device

1. Fresh install → onboarding shows Welcome, then Spotify, and never asks for audio/storage permission.
2. App info → Permissions: no "Music and audio".
3. Connect Spotify → sync → library fills with Spotify songs; play one.
4. Empty library (signed out) shows "Connect Spotify" in Library tabs and in Home's mix placeholder, and the
   button opens the Spotify dashboard.
5. Upgrade path: install the previous build with a scanned local library, then this one — the old on-device songs
   disappear (purged on first launch) and nothing crashes.
6. Offline: download Spotify tracks, enable airplane mode, play them.
7. Spot-check the kept features on a Spotify song: lyrics, Sing, Cloud Studio, equalizer, widget, Android Auto, Wear.
