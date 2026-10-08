# 2026-10-08: player top bar, skip timing, Connect volume keys, sync-editor fixes, notification icon (Android)

Branch `port-player-connect` (worktree `PixlAudio-android-player-connect`), cut from `origin/main` at `5aa975e`.
It ports four items of the 2026-10-07 iOS batch. Plans: `handoff/2026-10-07-plans/player-controls.json`,
`connect-volume.json` and `sync-editor.json`. Owner decisions: `DECISIONS.md` › Player, › Spotify Connect volume
buttons, › Sync editor bug. Not merged into `main`. Nothing ran on a phone, and nothing was built locally: CI is the
only build.

## What changed (what Hoa sees)

### 1. Full player: top bar and skip buttons (`8f4d2aa`, `d742e1d`)
- **"Now Playing" and the cloud icon are gone.** The output pill on the right names where the music plays, like iOS:
  - phone speaker: icon only;
  - Bluetooth, USB-C and wired headphones, HDMI: the device's own name, or "Bluetooth audio" / "USB audio" /
    "Wired audio" / "Digital output" when Android gives none;
  - Cast: the route name and the dot;
  - Spotify Connect: the speaker's own icon (speaker / TV / car…), its name and the dot;
  - while Cast or Connect starts: "Connecting…" and a spinner.
- The name follows the **real media route** (`AudioManager.getAudioDevicesForAttributes`, API 33+). Moving output to
  the speaker in Android's Output Switcher while earbuds stay connected shows the phone again. Before, the pill only
  guessed from "a Bluetooth device is connected" and never saw wired or USB outputs. API 30–32 fall back to the
  connected sink in the order Bluetooth > USB > wired > HDMI.
- A long name uses the width up to the collapse button, then ellipsizes. Material and Liquid Glass both.
- TalkBack reads "Playing on <device>" or "Playing on this phone".
- **Previous / next settle after 220 ms, like play/pause.** Before, they held the squeeze for 600 ms. The play/pause
  icon keeps its own 600 ms lock after a skip, so a streamed song that buffers still doesn't flash "Play". Glass mode's
  orbs already behaved like this.
- Faster: the pill collects its own state (`FullPlayerTopActions`), and the route / Bluetooth name left
  `FullPlayerSlice`, so an output change no longer recomposes the 3000-line full player.
- `ConnectivityStateHolder` now unregisters its audio-device callback in `onCleared()`. Before, each ViewModel
  recreation added another one.
- The heart: Android has no stale-heart bug (Room Flow → StateFlow → provider), and the lock-screen Like is already
  wired. One lag is fixed (review, below): unliking a track that only came in from "More on Spotify" now empties the
  heart at once instead of after the library clean-up.

### 2. Volume buttons on a Spotify Connect speaker (`7128280`)
- **Each press moves the speaker exactly 5 %.** The session's remote volume is now a 0–20 scale, so Android's volume
  panel steps 1 unit = 5 %. Before, it was 0–100 with 5-point presses: the panel showed 51, 52, 53… and then jumped.
- **With PixlAudio open, the keys also work while the speaker is paused.** MainActivity hands its window's volume keys
  to the media session (`Activity.setMediaController`) while an eligible speaker plays. Android itself routes keys to a
  remote session only while it is playing.
- **Phones and tablets are left alone** (usually this phone's own Spotify app): the keys keep changing this phone's
  volume. Same for a device that doesn't support volume.
- **Volume has its own request lane** (`SpotifyConnectVolumeLane`, same policy as iOS):
  - the first change goes at once, then at most one request every 300 ms with the latest value;
  - a 429 waits quietly, with one "Spotify is busy" toast only for a wait of 3 s or more;
  - a volume error never ends the session, and volume no longer waits behind a window send's song searches.
- **Polls no longer snap the volume back** for 3 s after a change (1.5 s more after the device accepts it).
- **A speaker that says it supports volume but refuses volume commands** (`VOLUME_CONTROL_DISALLOW`) stays without
  volume control for the rest of the session, with one toast. Polls and device lists keep reporting
  `supports_volume`; before, control came back on every poll and every press toasted again. This is the iOS
  reviewer's finding, ported.
- **Equalizer › Volume** shows and sets the speaker's volume while Connect plays ("Kitchen Echo volume"). For a device
  without volume control it says "This device sets its own volume".

### 3. Lyric sync editor (`51dad23`)
The iOS bug ("loading, then the editor vanishes") **does not exist on Android**: the editor is a state-driven overlay
inside the full player, not a presented cover. These real Android defects were verified in the code and fixed:
- **Spotify Connect wasn't guarded.** The editor opened, paused the speaker and stamped every tap against the paused
  phone. Now, while Connect plays or connects, it shows "Syncing only works on this phone. Switch playback back to this
  phone first." with Close:
  - this applies at open (chip, More sheet, empty state, Edit song) and mid-session;
  - it never pauses or seeks the speaker;
  - Edit song › Fix timing no longer sends a different song to the speaker first.
  - Cast is unchanged: its entries stay hidden, and its message stays.
- **Edit song › Fix timing from the player's queue** no longer starts the editor hidden under the queue (with the
  music jumping): the queue slides away.
