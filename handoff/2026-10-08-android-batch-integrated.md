# 2026-10-08: the Android port of the iOS 2026-10-07 batch is on main

Six reviewed, CI-green branches were merged into `main` through `integrate-oct8` (each with
`git merge --no-ff`, then a fast-forward of `main`). **Cloud Studio (`port-cloud-studio`) is not in
this build:** another agent is still building it, and it gets its own merge later.

APK: `C:/Users/Hoa/Downloads/PixlAudio-android-oct8.apk` (arm64 debug, 267 MB). It's the artifact
`pixlaudio-arm64-debug-apk` of the green `main` run `37806900196`, at `a74952e`. Nothing in it has run
on a phone yet.

## What merged, in order

| # | Branch | Tip | What it is | Its own note |
|---|---|---|---|---|
| 1 | `port-accent` | `3b7219e` | Accent colour picker (presets + Custom), app-wide re-theme, Player Theme › Accent Color, widgets and watch follow it | `2026-10-08-android-accent.md` |
| 2 | `port-streaming` | `4c1d0dc` | Faster stream starts: hedged resolves, next-song preload, URL cache, 1 s start buffer, `StreamStart` lines in Test playback | `2026-10-08-android-streaming.md` |
| 3 | `port-player-connect` | `2104315` | Full-player output pill, skip timing, volume keys on a Spotify Connect speaker, sync-editor fixes, new notification icon | `2026-10-08-android-player-connect.md` |
| 4 | `port-lyrics-page` | `320c063` | Lyrics page Translate (ML Kit, on device) and Sing, screen stays on, glass lyrics chrome | `2026-10-08-android-lyrics-page.md` |
| 5 | `port-glass` | `d7d1e1c` | Glass queue sheet, floating glass pills, the rest of the glass audit | `2026-10-08-android-glass.md` |
| 6 | `port-local-ai` | `c7a417e` | On-device AI by default (Gemini Nano via AICore, optional downloaded Gemma 4 E2B via LiteRT-LM) | `2026-10-08-android-local-ai.md` |

The order was smallest-overlap first, and `port-glass` after player-connect and lyrics-page, as its
note asked. No branch changed the Room schema, so the database stays at version 1 and `app/schemas`
is untouched.

## Conflicts and how they were resolved

Git flagged only two conflicts. Everything else (MusicService, MainActivity, PlayerViewModel and its test,
UserPreferencesRepository, SettingsCategoryScreen, SettingsViewModel, FullPlayerContent,
UnifiedPlayerSheetV2/Layers, DailyMixScreen, strings.xml, the Gradle files) auto-merged. After the
merges, strings.xml has no duplicate names and UserPreferencesRepository no duplicate keys.

1. **`LyricsSyncEditorStateHolder` constructor** (player-connect × lyrics-page). Player-connect added
   `spotifyConnect: SpotifyConnectController` (the "Syncing only works on this phone" gate). Lyrics-page
   added `lyricsTranslation: LyricsTranslationStateHolder` (so the editor's draft never keeps on-device
   translations). Both are kept. `LyricsSyncEditorStateHolderTest` (from player-connect) didn't pass
   the new parameter, so it now gets a relaxed mock whose `withoutOnDeviceTranslations` returns the
   lyrics unchanged. The existing assertions still see the real lyrics.
2. **`THIRD_PARTY_NOTICES.md`** (lyrics-page × local-ai). Both added sections at the same spot. Both
   are kept: ML Kit Translation / Language ID, then LiteRT-LM, Gemma 4 E2B and the ML Kit GenAI
   Prompt API.
3. **A conflict git didn't flag** (accent × local-ai). The first CI run of the full merge failed in
   `compileDebugUnitTestKotlin`. Local-ai had added `aiPreferencesRepository` to
   `GlobalSettingsModuleHandler`, because a restore re-runs the AI provider migration. Accent's new
   `ThemePreferencesRepositoryTest` built that handler without it. Fixed in `a74952e`: the test now
   passes `AiPreferencesRepository(store)`.

CI: `integrate-oct8` run `37805529274` was green. `main` at `a74952e` was green too: Android CI run
`37806900196`, which built the APK, plus the Phone Debug/Release and Wear workflows.

## Phone checklist for Hoa (Pixel 10 Pro)

