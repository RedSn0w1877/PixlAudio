# 2026-10-08: lyrics page on Android (Translate · Sing, screen always on, glass)

Branch `port-lyrics-page` (from `main` @ 5aa975e). Port of the 2026-10-07 iOS lyrics-page item, built from
`handoff/2026-10-07-plans/lyrics-page.json` and `DECISIONS.md` › Lyrics page. The iOS version is
`PixlAudio-iOS` branch `s16-lyrics-page` (`docs/handoff/2026-10-07-lyrics-page.md`). Not merged into `main`.

## What changed (what you see)

- **Translate · Sing replace Synced · Static** in the lyrics toolbar (`LyricsFloatingToolbar.kt`).
  - **Translate**: a tap shows or hides the lyrics' translations. When the lyrics have none, it translates the
    synced lines **on the phone** into the **phone's** language (not the app's), then shows them. The first time a
    language is used its model downloads (about 30 MB, mobile data allowed, with a "Downloading … for translation"
    notice). Lines already in your language are left alone (mixed K-pop / English songs).
  - **Hold Translate**: a menu with **Translate via AI** (the old cloud path, which saves into the lyrics) and
    **Show romanization** (when the lyrics have it).
  - Plain-only lyrics: Translate looks dimmed; a tap says why; holding it still offers Translate via AI.
  - **Sing**: vocals off / on through the song's studio instrumental (TAIS MDX-Net). With no render yet, a tap starts
    it: the segment reads "Removing vocals 48%" with the progress filling it, and the song switches to the
    instrumental by itself when it lands (once, and only if that song is still playing on this phone). A new song
    always starts with vocals. Sing is greyed out while Cast or a Spotify Connect speaker plays. The old floating
    "Instrumental" pill is gone. A tap while quiet automatic work renders the song restarts it as your own job, which
    playback can't cancel.
  - Translate and Sing count as touching the screen (immersive mode doesn't hide the controls right after) and tick
    a haptic only on a tap.
- **Synced vs plain is automatic.** The ⋯ sheet's Controls group has **Show as plain text** (synced songs only; it
  resets on the next song). The plain view now shows on-device translations too.
- **Screen always on** while the lyrics are open, playing or paused. The "Keep screen on" switch, its preference,
  the ON_STOP / screen-off receivers that switched it off are gone. It stops when the player collapses (the full
  player stays composed underneath the library) and when the lyrics close. The sync editor keeps its own window
  flag, unchanged.
- **Old backups**: `keep_screen_on_lyrics` is a retired key. Restore skips it and the restore preview lists
  "'Keep screen on (lyrics)' is no longer a setting and will be skipped…" (a warning, never a blocker). New
  backups leave it out, and a stored value is deleted once at launch.
- **Liquid Glass mode only** (Material 3 keeps its look apart from the new toolbar):
  - The chrome forces the **dark** glass palette (white text) over the always-dark art, also in a light-themed app.
  - Panels use the full player's top-pill tint (`tintSubtle`, White@0.08) instead of Black@0.22 / dark plastic;
    Black@0.35 over bright art; the Material container under Increase contrast.
  - Play/pause is the full player's **MediaOrb** (78 dp, lit with the album accent while playing). The seek bar is
    in its own light capsule (not interactive glass, so the thumb stays under the finger). The toolbar is **one glass
    bar**: Back / More are subtle circles, the segments subtle capsules, the active one flooded with the accent
    exactly like the player's selected LiquidChip; every button swells on press. The sync-offset row is its own
    capsule, the "Sync it yourself" chip a real glass capsule, and immersive mode's show-controls disc a small lit
    orb.
  - Lenses over the 30 fps artwork: header, orb, seek bar, toolbar = **4** (was 2), +1 while the sync chip or the
    sync-offset row shows.
  - The ⋯ sheet is a **floating, half-height glass sheet** (inset 8 dp, all corners 32, drag up for the rest). Rows
    stay soft fills in the glass palette's text colour; the alignment picker is the liquid **LiquidSegmented** and
    shuffle / repeat / heart are the full player's **LiquidChips**. It is its own window, so it refracts the app's
    album-coloured ambient, not the moving lyrics art (owner decision (a)).
  - Lines still fade out before the bars; the karaoke renderer, its engine and constants are untouched.

