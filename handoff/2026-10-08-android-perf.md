# Android performance pass, 2026-10-08

Branch `perf-android-oct8`, one PR into `main`. Goal: big speed-ups with the **look and behaviour unchanged**. Every
fix below changes only how much work is done, not what the screen shows, except the two deliberate
trade-offs called out under "Behaviour you could notice". CI (`android-ci.yml`: compile, unit tests, debug APK) is
the only compiler/test runner; nothing was built locally. Green on CI run 37874982844 (compile, all unit tests, debug APK).

Item numbers are the order they were done in. Each is one commit (the audit finding ids are in brackets).

## What changed, and why it is faster

1. **Tapping a song in a big list** `[and-playback-1]`
   The rest of the queue used to be attached in batches of 200, and every batch made the media session republish the
   *whole* queue (platform queue conversion, a binder call, the notification rebuilt, engine/transition listeners
   re-run): about n/200 republishes, so cost grew with the square of the queue size (5,000 songs = ~25 republishes).
   Now the tapped song starts first, as before, and the queue is attached in **two** calls (before and after it) once
   the song is ready (waits at most 400 ms, and only for queues over 200 songs). Also: the six per-song extras nobody
   reads (genre, track, year, mime type, bitrate, sample rate) are no longer written into every queue item, and the
   notification's "local only" flag is set while it is built instead of rebuilding the finished notification.
   Test: `PlaybackQueueAttachTest` (at most 2 `addMediaItems` calls for 5,000 songs, final queue identical to the old
   batching for many sizes).

2. **"Add songs" sheet and "New playlist" dialog** `[and-settings-shared-1]`
   They each built a *second* `PlayerViewModel` (re-initialising ~15 singleton state holders, a second
   MediaController, and later tearing the singletons down). They now receive the app's real one. Every
   `playerViewModel: PlayerViewModel = hiltViewModel()` default in screens was removed so a missing argument is a
   compile error. Test: `NoSecondPlayerViewModelTest` scans the sources and fails if the pattern comes back
   (`QueueBottomSheet` is the one allowed default; it is only used outside the NavHost).

3. **Settings writes no longer restart library queries** `[and-data-2, and-settings-shared-2]`
   All preferences live in one DataStore that emits on *every* write, so every flow re-emitted the same value and
   restarted the albums query, the album Pager and the folder tree on every tab switch, sync and track change.
   Preference flows (user, AI, equalizer, theme) now drop repeated equal values. Test: `PreferenceFlowsDistinctTest`.

4. **Home recommendations** `[and-home-search-1, and-home-search-3]`
   - The engine compiled a new regex and Unicode-folded names on every comparison (about 3 million times per
     refresh). Names are now normalised once per song, `select()` no longer re-normalises per candidate per slot, the
     regex is only used when really needed, and the planner caches each song's key. Scores, picks and order are
     identical. Test: `RecommendationGoldenEqualityTest` runs a frozen copy of the old engine and planner
     (`LegacyRecommendationEngine`, `LegacyHomeRecommendationPlanner` in the test sources) next to the new ones on
     seeded libraries (awkward spellings on purpose) and requires identical output, and checks a 5,000-song plan
     stays under a generous time budget.
   - Home planned everything twice per refresh even when the discovery catalog was still fresh. It now asks the
     catalog first whether a network refresh is due and only then ranks seeds; an unchanged snapshot is not
     re-planned or re-published. Test: `HomeCatalogRefreshGateTest` (the due/not-due rule).

5. **Typing in Search** `[and-home-search-2]`
   Every keystroke gave every visible result row a new click handler, so all rows recomposed. The list now gets a
   stable reader of the query and one stable click handler that reads the newest values when you tap (same queue
   name, same queue). The section order is a constant. No JVM test (the audit's suggestion needs Robolectric, which
   the project does not have); see "How to confirm".