Install `PixlAudio-android-oct8.apk`. Unless a line says otherwise, check in **Material 3** and in
**Liquid Glass** mode, in light and dark theme.

### Accent colour (Settings › Appearance › Global Theme)
- [ ] **Accent Color** sits under App Theme. With an accent already picked, the ring opens on it,
      with no jump from Dynamic.
- [ ] Tap each preset. The app recolours at once (buttons, switches, sliders, navigation bar), with
      no stutter. **Dynamic** brings back the wallpaper colours.
- [ ] Graphite looks grey, text stays readable, and the dark-mode pastels look OK.
- [ ] **Custom:**
  - [ ] Dragging the panel or the hue bar moves the thumbs, and the sheet stays still.
  - [ ] A typed hex works, and a bad one shows the hint.
  - [ ] #FF453A + **Use color** puts the check on Red, not on Custom.
  - [ ] **Use color** applies and **Cancel** doesn't. The Custom swatch then shows your colour with
        a check.
- [ ] Player Theme reads **Album Art / Accent Color**:
  - [ ] Album Art keeps the album colours in the mini player, full player, lyrics and queue.
  - [ ] Accent Color recolours the player in the accent.
- [ ] Glass mode:
  - [ ] The tab bar, toggles, sliders, chips and settings icon discs take the accent.
  - [ ] The background still comes from the artwork.
  - [ ] On Dynamic, the glass accent follows the album again.
- [ ] Cold start with an accent set: no Material You flash, and the splash isn't noticeably longer.
- [ ] With Player Theme › Accent Color, the home-screen widget recolours within about a second of an
      accent change. The Pixel Watch player, and a song sent to the watch, use the accent.
- [ ] "Open with PixlAudio" from a file manager: the overlay uses the accent.

### Full player: top bar, buttons, notification
- [ ] Output pill:
  - [ ] Phone speaker: icon only (also on a Spotify song).
  - [ ] Bluetooth: icon + device name.
  - [ ] USB-C / DAC: headphones icon + name, or "USB audio".
  - [ ] Output Switcher back to this phone while the earbuds stay connected: back to icon only.
- [ ] Cast to a Chromecast: "Connecting…" with a spinner, then the route name and the dot.
- [ ] Spotify Connect to the Echo: "Connecting…", then the speaker icon, the Echo's name and the dot.
- [ ] A long name ellipsizes and never covers the collapse button. The collapse and queue buttons
      still work. TalkBack reads "Playing on …" / "Playing on this phone".
- [ ] The heart flips the moment you tap it: in the full player, in the lyrics ⋯ sheet, and on the
      glass ⋯ chips.
- [ ] Previous / next squeeze and settle as quickly as play/pause. Skipping between Spotify songs
      doesn't flash the Play icon.
- [ ] Glass full player:
  - [ ] the lyrics and Taizo circles (the lyrics | queue pair in landscape);
  - [ ] the buffering circle while a song loads;
  - [ ] the format pill under the seek bar.
- [ ] The status-bar and media-notification icon is the new Glyph (rounded play triangle with a
      note).

### Volume keys on a Spotify Connect speaker
- [ ] With the Echo playing, each press moves the Echo 5 %. The volume panel's remote row follows
      without jumping back: with PixlAudio open, in the background and on the lock screen.
- [ ] Echo paused, PixlAudio open: the keys still move the Echo.
- [ ] Holding a key ramps smoothly, with no "Spotify is busy" spam.
- [ ] Connect to this phone's own Spotify app: the keys change the phone's volume. Stop playing on
      the Echo: the keys control the phone again.
- [ ] Equalizer › Volume shows "<Echo> volume", follows the keys, and dragging it moves the Echo.
- [ ] If a speaker refuses volume: one toast, then the keys go back to the phone (not a toast per
      press).

### Streaming speed (Spotify songs that aren't downloaded)
After each step, open Spotify dashboard › **Test playback** and copy the "Recent stream starts"
lines (or `adb logcat -s StreamStart`).
- [ ] Wi-Fi, a never-played song: it starts noticeably sooner, and the line shows a small
      `flush gap`.
- [ ] Wi-Fi, play 20 s, then skip: the next song starts almost at once (`preload: yes`,
      `url cached`).