## Code map

- `data/lyrics/translate/OnDeviceLyricsTranslator.kt`: interface + `MlKitLyricsTranslator` (ML Kit language ID and
  translator; clients closed after each call). Bound in `di/AppModule.kt`.
- `presentation/lyrics/model/LyricsTranslationApplier.kt` (pure): `withTranslations`, `untranslatedLines`,
  `resolveLyricsDisplay`, `plainLinesFor`.
- `presentation/viewmodel/LyricsTranslationStateHolder.kt` (@Singleton): runs the translation, drops results after
  a song change, keeps a per-session cache (30 songs) so reloaded lyrics get their translations back, never writes
  to the song or the lyrics cache.
- `presentation/viewmodel/LyricsSingStateHolder.kt` (@Singleton) + `data/worker/InstrumentalRenderJobs.kt`
  (WorkManager adapter): owns `studioInstrumentalActive` / `Available` (moved out of `PlayerViewModel`), the Sing UI,
  the pending tap and the one-shot switch. `PlayerViewModel` only forwards (`toggleSing`, `translateLyricsOnDevice`,
  `singUi`, `lyricsTranslationState`) and still sends the MusicService command.
- `LyricsSheet.kt`: screen-awake effect, display resolution, the glass cluster, palette, tints.
- `LyricsMoreBottomSheet.kt`: plain-text switch, grouped row shapes (`groupRowCorners`), glass pieces.
- `GlassAdaptive.kt`: `AdaptiveModalBottomSheet(floating = …)` (glass only). **The glass-expansion port may want
  the same flag for the queue / song sheets: reuse this one.**
- `ToggleSegmentButton.kt`: optional long press, progress fill, state description, dimmed; its colour and corner
  animations are now read in the draw / layer phase (same look at all 19 call sites).
- `UserPreferencesRepository.RETIRED_PREFERENCE_KEYS`, `removeRetiredPreferences()`; `ModuleSchemaValidator`
  `RETIRED_PREF` warning.
- New dependency: `com.google.mlkit:translate` 17.0.3, `com.google.mlkit:language-id` 17.0.6
  (`THIRD_PARTY_NOTICES.md` updated).
- Strings: new English strings in `values/strings_player.xml` beside the other lyrics strings;
  `lyrics_mode_synced`, `lyrics_mode_static`, `lyrics_controls_keep_screen_on` removed from all 11 files and the
  orphan `lyrics_more_keep_screen_on` from `values-ar`.

## What CI proved

`android-ci.yml` **run 37751152434 on commit 87b990e is green** (the later commits on this branch touch only
`handoff/`, which CI skips):

- `:app:compileDebugKotlin` (it compiled on the first push too, run 37750272437).
- `:app:testDebugUnitTest`: all 849 tests pass, including the new ones:
  `LyricsTranslationApplierTest` (11), `LyricsTranslationStateHolderTest` (8), `SingUiReducerTest` (7),
  `LyricsSingStateHolderTest` (8), `LyricsMoreSheetShapesTest` (2), and additions to `UserPreferencesRepositoryTest`
  (old backup restores without the retired key; exports skip it; launch cleanup deletes it),
  `ModuleSchemaValidatorTest` (an old global-settings backup is valid with one `RETIRED_PREF` warning),
  `LyricsSheetLogicTest` (the forced dark glass palette) and `PlayerViewModelTest` (the new holders).
  The first run failed only the two holder test classes: `advanceUntilIdle()` never runs `backgroundScope` work on
  its own, so the tests now step with `runCurrent()` (commented in both files).
- `:wear:compileDebugKotlin`, and `:app:assembleDebug -Ppixelplay.enableAbiSplits=true`:
  `mergeDebugNativeLibs` packed ML Kit's `libtranslate_jni.so` and `liblanguage_id_l2c_jni.so` next to LiteRT and
  ONNX Runtime with no duplicate-library conflict. The arm64 debug APK artifact (`pixlaudio-arm64-debug-apk`) is
  **278.8 MB vs 269.6 MB** on main's last green run 37704901031: about **+8.7 MB**, almost all ML Kit.

## Differences from the iPhone version

