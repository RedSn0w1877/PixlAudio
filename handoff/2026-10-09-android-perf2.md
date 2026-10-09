# Android performance pass 2, 2026-10-09

Branch `perf2-android-oct9`, one PR into `main`. Same rules as round 1 (`2026-10-08-android-perf.md`): look and
behaviour unchanged, CI (`android-ci.yml`) is the only compiler/test runner, nothing built locally.
Audit finding ids are in brackets.

## What changed, and why it is faster

1. **Models released when idle** `[and-lyrics-ai-bg-4]` The 378 MB wav2vec2 session and the 67 MB stem model used to stay
   in memory for the rest of the process. Both are now closed 90 s after the last job (never while one runs) and the
   alignment model also on `onTrimMemory >= RUNNING_LOW`. The next job pays the model load again.
2. **Play history** `[and-lyrics-ai-bg-1]` Every played song used to read, parse twice, serialise and fsync the whole
   history JSON (MBs after months). Now the history is loaded once and kept in memory; each play appends one ~130 byte
   line to `playback_history.log`; the base `playback_history.json` is rewritten only when old events are pruned,
   after 200 appended lines, or by an import/restore. A torn last line after a crash is skipped; a crash between
   "base written" and "log deleted" is de-duplicated on load. Tests: `PlaybackHistoryLogTest`.
3. **Silent second player** `[and-lyrics-ai-bg-5]` A second ExoPlayer (extra PCM track + wake lock) ran for the whole length
   of every song that has an instrumental, and its file checks ran on the main thread at each skip. It is now built
   only when the instrumental toggle is used (waits up to 0.6 s for it to be ready, then the same 700 ms crossfade) and
   released again after switching back.
4. **Artist images** `[and-data-5]` Prefetch writes images in batches of about 24 (one Room transaction, so the artists
   queries re-run per batch, not per artist), does not restart when the new candidate list is a subset of the running
   one, and keeps "Deezer has no such artist" across app-hidden (`trimMemory()` only drops the URL cache). Test in
   `ArtistImageRepositoryTest`.
5. **Importing one catalogue track** `[and-data-4]` `importTracks` wrote every mirrored song and mirrored every playlist before
   the matcher could start. It now writes only the imported songs (plus their albums/artists, counts still over the whole
   mirror) and mirrors only the browse playlist. Full rebuild stays for sync/removal.
6. **Taste signals** `[and-lyrics-ai-bg-7]` The up-to-5,000-entry signal JSON is decoded once (memoised by string) and
   sorted only when over the cap.
7. **Stored lyrics parse** `[and-lyrics-ai-bg-6]` Parsed on `Dispatchers.Default`, and only while no lyrics are loaded.
8. **YouTube session storage** `[and-playback-7]` `EncryptedSharedPreferences` (Keystore/Tink) is lazy and warmed on the
   startup IO scope, like Spotify's, instead of being built on the main thread through the player graph.
9. **Album colours** `[and-images-theme-4, and-images-theme-6]` Concurrent requests for one cover share one decode +
   quantisation (own scope, so one cancelled caller does not fail the rest); album tiles whose scheme is already in the
   memory cache start with it instead of null then recompose.
10. **Cover extraction** `[and-images-theme-8]` One extraction per song at a time, tmp+rename write, no MediaStore query on a
    cache hit.
11. **Folder filter** `[and-data-9]` `canonicalPath` memoised per folder string.
12. **Marquee** `[and-player-marquee-reset]` Title/artist marquee state is keyed on the layout part of the style, so the colour
    fade after a skip no longer resets it every frame.
13. **Baseline profile** `[and-home-search-9, and-library-3, and-playback-6, and-settings-shared-7]` Rules for Home sections, Search,
    detail screens, settings, list building blocks, streaming (stream/youtube/spotify/cache), Room, paging and Ktor.
    Still not regenerated on a device. Test: `BaselineProfileCoverageTest`.

## Skipped, and why

- `and-images-theme-1` (art keyed per image, L): changes the Coil key, the palette DB key and needs a lazy migration of every
  `song_art_*` file; cannot be verified without a device. Biggest remaining win.
- `and-data-1` (heart tap re-runs library queries): needs dropping the favourites->songs trigger and auditing the ~66
  `isFavorite` readers (incl. the Liked paging query) plus a migration. Not safe blind.
- `and-library-1` / `and-data-8` (paging sorts whole table): static ORDER BY per sort option, new indices and a Room
  migration; needs EXPLAIN-based tests (sqlite-jdbc is not a dependency) and exact order parity.
- `and-playback-3` (listening cache downloads streamed songs twice): the fix is inside the proxy chunk loop (serve from the
  growing cache file); owner decision of 2026-10-07 also fixes the cellular behaviour. Needs on-device streaming tests.
- `and-player-queue-warm-on-expand`, `and-player-scheme-fade`: large Compose refactors (760-line `FullPlayerContent`, queue sheet)
  whose "same pixels" cannot be proven without Compose/Robolectric tests.
- `and-playback-9` (wake mode): adds a Wi-Fi lock, a battery trade-off not a speed-up.
- Other medium ones not started: `and-data-6/7/10`, `and-home-search-4..8`, `and-library-4..7`, `and-images-theme-5/7/9/10`,
  `and-player-*` (ambient, queue row, slice churn), `and-settings-shared-5/6`, `and-playback-5/8`, `and-lyrics-ai-bg-8`.
- **Startup pass (and-startup)**: Application already builds everything behind `dagger.Lazy`, warms secrets/Keystore on
  an IO scope, WorkManager's initializer is removed, release has R8 + resource shrinking + dex layout + profile. The only
  clear win found was item 8. `android.enableR8.fullMode=false` is left off: full mode with Gson/reflection-heavy code
  needs a release-build smoke test.

## Behaviour you could notice

- First tap of the instrumental toggle on a song can take up to ~0.6 s longer (the player is built then, not at song start).
- Artist images appear in batches (up to ~1.5 s later) during the first prefetch.
- Automatic lyric alignment / stem jobs reload their model after 90 s idle (a few seconds on the next job).
- Backup of play history still exports from memory; `playback_history.log` is a new device-local file.

## Phone checks (plain language)

- Skip through songs for a few minutes, then open Stats: counts and top lists look right; kill the app, reopen: still there.
- Play a song with an instrumental and screen off: battery drain should be lower; tap the instrumental toggle: it fades in
  within a second and back to normal when toggled off.
- Open Artists after a sync: images fill in without the list reloading repeatedly.
- Search a Spotify/YouTube track and tap it: it starts sooner when you have many imported songs/playlists.
- After lyric alignment has finished and the app sits idle, the process memory (Settings > Apps) drops by several hundred MB.
- Cold start: no visible change; first open of Home/Search/detail screens a bit smoother after a fresh install.

## What remains

See "Skipped". Start with `and-images-theme-1`, `and-data-1` + `and-library-1` (together, one DB version bump), then the player
colour-fade refactor. Regenerate the baseline profile on a device.
