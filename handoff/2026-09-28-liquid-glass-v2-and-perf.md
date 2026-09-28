# Liquid Glass v2 + app-wide performance pass

Built in a cloud session **without access to NexHome** (it lives only on the desktop), so the
glass is a rebuild on the same library (Kyant `backdrop` 2.0.0), following the design decisions
from the earlier research phase: tiered fallbacks, album-lit ambient layer, light/dark palette,
no refraction under battery saver, sheet glass covering the whole sheet. It is **not** a verbatim
NexHome port. Compiles and packages (`:app:assembleDebug`); nothing has run on a device.

> This branch was cut from the desktop's `main` as handed off (3 commits, newest Sep 9). The
> Apple-Music lyrics view and tap-sync editor from the previous session were **not** in that
> checkout. Merge this onto the real working branch before testing, or those will look missing.

## Glass architecture (`ui/glass/`)

| File | Role |
| --- | --- |
| `GlassTheme.kt` | `GlassTier` (Refractive / Frosted / Solid), `GlassPalette` (light/dark + album accent wash), `GlassMaterial` size classes (Thin, Regular, Chrome, Thick), composition locals |
| `GlassEnvironment.kt` | `ProvideGlassEnvironment` (root: tier from API + live battery saver, palette, light, demand-driven page snapshot, shader warm-up), `ProvideInlineGlassSource` + `AmbientBackdrop` (what glass inside screens refracts) |
| `GlassModifier.kt` | `Modifier.glass()` — the single glass implementation; per-tier rendering; remembered effect lambdas; light spill under the finger |
| `GlassLight.kt` | One shared, lifecycle-aware gravity sensor → quantized rim-highlight angle, read in draw, preallocated `Highlight`s |
| `GlassSheet.kt` | `GlassModalBottomSheet` (drop-in for `ModalBottomSheet`; whole sheet is glass), snapshot-backed sheet backdrop |
| `PageBackdrop.kt` | Record-once page backdrop, `snapshotView` for other windows, `glassSource()` for local recordings |
| `GlassComponents.kt`, `LiquidNavBar.kt`, `LiquidGlassToggles.kt`, `LiquidGlassSliders.kt` | Components on top of the engine (same public names as v1) |

Tiers: **Refractive** = API 33+ (blur + lens + light-tracking rim). **Frosted** = API 31–32, or
any device in battery saver (blur + flat rim, no lens). **Solid** = API 30 (painted frost, no
sampling).

## Performance fixes (from a code audit, all read-verified)

- Position ticker at 250 ms and ambient player animation now only while the player is visible
  (the full player stays composed after collapsing, which pinned both on).
- Ambient effects: animation clocks read in draw (flowing/mesh styles recomposed every frame),
  cached brushes, reused arrays.
- Startup: Spotify repository and Hi-Fi `AudioTrack` probe off the main thread; Cast init deferred
  a loop turn; library-empty check is `COUNT(*)` instead of loading every song.
- DataStore `pref()` flows are `distinctUntilChanged` (every write re-ran ~95 collectors).
- Album rows can skip again (stable colour-scheme flow instances); colour extraction capped at 2
  parallel jobs.
- Detail screens collect a small player slice; only the current row gets `isPlaying = true`.
- Queue drag and predictive-back / swipe gestures no longer recompose the whole player sheet.
- Marquee edge brushes, play/pause shapes, player-card glass shapes cached.
- Glass: the old kit rebuilt each element's RenderEffect chain on every recomposition, and
  snapshotted the whole page 3×/sec forever. Both gone.

Bugs fixed along the way: glass settings sliders never saved (`onValueChangeFinished` dropped)
and ignored `steps`; disabled glass toggles could flip visually; sheets had a see-through strip
at the top; sheet glass likely never refracted (drew the main window's layer from the sheet's).

Not done (bigger refactors, need a device to verify): nav-bar hide animation still recomposes the
root and sheet each frame (`sheetCollapsedTargetY` as Float); `ScreenWrapper` transition layer;
queue snapshot sharing the settings DataStore file.

## Phone test checklist (Pixel 10 Pro)

1. Glass on, dark + light theme: nav bar, mini player, full player controls, a few sheets
   (sort, song info, timer), settings toggles/sliders. Rims should shift as you tilt the phone.
2. Home screen glass buttons: should show soft album-coloured light, not flat grey.
3. Sheets: glass covers the drag handle; page behind is visibly blurred/refracted.
4. Battery saver on → glass loses the lens bend but stays frosted; off → comes back.
5. Settings → min song duration slider: snaps to 5 s stops and **persists** after leaving.
6. Scroll Library albums fast; open/close queue; predictive back from full player — watch for jank.
7. Cold start time vs. previous build.
