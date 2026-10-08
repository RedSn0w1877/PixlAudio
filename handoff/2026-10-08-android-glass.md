# 2026-10-08: more Liquid Glass on Android (branch `port-glass`)

This is the Android port of the 2026-10-07 iOS glass expansion. It follows `handoff/2026-10-07-plans/glass-expansion.json` and DECISIONS › More Liquid Glass (options A + B). The iOS side is `docs/handoff/2026-10-07-glass-expansion.md` in PixlAudio-iOS (behaviour and constants). NexHome is the reference for every glass value.

**Everything here is Liquid Glass mode only.** Material 3 Expressive mode draws exactly what it drew before. The lyrics page and the full player's top bar were not touched (port-lyrics-page and port-player-connect own them).

## What you get (glass mode)

### The queue
- **A see-through glass sheet at about 92 % height** instead of a full-height one on an opaque scrim.
  - The player stays visible above it, slightly scaled back as before, under the palette's see-through sheet scrim (the one glass bottom sheets use).
  - The sheet itself is one heavy NexHome glass panel with 32 dp top corners: the same container as the glass song-options sheet, drawn in the player's window.
  - **A tap above the sheet closes the queue.** It never reaches the player's top-bar buttons underneath.
  - On a short or landscape screen the sheet never reaches within 8 dp of the status bar.