- [ ] A song that ends on its own advances seamlessly (`AUTO … preload: yes` or `AUTO 0 ms`).
- [ ] Crossfade on, skip and auto-advance a few times: no glitch, no double audio
      (`CROSSFADE 0 ms` lines).
- [ ] Mobile data: tap and skip again, and note `KiB fetched` on preloaded SKIP/AUTO lines. The
      budget is about 512 KB to 1 MiB per prepared song; ignore it on CROSSFADE lines.
- [ ] Skip a song 2 s in while the next one is slow to start: the skipped song doesn't show up as
      cached later.
- [ ] Data Saver on: play a minute and skip once. The line says `preload: no`, and the auto-cache
      size in storage settings doesn't grow.
- [ ] Paused for a minute: nothing is prepared, and resuming works.
- [ ] Spotify Connect to the Echo for a minute: no new stream-start lines on the phone.
- [ ] Seeking to 70 % of a non-downloaded song jumps quicker than before.
- [ ] A song played over 5 s on Wi-Fi shows up as cached later, and offline replay still works.
- [ ] Send any `rebuffered within 10 s` lines, plus five TAP and five SKIP lines from Wi-Fi and from
      5G (look at `n:` and the first tap after the app was closed).

### Lyrics page
- [ ] Screen timeout at 15 s:
  - [ ] the lyrics keep the screen on, playing and paused;
  - [ ] the sync editor opened from the lyrics keeps it on too;
  - [ ] collapsing the player lets the screen sleep normally.
- [ ] **Translate** on a foreign-language synced song:
  - [ ] the first time, a "Downloading … for translation" notice, then the translations, and the
        button lights up;
  - [ ] tapping again hides them and shows them;
  - [ ] in a mixed song (K-pop with English lines), the English lines get no translation.
- [ ] Hold Translate:
  - [ ] Translate via AI works (also with on-device AI; try a Vietnamese song and an English one);
  - [ ] Show romanization appears on a Japanese or Korean song and flips.
- [ ] Plain-only song: Translate is dimmed and a tap explains why; holding it still offers Translate
      via AI.
- [ ] **Sing** on a downloaded song with no render:
  - [ ] "Removing vocals …%" fills the segment, then the vocals go by themselves;
  - [ ] a tap brings them back and another removes them, with no skip;
  - [ ] the next song has its vocals again.
- [ ] Sing is greyed out while an Echo (Connect) or Cast plays.
- [ ] ⋯ menu:
  - [ ] Show as plain text works, and the next song is synced again;
  - [ ] Show translations, Show romanization and Disable immersive flip from anywhere on their row.
- [ ] Glass:
  - [ ] white text on the lyrics chrome, readable over white covers;
  - [ ] the play/pause orb is lit while playing, and the active segment fills with the album colour;
  - [ ] presses swell, and nothing looks like dark plastic.
- [ ] Glass ⋯:
  - [ ] a floating half-height glass sheet you can drag up for the rest;
  - [ ] the alignment picker reads Left / Center / Right in full;
  - [ ] the shuffle / repeat / heart chips behave like the full player's.
- [ ] A few minutes of lyrics open in glass mode: smooth, and no unusual battery drain.
- [ ] Translate a song, then ⋯ › Save lyrics › synced: the saved `.lrc` (or the song after an app
      restart) has no machine-translation lines.

### Lyric sync editor
- [ ] Lyrics ⋯ › Sync the words yourself, the chip and the empty state each open the editor, and it
      stays open. Close returns to the lyrics.
- [ ] Queue › a row's ⋮ › song info › Edit song › Fix timing: the queue slides away and the editor
      shows.
- [ ] While the Echo plays, every entry shows "Syncing only works on this phone…" with Close. The Echo
      keeps playing (no pause, no seek), also via Fix timing on another song.
- [ ] Collapse the player mid-sync after a few taps: toast "Syncing closed. Your taps are kept as a
      draft."

### Queue and other glass surfaces (glass mode)
- [ ] The queue opens with the button and with a swipe up, to about 92 % with the player dimmed above
      it.
- [ ] Dragging down from the header, the toolbar, the ⋯ circle or the top of the list follows the
      finger, and so do a fling and predictive back.
