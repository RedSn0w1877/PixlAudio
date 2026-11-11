# Android Spatial audio: UI (2026-11-11)

Role: **UI** (player button, sheet, Settings rows, strings). Branch `audio-ui`, cut from `origin/audio-hq` at
50cf187. Contract and map: `handoff/2026-11-10-plans/cloud-audio-spec.md` ("§" below),
`handoff/2026-11-10-plans/android-integration-map.md` ("map"). No contract file was changed.

CI: see the bottom of this note.

## What is there

### 1. The player entry (map 4.3)

`presentation/spatial/SpatialAudioPlayerButton.kt`, called as the third sibling after Lyrics and Ask Taizo in
`SongMetadataDisplaySection` (`FullPlayerContent.kt`, portrait and landscape).

- **Off**: a 48 dp circle in the row's `chipColor`/`chipContentColor`, the size of its siblings, with the icon (new
  `res/drawable/rounded_spatial_audio_24.xml`, Material Symbols Rounded `spatial_audio`) and "Off" under it in
  `labelSmall`. TalkBack: "Spatial audio, Off".
- **On**: the colours swap (Material's selected look), the corners tighten (24 → 16 dp) and the profile's name takes
  the place of "Off"; the width follows the name, capped at 104 dp ("Concert Hall" fits, a longer name ellipsizes
  rather than squeeze the song title). TalkBack: "Spatial audio, Studio".
- **Held back**: a selected profile with `audible.blockedBy` = `ROUTE` or `SING` keeps its name but loses the
  emphasis (TalkBack adds "Not playing right now."). `REMOTE` and `FORMAT` do not dim it: §6.5 has no line for them,
  so the button does not hint at something the sheet will not explain.
- It collects its own slice (`storedSettings` + `audible.blockedBy`), so a pick recomposes the button only. Colours
  and corners are read in draw/layer (`ColorProducer` tints for the icon and the text); width animates in layout.
- The first stored value lands without animation: the holder starts on the defaults for a frame or two after it is
  created, so the button waits on the new `SpatialAudioStateHolder.storedSettings` (null until the store is read)
  and only animates after that. Otherwise a stored profile would "grow in" from Off every time the app starts.

### 2. The sheet (§6.5)

`presentation/spatial/SpatialAudioSheet.kt`: `SpatialAudioSheet(onDismissRequest)` (an `AdaptiveModalBottomSheet`,
`skipPartiallyExpanded`), opened from the button with the album-art colours (wrapped in
`MaterialTheme(colorScheme = LocalMaterialTheme.current)`, like `FetchLyricsDialog`) and from Settings with the app's.

- Header "Spatial audio" / "For headphones.", then the status line card, then **Off and the six profiles** (name +
  the spec's one-liner, a grouped list; the selected row takes the tonal container, full corners and a check that
  scales in), then the quiet line "Spatial plays a little quieter so the peaks stay clean." (always).
- The status line is `holder.statusLine` mapped by `SpatialAudioUi.statusLineRes`: every row of the §6.5 table, word
  for word; `NONE` shows no card. A change crossfades (`AnimatedContent` + `SizeTransform`), polite live region.
- Picking a row calls `holder.selectProfile` at once and the sheet stays open, so profiles can be compared by ear.
  Rows are `selectable(role = RadioButton)` in a `selectableGroup`. Container colour, corners and the check are read
  in draw/layer only. It collects only while it is shown.

### 3. Settings (§6.5, map 4.1-4.2)

`presentation/spatial/SpatialAudioSettingsSection.kt`, called from `SettingsCategoryScreen.kt`:

- **Settings › Playback › Sound**, after Audio quality (`SpatialAudioSettingsRows(navController)`):
  - "Spatial audio": subtitle "<name> · <one-liner>"; opens the same sheet.
  - "Cloud renders": writes `spatial.cloud` only.
  - "Cloud processing is off" (only while "Cloud renders" is on and `cloudRendersOn` is false): says the master is
    off and opens `Screen.CloudProcessing` (map 4.2: the row must not look on and do nothing). The master switch is
    not written from here.
  - "Prepare ahead": `spatial.prepareAhead`; disabled with "Needs cloud renders." while "Cloud renders" is off.
  - "Songs ready: N · X GB on this phone" with a "Remove downloaded renders" button (disabled when there is nothing)
    and a confirm dialog ("Remove downloaded renders?").
- **Settings › Playback › Advanced** (Developer mode only, after Hi-Fi mode; `SpatialAudioDeveloperRows()`):
  - "Spatial audio route": Headphones only / Always.
  - "Spatial cloud limit per month": $5, 10, 15, 20, **30** (default), 50, 75, 100, 150, 200, No limit; a stored
    amount not in the list is shown in its place. Subtitle: "Spent this month: $X" (`stats.spentThisMonthMicroUsd`
    via `CloudCost.format`).
  - "Storage for Spatial renders": a slider over 14 stops, 256 MB … 64 GB (3 GB default is a stop).
  - "Spatial songs a day ahead": 1 … 100, caption "Sent today: N." (`stats.sentToday`).
  - "Spatial audio: this song": rendition (+ profile), held back by, status, the local render's profile, source codec,
    stem mode, loudness (integrated, source, headroom) and sample rate (`audible`, `currentStatus`, `currentRender`).
  - Sliders follow the finger locally and write when it lifts; nothing is written while dragging.
- Developer mode's file-info label in the full player (map 2.10, "decide with UI") now ends with
  "• Spatial Studio on phone" or "• Spatial Studio cloud render" while a spatial rendition is audible.

### 4. Strings

`res/values/strings_spatial_ui.xml`, English only (map 4.4: the newest files are English only; no existing strings
file was touched). Every name starts with `spatial_ui_` so ENGINE's and CLOUD's own files cannot clash with it. Two
existing strings are reused: `common_remove`, `common_cancel`. No changelog entry.

### 5. Until ENGINE and CLOUD land

- `audible` is `NOTHING`: the status line is `NONE` (no card), the button follows the stored pick only (never dimmed),
  the diagnostics say "Nothing is playing.", the file-info label adds nothing.
- The library is the no-op: "Songs ready: 0 · 0 GB on this phone" with the remove button disabled, $0.00 spent,
  0 sent today. `cloudRendersOn` is false, so **with "Cloud renders" on, the "Cloud processing is off" row shows even
  if the master switch is on**: in that build no cloud render can happen, but the row's reason is then wrong. It is
  right once CLOUD's `cloudRendersOn` is real.

### 6. Tests (JVM)

- `app/src/test/.../presentation/spatial/SpatialAudioUiTest.kt`: reads `strings_spatial_ui.xml` and `R.string` and
  checks every status line, the six names and one-liners, Off and the quiet line word for word against the spec;
  the sheet's order and single selection; the button for every block; the settings rows for the two switches and
  the stats; GB formatting; the storage stops, the cap choices and picks, the daily range; the diagnostics rows; the
  file-info mapping; no forbidden word in any UI string; every name in the `spatial_ui_` namespace.
- `SpatialAudioStateHolderTest`: `storedSettings` is null until the store is read, then follows it.
- `@Preview`s: the button (off, on, long name, held), the sheet (off, on device, headphones only), the Sound rows
  and the Developer rows.

## Divergences and choices the spec does not make (please confirm or overrule)

1. **The label sits under the icon** (icon over "Off" / the name, in `labelSmall`) rather than beside it: "Off"
   then fits the 48 dp circle of its siblings, and "Concert Hall" needs about 80 dp instead of about 125, which the
   song title would lose. If the owner wants the player calmer, dropping the word "Off" (icon only while off, as the
   output pill does on the phone speaker) is a one-line change in `SpatialAudioButtonContent`.
2. **Off is first in the list** and has its own one-liner, "The normal stereo sound." (§6.5 lists "the profile list
   … Off" without an order or a line for Off). iOS should match whichever the lead picks.
3. The header's "For headphones." is mine (§1: "It is for headphones").
4. The button dims only for `ROUTE` and `SING` (the blocks with a line). `REMOTE`/`FORMAT` show it as on.
5. The Settings profile row opens the player's sheet instead of a plain picker, so the one-liners and the status line
   are there too.
6. "Prepare ahead" is disabled while "Cloud renders" is off (it can only send cloud work). Its stored value is kept.
7. The monthly limit has no $0 choice (turning "Cloud renders" off does that); a stored 0 still shows as "$0".
8. "X GB" uses binary units (1 GB = 1024 MB), like `storageBytes`; anything above 0 bytes reads at least "0.1".
9. The developer diagnostics values are untranslated technical words, like the existing file-info label; their
   labels are string resources.
10. The holder gained one UI-only derived flow, `storedSettings` (no rule): see 1 above.

## What other roles or the lead must do

- **ENGINE**: nothing for the UI to work. When it is decided whether float output is off "while a profile is
  selected" or "while Spatial is active" (map 9.3), the Hi-Fi mode row (`SettingsCategoryScreen.kt`, UI's file) can
  say so in one line; tell UI which.
- **CLOUD**: `cloudRendersOn` must be the two switches together (map 1.2.2), or the "Cloud processing is off" row is
  wrong (see 5). `stats.spentThisMonthMicroUsd` / `sentToday` feed the Developer rows as they are.
- **Lead**: confirm the divergences above; compare the list order and the Off line with iOS; ask for the changelog
  entry when the beta is cut.

## Not done / uncertain

- Nothing was run on a device or in an emulator (none here); layout widths were reasoned from the code (24 dp side
  padding, 48 dp siblings). The landscape row now holds Lyrics|Queue, Ask Taizo and Spatial audio after the title.
- The loading placeholder of the song row (`MetadataPlaceholder`) has no shape for the third button; it shows for a
  moment while the player expands, as before.