6. **Colours** `[and-images-theme-2, and-images-theme-3]`
   - Skipping inside an album produced a "new" colour scheme with identical colours, which ran a ~0.33 s fade and
     recomposed the whole player for ~40 frames without changing a pixel. A colour-identical scheme now keeps the
     existing instance (the cover URI still updates), and the fade has the same check as a backstop. Tests:
     `ColorSchemeColorsTest` (every one of the 48 colour roles is compared), `ThemeStateHolderSchemeReuseTest`.
   - A custom accent colour was rebuilt through material-color-utilities on every cold start with the splash
     waiting (~100 ms). The generated scheme is now saved once (96 ARGB ints + the accent + an algorithm version)
     and rebuilt with the plain `ColorScheme` constructor; it is regenerated when missing, stale or malformed, and is
     kept out of backups. **If `generateAccentColorSchemePair` is ever changed, bump
     `AccentColorSchemes.ALGORITHM_VERSION`.** material-color-utilities is also added to the baseline profile.

7. **Automatic Studio** `[and-lyrics-ai-bg-2, and-lyrics-ai-bg-3, and-playback-4]`
   - Automatic lyric/instrumental jobs and the background sweep now require **charging** (plus battery/storage not
     low); the sweep runs every 3 h instead of every 15 min (existing installs are updated in place).
   - No new automatic job starts while the app is on screen (leaving the app triggers a scan). A job that is
     already running is not cancelled by opening the app (unchanged).
   - The 6-hour / 8-job budget is saved to disk (it used to reset with every process, and every time the app was
     opened). A job cut at its 8-minute budget waits 6 h before being retried instead of 2 min.
   - The 30 s re-check loop only runs when it can start something (a feature on, nothing playing, app off screen);
     WorkManager is not queried at all in scans that cannot schedule (only to clean up an automatic job that may
     still run); WorkManager's job rows are only watched while a feature is on; a full library walk that found
     nothing to do is not repeated for 10 minutes unless the song, a setting, the app visibility or a job changed.
   Tests: `AutomaticStudioPolicyTest`, `AutomaticStudioRequestTest` (constraints, sweep interval, budget
   persistence, poll rules, idle-walk memo, timeout cool-down).