- [ ] Tapping the strip above the queue closes it, and the player's top-bar buttons don't fire.
- [ ] The sheet reads as glass over bright and dark art. Say so if the header looks too dark.
- [ ] Shuffle, repeat and the timer light up when on. TalkBack says "selected" on the active ones.
- [ ] ⋯ menu:
  - [ ] the circle stretches into "Save as playlist", with Locate and Clear above it;
  - [ ] it closes back into the circle on a tap on the dim and on Back;
  - [ ] Locate scrolls to the playing song, Clear asks first, Save opens Save as playlist;
  - [ ] Back with the menu open closes only the menu; a second Back closes the queue.
- [ ] Swipe a song away: the glass undo bar appears and Undo works. Tapping the bar's title plays
      nothing.
- [ ] Save as playlist: unticking every song disables Save, and ticking one enables it.
- [ ] Edit song: the Cancel / Save pills hide while the keyboard is up, and typing stays smooth.
- [ ] Library › reorder tabs: the Reset circle and the Done pill.
- [ ] Genre › Quick Fill:
  - [ ] the Select all · Clear capsule, and Next disabled until a song is ticked;
  - [ ] the status capsule and the Quick Fill pill;
  - [ ] taps on the disabled Next or the capsule never tick the song underneath.
- [ ] Create or edit a playlist: Next / Create / Save pills. Create stays disabled, and does
      nothing, until there's a name.
- [ ] The genre page options orb, and the round Daily Mix AI orb (OK instead of the star?).
- [ ] "Disable blur all over" on, and separately Material 3 mode: all of the above looks exactly as
      before.

### AI features (Settings › AI features)
- [ ] The Gemini Nano row says "In use", or tap **Get ready**, watch the bar, then "In use". Leaving
      Settings right after Get ready still lets it finish.
- [ ] "Use a cloud assistant" is off, unless you had a Gemini key (then it's on with Gemini).
      Advanced shows only Temperature.
- [ ] In airplane mode (on-device only):
  - [ ] Home: the greeting turns into an AI line, and expanding the card writes an insight.
  - [ ] Daily Mix sparkle "rainy day indie", 10-15 songs: a playlist, no "No Internet Connection".
  - [ ] Library › Create playlist › With AI: 24 songs, then 150 songs.
  - [ ] Daily Mix › refine with a prompt: the mix updates.
  - [ ] Taizo: "what are my top artists?", then a follow-up that needs the previous answer, then
        "play some chill songs" (the card at once, the intro a moment later). A quick double send
        waits for the first.
  - [ ] Switching apps mid-playlist shows "Stay in PixlAudio…", not a network error.
- [ ] Downloaded model (Wi-Fi, about 3 GB free):
  - [ ] "Use downloaded AI model" shows 2.6 GB, the licence link and the mobile-data checkbox.
  - [ ] Progress shows in the row and in the notification, and Cancel works.
  - [ ] Download again and leave the app: it keeps going.
  - [ ] It ends on "Downloaded · 2.6 GB", and the Gemini Nano row says the downloaded model answers
        instead.
  - [ ] Repeat a few airplane-mode checks. Note how long the first request takes (up to ~10 s), and
        that no "couldn't load" line appears.
  - [ ] Play music 10+ minutes afterwards: playback never stops.
  - [ ] Delete:
    - [ ] it asks first, the space comes back and the switch turns off;
    - [ ] AI works on Gemini Nano again;
    - [ ] switching it back on shows "Not downloaded · 2.6 GB" with **Download**.
- [ ] The new Settings rows look right in Material and Liquid Glass.

### Backups (one pass covers all three features)
- [ ] Make a backup, change the accent and "Use downloaded AI model", then restore. The app re-tints
      live to the backed-up accent, and the AI switch keeps its backed-up value.
- [ ] An older backup restores to the Dynamic accent.
- [ ] An old backup with "Keep screen on": the preview lists it as skipped, and the restore
      succeeds.

## Left open

- **Cloud Studio** (`port-cloud-studio`) is pending. It merges on its own once its agent finishes,
  and will conflict wherever it touches the same files as above (PlayerViewModel, settings, strings).
- Each branch's "Left for later" items are still open; see their notes. The main ones:
  - new strings are English only;
  - watch volume keys still move the phone's volume during Connect;
  - "Generate with AI" in PlaylistBottomSheet only works from Daily Mix (an older bug);
  - the Gemini Nano output cap needs checking on the phone.
- The integration branch `integrate-oct8` can be deleted once Hoa is happy with the build.