- **A load that hangs or throws** now shows the error screen with Close after 8 s. Before, it was a spinner with no way
  out, or an uncaught exception (a crash).
- **"Fix timing" on lyrics without timed words** (a line-timed document) starts normally. Before, it spun forever:
  `goToPreview` returned without choosing a screen.
- **Toasts instead of silence** for these cases:
  - nothing is playing;
  - the song never started (8 s);
  - playback stopped mid-sync (the editor closes; taps stay a draft);
  - the player collapsed with taps kept as a draft;
  - "Remove my timing" failed. It used to close as if it had worked; now it stays.

### 4. Notification icon (`0de93c0`)
`drawable/monochrome_player.xml` (status bar, media, lyric-sync and watch-transfer notifications) is the new Glyph
logo: 24 dp, glyph 22 dp tall, centred. `tools/make-launcher-icon.py` writes it from the logo's geometry. A new
`--notification-only` flag rewrites just that file without touching the launcher WebPs:
```
python tools/make-launcher-icon.py --generator "<PixlAudio-iOS>/ci/make-icon.py" --notification-only
```

## What CI proved
`android-ci.yml` compiles `:app`, runs `:app:testDebugUnitTest`, compiles `:wear` and builds the arm64 debug APK.
- `37748043330` (`d742e1d`): green.
- `37749267418` (`7128280`): green.
- `37750602216` (`0de93c0`): green, covering everything. `37750274468` (`51dad23`) was cancelled because the next push
  superseded it.

New unit tests (JUnit 5), all in the green runs:
- `PlayerOutputResolverTest` (8): which output the pill shows, and the precedence.
- `AudioOutputCategoryTest` (6):
  - device type → category;
  - the speaker never named; names equal to the model dropped;
  - the Bluetooth fallback name;
  - the API 30–32 route pick;
  - the Connect chip slice.
- `SpotifyConnectVolumeTest` (6):
  - 0–20 scale with `toSteps(p + 5) == toSteps(p) + 1` for every p;
  - clamps;
  - the phone/tablet exclusion;
  - the remote DeviceInfo;
  - the lane's leading edge, 300 ms, 429 and failure paths.
- `SpotifyConnectReducerTest` (+2): the poll hold, and the refusal staying sticky through polls and device lists.
- `LyricsSyncEditorStateHolderTest` (12):
  - Connect at open, while connecting, and mid-session, never pausing or seeking;
  - Cast mid-session;
  - load watchdog and load exception;
  - stays open;
  - player unloaded; nothing playing; requestOpen timeout;
  - remove-timing failure.

The Media3 and material3 facts the plans relied on were re-checked in bytecode against today's versions (Media3
1.11.1, material3 1.5.0-alpha29; the plans were written against 1.10.1 / alpha22). Media3 rebuilds the platform
VolumeProvider only on a DeviceInfo change or a player swap. `MediaSession.getPlatformToken()` is public. TopAppBar
still measures its actions at the full bar width, hence the 72 dp leading reserve.

## Review fixes (adversarial review, same branch)
- **A press after a long 429 could step from the speaker's old volume.** The poll hold is 3 s, but a Retry-After can
  be longer. When the gate opened, a poll could land before the waiting `PUT`, put the old value back on screen and in
  the system panel, and the next press then stepped from it (70 % set, 40 % polled, press → 45 %). Polls and device
  lists now also keep the local volume while the lane still has a value waiting or on its way
  (`SpotifyConnectReducer.apply(volumeSending = …)`). Test: `a volume still waiting to go out outlasts the hold`.
- **The volume lane had no crash guard.** Volume requests left `enqueue`, and with it its catch-all: an exception from
  the auth layer (the encrypted prefs) would escape the handler-less scope and crash the app. It is now a failed
  request like any other. A stray `CancellationException` no longer strands a newer value in the lane either.
