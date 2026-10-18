# Android features picked from the spoti.pw checklist (2026-10-18)

Four features, written from scratch from the one-line descriptions (the other mod's licence forbids copying
its code; none of it was opened). Material 3 Expressive only. Nothing here was run on a phone: the only
compiler is the GitHub Actions `android-ci.yml`. See "Not verifiable without the phone" at the end.

All new options live in **Settings > Playback** (the category the app calls Playback; it is the "Player"
settings): *Blocked artists*, *Gestures*, *Music haptics*.

## 1. Find in playlist

- On a playlist page: **pull the list down past the top**, or tap the new **search icon** in the top bar.
  A rounded Material 3 search field (with Cancel) slides in and takes the keyboard.
- Filters live as you type: title, artist (every credited artist) and album; case and accent insensitive
  ("beyonce" finds "Beyoncé"); every word must match, in any order; original order is kept.
- "No matches for ..." when nothing fits. Cancel, the system Back button, or scrolling the list up with an
  empty box hides it. Search key on the keyboard just closes the keyboard.
- Tapping a result plays inside the **filtered** list (that list becomes the queue). Drag-to-reorder is
  switched off while a search is active.
- Performance: the lower-cased / accent-free text of every song is built once per list on a background
  thread; each keystroke (debounced 80 ms) is a substring test per song on a background thread; the list
  uses stable keys; nothing is allocated per row while composing.
- TalkBack: the field has a content description; the search icon has one too.
- Not done: **Liked Songs and album pages** do not share the playlist list (they have their own screens with
  collapsing headers), so they were left alone to avoid fighting their scroll logic.
- Code: `presentation/components/FindInList.kt`, `data/text/SongSearchIndex.kt` (+ test),
  wired in `PlaylistDetailScreen.kt`.

## 2. Blocked artists

- **Block** from: the artist page (the pencil/edit menu now also has *Block artist* / *Unblock artist*), a
  song's options sheet (one *Block <artist>* row per credited artist), or the quick-actions sheet.
  **Manage** in Settings > Playback > Blocked artists: the row shows the count; the screen has a search box,
  *Unblock* per artist and *Unblock all* (asks first).
- What "blocked" does:
  - Never plays on its own: the playback service has the **single enforcement point**
    (`MusicService.enforceBlockedArtists`, called when any item becomes current or when play is pressed). It
    skips a blocked song in the direction the queue was moving (also with shuffle, repeat-all, a saved queue,
    Android Auto, Wear, the lock screen, Spotify Connect), and pauses if nothing playable is left (loop
    guard so a fully blocked queue cannot spin).
  - Queues the app builds from a list drop blocked songs first (`PlaybackDispatchStateHolder.internalPlaySongs`).
    "Play this playlist" therefore starts at the first allowed song.
  - Left out of Daily mix, Your mix, AI candidates (`DailyMixManager.personalizedPicks`), Home recommendations
    (library, discoveries and new releases), Spotify catalog and YouTube Music search results, and the
    "most played" list in the Spotify browser.
  - In playlists, the queue and song lists the song stays **visible but dimmed with a Blocked badge**. Tapping
    one asks *Play anyway?*; "Play anyway" lets that one song through the guard until another song becomes
    current.
- Matching (`data/blocking/BlockedArtists.kt`): artist id first, then case/accent-insensitive name. Any
  credited artist counts. Player items carry the credited artist ids in their metadata extras
  (`MediaItemBuilder.EXTRA_ARTIST_IDS`); items without them (an old saved queue) fall back to the names.
- Storage choice: the **settings DataStore** (string set `blocked_artists`, entries `<id>|<name>`), not Room.
  Room is at version 1 with no migrations and a bump would be a database-wide risk for a tiny list; a
  DataStore key needs no migration and is automatically part of the settings backup/restore
  (`exportPreferencesForBackup` exports every key). Documented here as the chosen trade-off.
- Home shelves are rebuilt on their normal schedule, so a freshly blocked artist can still show on Home until
  the next refresh. Skipping and mixes pick it up immediately.
- Diagnostics: `DiagnosticsLog.event("BlockedArtists", ...)` for block / unblock / skips / nothing-left-to-play
  (no names or ids are logged).
- Tests: `BlockedArtistsTest`, `FoldedTextTest`.

## 3. Gestures (Settings > Playback > Gestures, one switch each)

| Gesture | Default | Notes |
|---|---|---|
| Full player cover: swipe left/right | on | The cover is the existing carousel (it already had the swipe and a haptic). The switch turns the swipe off. |
| Full player cover: double-tap = like / unlike | on | Heart burst animation + haptic. Adds ~0.3 s delay to the cover's tap-to-open-album (the double-tap wait). |
| Full player cover: long-press = quick actions | on | Opens the song options sheet: share, add to playlist, go to artist, block artist. |
| Mini player: swipe left/right = next/previous | **off** | It replaces swipe-to-dismiss (that gesture already used the sideways swipe), so it is off by default. |
| Mini player: swipe up opens the player | on | This already worked (sheet drag); the switch exists but the behaviour is built in. |
| Mini player: long-press = quick actions | on | Same sheet as above. |
| Song rows: swipe right = Play next, left = Add to queue (if not already queued) | **off** | Rows live in horizontally swiped tabs; when on, a sideways swipe that starts on a row is taken by the row. |