- **Toolbar: separate glass circles, no backing capsule.** Shuffle, repeat and the sleep timer are each a 56 dp glass circle. They light up with the album accent when on (NexHome's chip recipe) and read as "selected" to TalkBack.
- **The ⋯ circle morphs into the menu.**
  - Tapping ⋯ stretches the circle sideways into the "Save as playlist" pill. Its lens deepens and the accent floods in.
  - The toolbar circles fade out under it, and "Locate current song" and "Clear queue" materialise above it (fade, a small rise, their lens blooming in; Clear is tinted with the error colour).
  - Behind it the sheet dims with NexHome's Dim, not Material's 0.55 scrim and opaque gradient. Tap the dim or press Back to close the menu; Back closes the menu before the queue.
  - It runs on a dedicated spring (open 0.72 / 360 with a little overshoot, close 1 / 520 with none).
- **The undo bar** ("<song> removed · Undo") is a glass capsule. Undo is an accent-lit fill, so the text stays the palette's colour over glass.
- **The source badge** in the header is a subtle fill on the header's glass, and the empty-queue text uses the palette's colour.

### Floating bars: the primary button is its own glass pill
None of these bars has a backing bar any more; a glass button on a glass bar is glass-on-glass.
- **Save as playlist** (from the queue's menu): a summary capsule (count + name) and a lit **Save** pill, both 56 dp. Save is disabled while no song is ticked.
- **Edit song:** a neutral **Cancel** pill and a lit **Save** pill, 56 dp. They still hide while the keyboard is up.
- **Library tab order:** a 56 dp glass **Reset** circle and a lit **Done** pill.
- **Genre Quick Fill:** on the songs step, the Select all · Clear pair in one glass capsule, an empty gap, and a lit **Next** pill (disabled until a song is ticked). On the genre step, a status capsule and a lit **Quick Fill** pill (disabled until a genre is picked).
- **Playlist editor** (Create / Next and Save): lit glass pills in place of the extended FABs. Create / Next is disabled until the playlist has a name (the Material FAB only dims there).

### The rest of the audit (the plan's recommended scope)
- **Full player, the song-info row (not the top bar):** the lyrics button, the landscape lyrics | queue chips and the Taizo button are 48 dp glass circles instead of solid fills. The buffering indicator sits in a clear glass circle.
- **The format pill under the seek bar** ("FLAC · 44.1 kHz" etc.) is a small clear glass capsule (NexHome LiquidChip's lens).
- **Genre page options** and **Daily Mix's AI button** are the 64 dp accent-lit glass header orb that album and artist pages already use. Daily Mix's button is round in glass mode: the 8-point star has no lens-compatible shape.

### Already the iOS look on Android (no change, on purpose)
- **Listening Stats header:** it already is Settings' glass bar in glass mode. That is the floating glass capsule over a band that fills with the refracted background as the header collapses, the refresh orb and the glass range tabs (`StatsScreen.kt` › `glassAwareHeaderFill` + `CollapsibleCommonTopBar` → `GlassCollapsingTopBar`, the same path `SettingsScreen` takes).
- **Song options, AI Daily Mix and Taizo chat sheets:** they already are partial-height glass sheets (`AdaptiveModalBottomSheet`'s heavy glass container), with the page or player visible above them. The iOS problem (tall sheets turning opaque) doesn't exist on Android.

## Code map

| Piece | Where |
|---|---|
| Glass pill button (LiquidButton's look on a GlassPanel, so no lens rebuild per keystroke); `glassPillTint` | `ui/glass/GlassPillButton.kt` |
| Shape morph (layout-phase only) + `lerpMorphRect`, `morphFadeIn/Out` | `ui/glass/GlassMorph.kt` |
| Morph springs | `ui/glass/motion/LiquidMotion.kt` (`MorphOpenSpring`, `MorphCloseSpring`) |
| Lit circles, lens bloom on circles | `ui/glass/GlassTopBar.kt` › `GlassCircleAction(lit, enterProgress)`, `drawGlassLit` |
| 92 % sheet sizing, see-through scrim with tap-to-close | `presentation/components/UnifiedPlayerOverlaysLayer.kt` (`resolveQueueSheetHeightPx`, `glassQueueSheetHeight`) |
| Player no longer hidden under the open queue | `UnifiedPlayerSheetV2.kt` (`glassQueueCoversPlayerProvider` removed) |
| Queue sheet glass, undo bar, badge, Save as playlist bar | `presentation/components/QueueBottomSheet.kt` |
| Glass toolbar + ⋯ morph menu | `presentation/components/QueueGlassControls.kt` (new) |
| Edit song / tab order / Quick Fill / playlist editor pills | `EditSongSheet.kt`, `ReorderTabsSheet.kt`, `screens/QuickFillScreen.kt`, `screens/CreatePlaylistScreen.kt` |
| Song-row chips, format pill | `presentation/components/player/FullPlayerContent.kt` (`SongMetadataDisplaySection`, `EfficientTimeLabels`) |
| Header orbs | `screens/GenreDetailScreen.kt`, `screens/DailyMixScreen.kt` |
| Tests | `test/.../ui/glass/GlassMorphMathTest.kt`, `GlassPillButtonTest.kt`, `test/.../presentation/components/QueueSheetGeometryTest.kt` |

Performance notes (all by construction; nothing was profiled):
- Every animated value (morph, lit glow, scrim, lens bloom) is read in a layout or draw lambda. The controls recompose only when the menu is asked to open or close and when it appears or disappears, never per frame.
- The sheet's glass is a sibling *behind* the list, so scrolling never re-records its lens. It re-renders only while the sheet moves (open, drag, predictive back) and is skipped while the queue is hidden.
- The toolbar circles fade with a layer alpha only; their lenses are not re-rendered by the morph. The morph itself re-renders one resizing lens plus the two blooming pills for about 300 ms.
- All pills are GlassPanels, which build their glass chain once. Pills on typing screens get a stable click (`rememberUpdatedState`), so a keystroke doesn't even recompose them.
- No `layerBackdrop` was added anywhere; everything samples the root ambient (or a sheet's or dialog's window-aligned copy of it).

## Where this differs from the plan, and why
- **Material 3 mode is unchanged.** The plan also made the Material queue 92 % tall and moved its ⋯ menu to M3's `FloatingActionButtonMenu`. That is out of scope here: the Android rule is that Liquid Glass changes only the glass theme mode, and DECISIONS says nothing about Android's Material mode. If Hoa wants the Material versions too, the plan's Material notes still apply (the FAB-menu API was only checked against material3 alpha22; main is on alpha29 now).
- **No `headerTopInset` parameter.** The queue header pads 10 dp in glass mode (the sheet starts below the status bar) and keeps the status-bar padding in Material mode. There is a single caller.
- **The Save pill is centred on the toolbar row** (where the ⋯ circle is), not 36 dp + nav bar up. The circle then stretches sideways into it instead of jumping.
- **The ⋯ anchor is read from the slot's placement** (relative coordinates, never root ones, so it holds still while the sheet slides), with a computed fallback for the very first frame.
- **The toolbar circles don't fade their lens** with the morph (the plan scaled their refraction by `1 − morph`). Alpha alone looks the same and saves three lens renders per frame.
- **Undo is an accent-lit fill, not accent text.** The kit's rule is that text over glass is the palette's primary colour, never the accent.
- **All floating-bar pills are 56 dp**, as on iOS. That includes the tab-order Reset circle (the plan had 48 dp).
- **The format pill uses LiquidChip's 6 / 12 lens**, not the default light-panel 12 / 24, which is too deep for a ~20 dp capsule.
- **Plan citations checked:** the file:line references held, within a few lines. Two facts moved since the plan was written:
  - material3 is now 1.5.0-alpha29 (Dependabot);
  - `kyantBackdrop` is 2.0.1 (the `GlassDraw.kt` comment still says the pin is 2.0.0).

## Where Android differs from iOS
- **"See-through" means the album-art background, not the player.** Android glass refracts only the baked ambient layer (FlattenNestedGlass). So the 92 % sheet shows the refracted background through it and the player only above it. iOS shows the player's art and controls through the glass. Sampling the player itself would be glass-on-glass and a full-screen lens re-rendered on every player redraw, so it's not done.
- **The queue sheet is full width with top corners**, like Android's other glass sheets. iOS 26 insets partial-detent sheets from the edges (the lyrics page's More sheet on `port-lyrics-page` is inset too). Changing this is a small follow-up if Hoa prefers the inset look.
- **The morph is hand-built** (`glassMorphBounds`) instead of a shared `glassEffectID`.

## What CI proved
- `android-ci.yml` on `port-glass`: `:app:compileDebugKotlin`, `:app:testDebugUnitTest` (including the three new test classes), `:wear:compileDebugKotlin` and `:app:assembleDebug` (arm64 APK artifact `pixlaudio-arm64-debug-apk`).
  - Run 37772185912: green (the kit, the queue and the floating bars).
  - Run 37773115814: green (adds the song-row chips, the format pill and the screen orbs).
  - Run 37773927821: green on `5ede6bf` (adds the ⋯ anchor fallback and its test).
  - Run 37778375028: green on `f26ce3f`, the review fixes below (compile, unit tests, Wear compile, arm64 APK). **Install the APK from this run.**
  - The new test classes compiled and ran inside `:app:testDebugUnitTest`; CI doesn't print per-class results on success.
- Nothing ran on a phone, and nothing was profiled. Every visual and feel claim above is by construction.

## Review fixes (adversarial review, 2026-10-08, commit `f26ce3f`)
A second pass read the whole diff the way the compiler and a strict reviewer would. It found one class of real bug and fixed it with a few small cleanups.
- **Taps fell through glass that replaced a Material surface.** M3's `Surface` swallows every touch on it (an empty `pointerInput`). A `GlassPanel` without a click has no touch handler, so a tap went to whatever was drawn under it:
  - a tap on the queue's undo bar that missed Undo played the song row underneath;
  - a tap on Quick Fill's disabled Next (or its Select all · Clear capsule, or the genre step's status capsule) ticked the song or genre under it;
  - a tap on the playlist editor's disabled Create pill hit the form under it.
  These surfaces now take the touch like the Material ones. The fix for disabled pills sits in `GlassPillButton`, so every disabled pill behaves this way.
- **Locate and Clear ignored "closing".** While the menu closes, the two pills fade out but stay laid out for about 250 ms, and a quick second tap there still ran Clear or Locate. They now act only while the menu is open.
- The Taizo button's label is now a string resource (`player_cd_ask_taizo`) instead of a hard-coded "Ask Taizo" in both modes.
- `QueueSheetGeometryTest` had a test that only checked its own arithmetic. It now sweeps screen sizes and status-bar heights through `resolveQueueSheetHeightPx`.

Left as they are, on purpose:
- **Material 3 mode is still unchanged.** DECISIONS says to take a plan's recommended option for every question Hoa didn't answer, and this plan recommended a 92 % queue and M3's FAB menu for Material mode too. The Android rule (Material stays as it is unless a decision says so) wins here, and the FAB-menu API was only checked against an older alpha. **Hoa: say if you want the Material queue changed too.**
- The undo bar's Undo and Quick Fill's Select all / Clear are Material buttons inside glass, so they ripple instead of swelling. That is the kit's documented rule for Material buttons in glass (`GlassPressIndication.kt`).
- During the ~300 ms morph, the layout allocates a few small `Rect`s per frame and re-measures the pill label's width. That costs far less than the one lens the morph re-renders per frame.
- TalkBack can still reach the queue rows behind the open ⋯ menu, as in Material mode.

## Checklist for Hoa's Pixel 10 Pro (glass mode, unless it says otherwise)
- [ ] Open the queue (button and swipe up from the player): it slides up to about 92 % with the player visible and dimmed above it.
- [ ] Drag the queue down from the header, the toolbar, the ⋯ circle and the top of the list; fling it closed; use predictive back. All of it should follow the finger smoothly.
- [ ] Tap the strip above the queue: the queue closes, and the player's top-bar buttons never fire.
- [ ] The sheet reads as glass over bright and dark album art, in light and dark theme. If the header looks too dark on the sheet, say so: it can drop to a lighter panel.
- [ ] Shuffle, repeat and the timer light up when on and go dark when off; the press swells. TalkBack says "selected" on the active ones.
- [ ] ⋯: the circle stretches into "Save as playlist" with Locate and Clear appearing above it. Then close it by tapping the dim and, separately, with Back; it flows back into the circle both times. Try each action (Locate scrolls to the playing song, Clear asks first, Save opens Save as playlist).
- [ ] Back with the menu open closes only the menu; a second Back closes the queue.
- [ ] Swipe a song away in the queue: the glass undo bar appears, and Undo works. Tap the bar's song title (not Undo): nothing plays.
- [ ] Save as playlist: the summary capsule and the Save pill; untick every song and Save disables, tick one and it enables.
- [ ] Edit song (from a song's ⋯ › Edit): Cancel and Save pills; typing stays smooth; both pills hide while the keyboard is up.
- [ ] Library › reorder tabs: the Reset circle and the Done pill.
- [ ] A genre with untagged songs › Quick Fill: Select all · Clear capsule and Next (disabled until a song is ticked); the genre step's status capsule and Quick Fill pill. Scroll a song under the bar and tap the disabled Next and the capsule: no song gets ticked.
- [ ] Create a playlist and edit one: the Next / Create / Save pills (Create disabled until there's a name; tapping it then does nothing).
- [ ] Full player: the lyrics and Taizo glass circles (and the lyrics | queue pair in landscape), the buffering circle while a song loads, the format pill under the seek bar.
- [ ] Genre page options orb; Daily Mix's AI orb (round now instead of a star in glass mode: OK?).
- [ ] Unchanged: the Stats header, song options, AI Daily Mix and Taizo sheets.
- [ ] Turn "Disable blur all over" on, and separately switch to Material 3: everything above looks exactly like before this branch (full-height queue, tonal toolbar, Material menu, Material buttons).
- [ ] Optional, only if you want numbers: `adb shell dumpsys gfxinfo com.theveloper.pixelplay.debug framestats` while opening the queue, dragging it and opening the ⋯ menu.

## Next step
Hoa runs the checklist on the APK from the branch's last green CI run. Tuning knobs, if something looks off:
- the sheet height (`QUEUE_SHEET_HEIGHT_FRACTION`);
- the header panel's weight on the sheet (`QueueHeaderSection`: `heavy = true` → `false` with `palette.tint`);
- the morph springs (`LiquidMotion.MorphOpenSpring` / `MorphCloseSpring`).

Then the integrator merges `port-glass` after `port-player-connect` and `port-lyrics-page`. The overlaps are small: `UnifiedPlayerSheetV2.kt` (deleted lines near the ones player-connect edits), `UnifiedPlayerSheetLayers.kt` (one KDoc) and `FullPlayerContent.kt` (the song-info row and the time labels; their edits are elsewhere in the file). `port-accent` changes `GlassPalette`; the pills and lit circles read `palette.accent`, so they pick up the chosen accent automatically.