- The on-device translator is Google ML Kit (the plan's suggested option A), not Apple's. Each language's model is
  about 30 MB and downloads the first time (mobile data allowed, with a notice). Translation is per line, so it has
  less context than Apple's.
- On-device translations stay in memory for the session (last 30 songs); they aren't saved. Translate via AI saves.
- "Show as plain text" also shows on-device translations (Android rebuilds the plain lines; iOS noted it doesn't).
- Glass toolbar: one glass bar with flat circles and segments on it (owner decision 2, suggested option (a):
  4 lenses over the moving art), where iOS makes each button its own glass in one container. Option (b), every button
  its own lens (8–9 lenses), needs a smoothness and battery check on the Pixel first.
- The sync-offset row is one glass capsule (iOS: five capsules that materialise).
- The ⋯ sheet is its own window, so its glass bends the app's album-coloured ambient, not the moving lyrics art
  (owner decision 3, suggested option (a)). Material 3 mode keeps the full-height Material sheet.
- The long-press menu is a Material dropdown in both modes (Android has no glass menu yet).
- Tapping Sing while quiet automatic work renders the song restarts that render as the person's own job (iOS adopts
  the running job). Automatic work only runs while playback is idle, so this is rare.

## Where the plan was off (written against 90d32ca; main @ 5aa975e had the same lines)

- Its file:line references held on main. Corrections:
  - `lyrics_controls_keep_screen_on` is in 11 files, not 12. `values-ar` only has the orphan
    `lyrics_more_keep_screen_on`, which is in `strings_components.xml`.
  - The "Disable immersive (once)" row only reacted on its switch. Its whole row is the target now, like the other
    switch rows.
- The translator interface changed shape so the holder can show a separate "downloading" phase and stay
  testable:
  - batch language ID;
  - `supportedLanguage` (it rejects romanized `*-Latn`);
  - `downloadModels`.
- The holder writes `show_lyrics_translation` through the Hilt `DataStore<Preferences>`. It's the same "settings"
  file the plan's `context.dataStore` points to.
- Plain-only lyrics: the plan said "disable Translate"; it's dimmed but tappable instead, as in the iOS build. A tap
  explains, and holding it still offers Translate via AI.
- The plan's `ToggleSegmentButton` change was done as asked. The lyrics toolbar's glass look got its own segment.
- Untouched orphans: `presentation_batch_g_lyrics_mode_synced` / `_static` in
  `values-ar/strings_presentation_batch_g.xml`. They have no `values/` original and aren't part of this item.

## Adversarial review (2026-10-08)

A second agent reviewed `git diff origin/main...HEAD` against the plan, `DECISIONS.md` and the Android rules
(glass mode only, Material 3 unchanged, performance, state holders, no Room / OkHttp / Spotify-token changes here).
No blocker. Fixed in commit `4e48817`; `android-ci.yml` **run 37766458090 on it is green** (compile, every unit test
including the new ones below, Wear compile, arm64 debug APK):

- **Major: on-device translations could be saved into your lyrics.** The holder put them on the lyrics the whole
  page sees, so ⋯ → Save lyrics (synced) wrote the ML Kit lines into the song's `.lrc` as dual-timestamp
  translations, and the sync editor's draft carried them into a saved sync. Translate via AI also saw them and
  answered "already translated" for the rest of the session. All three now start from
  `LyricsTranslationStateHolder.withoutOnDeviceTranslations()` (`Lyrics.withoutTranslationsFrom`), which takes off
  exactly the phone's translations and keeps the lyrics' own.
- **Sing could answer a new tap with an old render.** WorkManager re-reports a song's finished jobs whenever its
  table changes (e.g. the tap's own `cancelAllWorkByTag`), so a render that failed yesterday answered today's tap
  with its stale error and the real render then never switched; an old render whose file was deleted switched the
  player to the missing file. Jobs that finished before the tap are now marked answered, and the tap clears
  `available` (nothing is on disk) so the new render's progress shows.
- Quiet automatic work that is only queued (waiting for idle playback and a charged battery) no longer shows
  "Removing vocals…"; it shows once it really runs.
- The glass alignment picker prints its labels under the icons on one line; it now uses short Left / Center /
  Right (the long "Align lyrics center" was cut off).
- New tests: `LyricsTranslationApplierTest` (the inverse keeps the lyrics' own translation),
  `LyricsTranslationStateHolderTest` (saving paths get the lyrics without the phone's translations),
  `LyricsSingStateHolderTest` (an old failure and an old render with a missing file don't answer a new tap),
  `SingUiReducerTest` (queued automatic work).

Checked and left as is: the screen-awake effect (view flag, gated on the expanded player), the forced dark palette
covers only the chrome over the art (dialogs and the ⋯ sheet follow the app), no `layerBackdrop` on an ancestor of
a reader (the art is captured as a sibling; the ⋯ sheet's chips read the window backdrop beside its panel),
animated values read in draw / layer, the floating sheet's navigation-bar padding (consumed once).

Not fixed (pre-existing, same as before this branch): if the activity is recreated while the instrumental plays
(a theme switch), Sing shows vocals on until the next song, because the old ViewModel state reset the same way.

## Not verified (nothing ran on a phone)

- Any of the UI: the glass look, the half-height sheet, readability over bright art, the dropdown menu position.
- ML Kit at runtime: model download, translation quality (per line, no cross-line context), language ID on lyrics.
- The Sing render, the automatic switch, the Connect / Cast disabling.
- GPU cost of the 4–5 lenses over the 30 fps artwork.

## For Hoa's Pixel 10 Pro

- [ ] Set the screen timeout to 15 s. Lyrics open, playing and paused: the screen stays on. Collapse the player:
      it sleeps normally. Open the sync editor from the lyrics: it stays on.
- [ ] Translate on a foreign-language synced song: a "Downloading … for translation" notice the first time, then the
      translations appear and the button lights up. Tap again hides them; again shows them.
- [ ] A mixed song (e.g. K-pop with English lines): the English lines get no translation.
- [ ] Hold Translate: Translate via AI works; Show romanization appears on a Japanese / Korean song and flips.
- [ ] A plain-only song: Translate is dimmed and a tap explains; holding it still offers Translate via AI.
- [ ] Sing on a downloaded song with no render: "Removing vocals …%" fills the segment, then the vocals go away by
      themselves. Tap brings them back, tap again removes them (no skip). Next song: vocals are back.
- [ ] Sing is greyed out while a Spotify Connect speaker (Echo) or Cast plays.
- [ ] ⋯ → Show as plain text: plain lyrics; the next song is synced again. Show translations / romanization /
      Disable immersive flip from anywhere on their row.
- [ ] Material 3 mode: everything above works, the page looks as before apart from the toolbar's two new segments.
- [ ] Glass mode, dark and light app theme: white text on the lyrics chrome; the play/pause orb is lit while playing;
      the active segment is flooded with the album colour; presses swell; nothing looks like dark plastic.
- [ ] Glass mode ⋯: opens as a floating half-height glass sheet, drag up for the rest; the alignment lens and the
      shuffle / repeat / heart chips behave like the full player's; the heart flips at once.
- [ ] Bright album art (white covers): the chrome stays readable.
- [ ] Smoothness / battery in glass mode with the lyrics open for a few minutes (4–5 lenses redraw at 30 fps).
- [ ] Restore an old backup that has "Keep screen on": the preview lists it as skipped; the restore succeeds.
- [ ] Translate a song on the phone, then ⋯ → Save lyrics → synced: open the saved `.lrc` (or replay the song after
      restarting the app): no machine translation lines in it. Hold Translate → Translate via AI still runs.
- [ ] Glass mode ⋯: the alignment picker reads Left / Center / Right in full.

## Next step

Review and merge `port-lyrics-page` into `main` (agents don't merge), then run the checklist above. Expect merge
conflicts with the sync-editor port (LyricsSheet / LyricsMoreBottomSheet), the glass-expansion port
(`AdaptiveModalBottomSheet`), the player-controls port (`GlassPlayerToggleRow`, `PlayerViewModel`) and any other
branch that adds to `PlayerViewModel`'s constructor or `PlayerViewModelTest`. If the glass More sheet still doesn't
read as glass, the true fix is an in-window sheet drawn inside LyricsSheet over the lyrics art (plan risk 2, an
M-size follow-up).