8. **Settings and artist page** `[and-settings-shared-4, and-library-2]`
   - Every Settings category built a `StatsViewModel` (loads the whole library, computes listening stats) just for
     the Developer "Regenerate stats" button. That button now calls the stats repository through `SettingsViewModel`.
     Test: `SettingsDoesNotBuildStatsViewModelTest`.
   - The artist page reloaded everything and searched YouTube Music again on every unrelated database write (the
     first visit's own image write, a heart tap, importing a "More from" track), blanking "More from". Now equal
     inputs are skipped, the image/palette work and the YouTube search run once per artist, "More from" survives
     updates, and the album sort runs off the main thread. Test: `ArtistDetailViewModelReloadTest`.

9. **Baseline profile** `[and-player-profile-gaps]`
   Queue sheet, lyrics sheet + toolbar + sync controls, cast sheet, seek bar, scroll bar and SmartImage were in no
   profile and ran interpreted after every install. Rules added to `app/src/main/baseline-prof.txt` (and the
   misleading lyrics comment fixed). Test: `BaselineProfileCoverageTest`. The profile is still not regenerated on a
   device; do that when convenient (`:baselineprofile:generateBaselineProfile`).

10. **Saved queue in its own file** `[and-data-3, and-playback-2, and-settings-shared-3]`
    The playback queue (~1.3 MB of JSON for 5,000 songs) lived in the settings DataStore, so every settings edit
    rewrote it, every play/pause rewrote the whole settings file, and the launch read had to parse it. It now has
    its own `playback_queue` DataStore. An existing queue is **moved once** (written to the new file first, then
    removed from settings, so a crash in between leaves it in both places, never neither; if the move fails the
    queue is still read from settings and the move is retried). The same JSON is stored, so restore behaves the
    same. Decoding/encoding now happens off the main thread. Tests: `PlaybackQueueStoreTest` (settings file stays
    small, exact round trip, migration, crash-between-writes, reset/restore parity, single-store fallback).

## Behaviour you could notice (decide if you want them)

- **Automatic lyrics/instrumentals arrive later**: only while charging, only when the app is off screen, at most
  8 jobs per 6 h. This is the product trade-off the audit flagged (item 7).
- **Big queues**: for the first ~0.4 s after tapping a song in a list of more than 200, "next" has nothing to skip to
  yet (the queue sheet itself shows the full queue immediately, as before).
- **Backups** no longer contain the saved playback queue (it was inside the exported settings; it is device-local
  state that points at files on that phone). Android auto-backup rules exclude the new file too.
- "More from <artist>" no longer drops a track you just imported from it; it refreshes on the next visit.

## Skipped / not done, and why

- `and-lyrics-ai-bg-3`: the second half (run the DSP on a background-priority thread and cap automatic inference at
  2 threads). The heavy loops hop between dispatchers inside the TFLite/ORT workers, so a priority set on one thread
  would not reliably cover them; the "never start while the app is on screen" half is done and removes the reason it
  janked the UI.
- `and-data-3` defensive step "make each holder's `onCleared` scope-aware" (from `and-settings-shared-1`): the
  second ViewModel is gone; `ExternalPlayerActivity` still has its own `PlayerViewModel` whose teardown clears the
  shared holders. Separate, touches ~18 holders.
- `and-home-search-2` optional part (move the field to `TextFieldState`): the root screen still recomposes per
  keystroke; the expensive part (every result row) no longer does.
- `and-playback-4`: observing only `AUTO_STUDIO_WORK_TAG` instead of `PIXELPLAY_JOB_TAG`. The shared tag is how a
  manually started lyric/stem job cancels an automatic one, so it is kept (but only watched while a feature is on).
- `and-playback-1` shortening the extras key prefix: the 45-character prefix is kept (only the six unused keys
  were removed); changing it is not needed for the win.

## How to confirm on the phone (plain language)

- Tap a song in a very long list (Songs, or a big playlist): the player opens smoothly, the notification does not
  flicker, no stutter while the list scrolls.
- Open a playlist and tap "Add songs", and open "New playlist" from Library: both open instantly.
- Switch between Library tabs quickly, then Albums: no placeholder flash, no hitch in the tab change.
- Open Home after a restart: the mixes appear noticeably sooner; coming back to Home does no second reshuffle.
- Type in Search: typing and the keyboard animation are smooth; tap a result and the queue name and queue are the
  same as before ("Search: <your text>").
- Skip tracks inside one album with the full player open: the colours do not pulse; colours still change when the
  album changes. With a custom accent colour set: cold start reaches the app slightly faster.
- Leave the phone idle on battery for an hour: it should not get warm; automatic lyrics/instrumentals show progress
  only once it is on a charger.
- Settings: open Library, Appearance, Playback... categories: they slide in without a hitch; Developer >
  Regenerate stats still shows its toast.
- Artist page: open an artist; "More from" appears once and stays when you tap a heart.
- First launch after updating: your saved queue is still restored (Now playing shows the same song, position).
- `PerformanceMetrics` now records a `queue_attach` timing (visible in the exported performance report).

## What remains from the audit (not started)

Critical/high findings not done in this pass, from the audit journal:
`and-images-theme-1` (critical, L: per-image artwork keys), `and-data-1` (a heart tap re-runs every whole-library
query), `and-data-4` (importing one catalog track rewrites the Spotify mirror), `and-data-5` (artist-image prefetch
write storm), `and-library-1` (library paging sorts the table per page), `and-lyrics-ai-bg-1/4/5` (play-history
JSON rewrite, resident 378 MB model, silent second ExoPlayer), `and-playback-3` (listening cache downloads streamed
songs twice), `and-player-queue-warm-on-expand`, `and-player-scheme-fade`, plus the medium/low ones. Everything
else in the journal is untouched.