- **Unliking an Explore-only Spotify track** writes the favourite first, then runs `removeFromExploredCatalog` (the
  `player-controls.json` step that was skipped). The clean-up never touches the favorites table, so the end state is
  the same; the heart just doesn't wait seconds for the mirror rebuild. Test in `PlayerViewModelTest`.

## Not verified
Nothing here ran on a phone. The system volume panel's look and the Output Switcher callbacks in particular can only
be checked on the device.

## Hoa's Pixel 10 Pro checklist
Top bar (Material and Liquid Glass):
- [ ] Phone speaker: icon only; no "Now Playing" and no cloud, also on a Spotify song.
- [ ] Pixel Buds / any Bluetooth: Bluetooth icon plus the device's name.
- [ ] USB-C headphones or a DAC: headphones icon plus a name (or "USB audio").
- [ ] Output Switcher → this phone while the earbuds stay connected: back to icon only (open and close the player if
      it lags).
- [ ] Chromecast: "Connecting…" plus spinner, then the route name and the dot.
- [ ] Spotify Connect to the Echo: "Connecting…", then the speaker icon, "Kitchen Echo…" and the dot.
- [ ] A long name ellipsizes and never covers the collapse button; the collapse button and the queue button still work.
- [ ] TalkBack on the pill: "Playing on …" / "Playing on this phone".
- [ ] Previous / next squeeze and settle as quickly as play/pause. Skipping between Spotify songs doesn't flash the
      Play icon.

Volume keys during Spotify Connect:
- [ ] Echo playing, PixlAudio open: each press moves the Echo 5 %, and the volume panel's remote row follows without
      jumping back.
- [ ] The same with PixlAudio in the background and on the lock screen while the Echo plays.
- [ ] Echo paused, PixlAudio open: the keys still move the Echo.
- [ ] Hold a key: the volume ramps smoothly, with no "Spotify is busy" spam.
- [ ] Connect to this phone's own Spotify app (Smartphone): the keys change the phone's volume.
- [ ] Stop playing on the Echo: the keys control the phone's volume again.
- [ ] Equalizer › Volume shows "<Echo> volume" and follows the keys; dragging it moves the Echo.
- [ ] If a speaker refuses volume: one toast, then the keys go back to the phone's volume (no toast per press).

Lyric sync:
- [ ] Lyrics ⋯ → Sync the words yourself, the chip, and the empty state: the editor opens and stays; Close returns to
      lyrics.
- [ ] Player queue → row ⋮ → song info → Edit song → Fix timing: the queue slides away and the editor shows.
- [ ] While the Echo plays: every entry shows "Syncing only works on this phone…" with Close; the Echo keeps playing
      (no pause, no seek). Edit song → Fix timing on another song doesn't change what the Echo plays.
- [ ] Collapse the player mid-sync after a few taps: the toast "Syncing closed. Your taps are kept as a draft."

Notification:
- [ ] The status-bar and media-notification icon is the new Glyph (rounded play triangle with the note).

## Divergences from iOS (for the iOS repo's `docs/parity.md`)
- **Volume feedback.** Android shows the system volume panel's remote row, not a PixlAudio glass pop-up. The keys also
  work in the background and on the lock screen while the speaker plays: that's Android's own remote-session routing,
  which already existed. "Foreground only" was an iOS API limit. Owner decision 1 in `connect-volume.json`
  recommended this.
- **Phone or tablet Connect targets.** The keys keep changing this phone's volume there. On iOS the buttons never take
  over.
- **Output pill.** Android also names USB and HDMI outputs, and shows Cast instead of AirPlay.
- **Playback stopping mid-sync.** Android closes with a toast (the draft is kept) instead of iOS's in-editor
  "Playback stopped." error. The sheet can hide when the player unloads, which would strand an error screen.
- **Cast in the sync editor.** Its entries stay hidden while casting, unchanged. Only Connect shows the message.

## Left for later
- Optional: delete the now-unused `player_now_playing` / `player_cd_cloud_stream` strings from `values` and the 10
  locales. The baseline-profile pattern still lists "Now Playing", which is harmless: it also matches
  "Collapse player".
- Watch volume controls still change the phone's volume during Connect (`connect-volume.json` owner decision 3,
  suggested follow-up).
- New strings are English only (`values/strings.xml`).

## Next step
Review and merge `port-player-connect` (Hoa or the integration session; agents don't merge into `main`). Then install
the CI APK (`pixlaudio-arm64-debug-apk` of run `37750602216`) on the Pixel 10 Pro and go through the checklist above.