- Intent detection (`presentation/utils/GestureModifiers.kt`): a swipe only starts when the sideways travel
  clearly dominates (|dx| > 2 x |dy| after touch slop), is abandoned as soon as it is vertical, and only
  then consumes the pointer. Taps, long-presses, list scrolling and the sheet's vertical drag are untouched.
- Skips use `PlayerViewModel.nextSong()/previousSong()`, which go through `PlayerTransport` (the helper fixed
  earlier), including the "tapped while the queue attaches" gate.
- TalkBack: the cover and the mini player expose custom actions (Next song, Previous song, Like or unlike,
  More actions); rows expose Play next / Add to queue when row swipe is on.

## 4. Music haptics (Settings > Playback > Music haptics)

- **Master switch (off by default)**, **Strength 20-200 %** (100 default), **Follows**: *Everything* (taps on
  kick and snare + a soft rumble under the bass), *Beat* (taps only), *Bass* (kick taps + rumble).
  Greyed out with "Not supported on this device" when the phone has no vibration motor.
- Engine (extra row, only shown if the system generator exists):
  - **App analysis (default).** A `TeeAudioProcessor` in the player's audio chain passes the audio through
    unchanged and hands the decoded PCM to `BeatDetector`: two one-pole filters (low band under about 140 Hz,
    high band over about 1.5 kHz), 10 ms hops, band energy compared with its own running average (a
    band-wise spectral-flux test). Kick = low-band onset that is mostly low band; snare = high-band onset
    that is mostly high band; the smoothed low band drives the rumble. Zero allocation on the audio thread.
    Events become `VibrationEffect` primitives (CLICK for kicks, TICK for snares, LOW_TICK for the rumble;
    one-shot fallback if the motor lacks primitives) on a background thread, held back ~110 ms to line up
    with the speaker, **max about 25 events per second**, attributed to USAGE_MEDIA so Android's own
    media-vibration switch applies.
  - **System (experimental).** Android's `HapticGenerator` on the player's audio session (API 31+). It cannot
    honour Strength or Follows, and with ExoPlayer it may produce nothing (Media3 does not unmute haptic
    channels), which is why it is opt-in and not the default. If the phone supports it and you feel nothing,
    leave it on App analysis.
- When it runs: only while enabled, the app is in the foreground, a song is actually playing locally, Battery
  Saver off, Do Not Disturb off, no phone call, the device is not hot (thermal severe), and the system "touch
  vibration" switch is on. Cast / Spotify Connect (audio on another device) turns it off. It stops on pause,
  seek and audio-route change, and is released with the service (the HapticGenerator too).
- Compressed audio offload hides the PCM from the app, so while the switch is on the player keeps PCM output
  (offload is turned off; a small battery cost the owner accepts by switching it on).
- Diagnostics: start / stop (with the reason, e.g. "battery saver") / failures through `DiagnosticsLog.event`.
- Tests: `BeatDetectorTest` (silence, 60 Hz bursts = kicks only, noise bursts = snares only, steady tone does
  not retrigger, spacing, PCM buffers not consumed), `HapticPlannerTest` (Follows modes, 25/s cap, strength).

## Plain phone checks

1. Settings > Playback: the three new sections exist. Gestures defaults as in the table.
2. Open a big playlist, pull down: the field appears and the keyboard opens. Type a few letters (with and
   without accents); the list narrows instantly. Tap a song: it plays and Next stays inside the filtered
   list. Cancel brings the full list back.
3. Block an artist from a song's options. Play a playlist containing them: they are skipped. Open the
   playlist: the song is dimmed with "Blocked"; tap it: Play anyway? Settings > Blocked artists: count,
   search, Unblock, Unblock all. Check Home / Daily mix after a refresh.
4. Full player: swipe the cover, double-tap (heart), long-press (sheet). Mini player: long-press (sheet);
   turn on the mini swipe and check next/previous and that swipe-to-dismiss is gone.
5. Music haptics: switch on, play a song with a clear kick drum, hold the phone. Try Strength 50 / 150 and the
   three Follows modes. Pause: it must stop at once. Turn on Battery Saver: it must stop. Cast: no buzz.
   Try the System engine only if curious.

## Not verifiable without the phone

- **How the haptics feel**: threshold constants in `BeatDetector` (ratios, band edges, minimum spacing) and the
  110 ms latency compensation are reasoned values, not tuned on a Pixel 10 Pro. If kicks are missed or
  everything buzzes, those constants are the knobs. Whether `HapticGenerator` makes anything with ExoPlayer.
- Gesture feel on the real device: the 56 dp / 72 dp swipe thresholds, the row swipe taking over the tab pager,
  and the double-tap delay on the cover.
- That the options sheet (long-press) opens above the full player on every screen state.
- The R8 release build (checked in CI only).
