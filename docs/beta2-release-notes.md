# PixelPlayer 0.7.6 Beta 2

## Changes in this release

- Automatic lyric sync checks available catalogs for real word timings first, then uses local audio for alignment when appropriate. Automatic processing preserves existing word timings; manual sync and resync remain available.
- Automatic instrumental preparation uses songs already stored on the phone. It runs quietly, one job at a time, while PixelPlayer is open. Manual processing takes priority.
- Settings → AI integration includes separate automatic-processing switches, queue status, and a recheck action. Automatic jobs do not post notifications. Battery, temperature, storage, time, and retry limits prevent constant processing.
- Home adds adaptive mixes, favorites, rediscovery, unheard songs, outside-library discoveries, and recent releases from artists you listen to when Spotify is connected. The sections use listening history, likes, skips, and the existing recommendation engine, with cached results available offline.
- Playback prepares the next online song's stream URL after the current song settles, shares concurrent URL requests, and reacts immediately when the stream proxy is ready. Existing streaming clients and playback fallbacks are retained.
- Instrumental rendering reuses FFT scratch memory, reducing repeated allocation without changing model weights or audio reconstruction.
- Includes the earlier Beta 2 word-sync, offline download/instrumental persistence, playlist ordering, and YouTube playback fixes confirmed in user testing.

## Processing details

Automatic work is limited to eight jobs per foreground visit and tracks up to six minutes. Heavy work requires charging or at least 40% battery, a cool phone, and at least 1 GiB free. It stops when the app leaves the foreground; retries respect cooldowns. Longer songs remain available through manual controls. Local acoustic work does not download an entire remote track automatically.

The bundled stem model currently runs on CPU/XNNPACK. The Google Tensor LiteRT runner is integrated, but the validated Tensor G5 compiled model is not bundled. This release does not claim active TPU acceleration or newly trained music/separation weights. Playback and render speed changes require device measurements before quoting an improvement.

## Installation and release identity

Version: `0.7.6-beta2`, version code `13`. Release package: `com.theveloper.pixelplay`. The arm64 APK is for Pixel 10 Pro and other arm64 devices; the armeabi-v7a APK is for supported older 32-bit devices.

This candidate adds quiet whole-library processing through a persistent WorkManager sweep. It continues outside the app, processes one job at a time, ranks the current/recent/favorite library above never-played songs, pauses during playback or manual work, and posts no automatic notifications. Instrumental jobs still require audio already available locally; cloud-only tracks remain manual so the app does not silently consume a large data allowance.

The release retains the signing identity used by the earlier release APK. The development app uses the separate `com.theveloper.pixelplay.debug` package; a release APK does not replace or automatically migrate that app's private data. The existing backup/restore flow transfers selected metadata such as playlists, settings, favorites, and saved lyrics. It does not transfer downloaded audio or generated instrumental files. Keep the debug app installed until you have checked the release app and any data you want to carry over.

## Suggested final device smoke check

1. Open Home, scroll the new sections, and play an outside-library discovery and a mix.
2. Leave a downloaded song with missing lyrics/instrumental playing with the app open. Check automatic queue status in Settings; confirm there is no automatic notification and that manual controls still work.
3. Skip to the next online song, then check playback with crossfade enabled.
4. Check saved word lyrics, instrumental playback, downloaded playback offline, and playlist ordering after reopening the app.
5. If moving from the debug app, verify backup/restore before removing that app.

Computer test totals, final APK checksums, and packaging verification are recorded with the built artifacts. No phone installation or automated phone testing was performed for this release turn.
