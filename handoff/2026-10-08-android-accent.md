# 2026-10-08: accent colour on Android (branch `port-accent`)

This is the Android port of the 2026-10-07 iOS accent colour. It follows `handoff/2026-10-07-plans/accent-color.json` and DECISIONS › Accent colour, and uses the iOS branch (`docs/handoff/2026-10-07-accent-color.md` in PixlAudio-iOS) for the behaviour and the numbers.

## What you get

- **Settings › Appearance › Global Theme › Accent Color**, between App Theme and Smooth corners (the same order as iOS). It has two rows of six swatches:
  - Dynamic (Material You from the wallpaper; still the default, so nothing changes until you pick something);
  - Blue, Indigo, Purple, Pink, Red, Orange, Yellow, Green, Mint, Graphite;
  - Custom.
- **Picking one re-themes the whole app at once:** buttons, switches, sliders, the navigation bar, and the glass highlights in glass mode. The scheme is the vivid "seed-chroma" one from iOS, so a red stays red in light mode (#BD0E12, not Material You's brick #904A42). Dark mode stays pastel, as Material You is.
- **Custom** opens a sheet with:
  - a saturation/brightness panel;
  - a hue bar;
  - a hex field;
  - the colour you picked shown next to its in-app tone ("Aa").
  
  Nothing changes until you tap **Use color**. Dragging inside the panel never moves the sheet.
- **The player keeps its album colours** (mini player, full player, lyrics, queue), in both Material and glass mode.
- **Player Theme:** "System Dynamic" is now **"Accent Color"**. The stored value is still `dynamic`, so old backups keep working. It makes the player use the app's accent, which is Material You while the accent is Dynamic.
- **Widgets and the watch:** under Player Theme › Accent Color, the Glance widgets and the Pixel Watch (live state and transferred songs) use the accent. They now refresh as soon as Player Theme or the accent changes, not at the next track.
- **Glass mode:**
  - with a chosen accent, the app-wide glass accent is that accent;
  - with Dynamic, it still follows the playing album, exactly as before;
  - the ambient artwork background stays art-driven either way.
- **Backups:** the accent is stored as `accent_color_v1` = `"#RRGGBB"` in the shared `settings` store. That is the same key and format as iOS, so the global-settings backup carries it with no backup-code change.
  - Restoring an old backup (one without the key) sets the accent back to Dynamic.
  - A junk value reads as Dynamic.

## Code map

| Piece | Where |
|---|---|
| Presets, hex parse/format, HSV and luminance maths (pure Kotlin) | `data/preferences/AccentColor.kt` |
| Stored key, normalised flow, setter | `ThemePreferencesRepository` (`accentColorFlow`, `setAccentColor`) |
| Vivid scheme | `ui/theme/ColorRoles.kt` `generateAccentColorSchemePair`. TonalSpot palettes, primary at max(36, seed chroma). Near-grey seeds use chroma-0 palettes. |
| Memo (same seed ⇒ same instances; `ColorScheme` has no `equals`) | `ui/theme/AccentColorSchemes.kt` |
| App-wide state | `ThemeStateHolder.accentScheme`: `StateFlow<AccentScheme?>`, built on Default, eager on `@AppScope` |
| Root theme | `PixelPlayTheme(accentSchemePair = …)`. Order: override, then accent, then Material You, then static. |
| First frame | `MainActivity`: the splash also waits for `accentScheme` (same 1 s cap), so there's no Material You flash |
| Glass accent | `GlassPalette.rootAccent` / `playerAccent`; `GlassRoot` in MainActivity; `ProvidePlayerGlassPalette` (`PlayerSchemeScopes.kt`) around `UnifiedPlayerSheetV2` |
| Settings row + picker | `presentation/screens/AccentColorSettingItem.kt`; `GlassFlatSettingRow` is now `internal` |
| Widgets / watch | `MusicService.buildPlayerInfo` (accent branch + refresh collector); `PhoneDirectWatchTransferCoordinator.resolveTransferThemePalette` |
| Strings | `values/strings_settings.xml` (English). `settings_player_theme_dynamic` is gone from values/ and the 10 locales that had it. The new keys fall back to English there. |

Performance notes:
- Schemes are never built on the main thread.
- The swatches show the seed colour (as iOS does), so the row builds no schemes.
- The picker's drag values are read only in draw and layout lambdas.
- The picker preview is debounced to 120 ms and kept out of the memo.

## Where this differs from the plan (and why)

- **Graphite is pure grey at the exact role tones** (#5E5E5E light / #C6C6C6 dark), the same as iOS. The plan's HSL greyscale (#6A6A6A / #D7D7D7) can drop just under 4.5:1 for primary on the background.
- **The swatches show the named colour, not the scheme tone** (the same as iOS). In dark mode the tones are nearly equal for Red/Pink and Blue/Indigo, and in light mode Yellow and Orange turn olive and brown.
- **The contrast check is 4.5:1 for primary on the background**, stricter than the plan's 3:1. Every preset passes, and so does a sweep of 606 custom picks (24 hues × 5 chromas × 5 tones, plus black, white, grey and the RGB primaries).
- **Widgets and the watch refresh at once** when the accent or Player Theme changes. The plan left that for the next track.
- **The hex field sits above the panel**, so the keyboard can't cover it.

## Where Android differs from iOS

- The default is **Dynamic (Material You)**, not the violet. There's no "PixlAudio" swatch; Custom #6C4FF5 gives that colour. `""` means each platform's own default.
- The custom picker is PixlAudio's own sheet and applies on **Use color**. iOS uses the system ColorPicker and applies live after a 180 ms debounce.

## What CI proved

- [Android CI](https://github.com/RedSn0w1877/PixlAudio/actions/runs/37750327572) run 37750327572 on `port-accent` @ `37c9371` is green:
  - `:app:compileDebugKotlin`, which includes the Hilt graph with the new `@AppScope` injection and the MainActivity field;
  - `:app:testDebugUnitTest`;
  - `:wear:compileDebugKotlin`;
  - `:app:assembleDebug` arm64.
- **New tests** (all inside that green test task):
  - `AccentColorSchemeTest`: WCAG AA for every preset and a hue/chroma/tone sweep; the light and dark primaries pinned to MDC 1.14.0 values, which match iOS; vivid ≥ TonalSpot chroma; Graphite grey; surfaces unchanged; memo identity.
  - `AccentColorTest`: hex normalise, presets, HSV round trip, check-mark colour.
  - `ThemePreferencesRepositoryTest`: default, normalise, key removal, junk, and a backup round trip through `GlobalSettingsModuleHandler`. It also checks that an old backup restores to Dynamic.
  - `GlassPaletteTest`: the `rootAccent` / `playerAccent` rules.
- CI doesn't upload test reports on success. So the record shows that the test task passed with these classes compiled in; it doesn't list them one by one.
- **Earlier in the session:** before writing the tests, I computed the pinned colours, the 4.5:1 sweep and the TonalSpot surface equality with plain `javac` against the real MDC 1.14.0 jar from the Gradle cache. There were no failures.

## Nothing has run on a phone. Checklist for the Pixel 10 Pro

- [ ] Settings › Appearance shows **Accent Color** under App Theme. Check Material 3 mode and glass mode, light and dark.
- [ ] Tap each preset: the app recolours at once (buttons, switches, sliders, the navigation bar), with no stutter. **Dynamic** brings back the wallpaper colours.
- [ ] Graphite looks grey, and text stays readable. The dark-mode pastels look OK.
- [ ] **Custom:**
  - [ ] dragging the panel and the hue bar moves the thumbs, and the sheet stays put;
  - [ ] typing a hex works, and a bad one shows the hint;
  - [ ] **Use color** applies and **Cancel** doesn't;
  - [ ] the Custom swatch then shows your colour with a check.
- [ ] With an accent picked and Player Theme › Album Art, the mini player, full player, lyrics and queue keep the **album colours** (Material and glass).
- [ ] Player Theme now reads **Album Art / Accent Color**. Accent Color recolours the player in the accent.
- [ ] **Glass mode:**
  - [ ] the tab bar, toggles, sliders, chips and settings icon discs take the accent;
  - [ ] the background still comes from the artwork;
  - [ ] back on Dynamic, the glass accent follows the album again.
- [ ] **Cold start** with an accent set: no Material You flash, and the splash isn't noticeably longer.
- [ ] **Home-screen widget**, with Player Theme › Accent Color: it recolours within about a second of changing the accent.
- [ ] **Pixel Watch 4**, with Player Theme › Accent Color: the watch player uses the accent, and so does a song sent to the watch.
- [ ] **Backups:**
  - [ ] make a backup, change the accent, then restore: the app re-tints live to the backed-up accent;
  - [ ] restoring an older backup gives Dynamic.
- [ ] **"Open with PixlAudio"** from a file manager: the overlay uses the accent.

## Next steps

1. Hoa checks the list above on the phone, then opens a PR `port-accent` → `main`. Agents don't merge into main.
2. iOS follow-up: `AndroidPreferenceCatalog.iosOnly` lists `accent_color_v1` as iOS-only. Android now reads the same key and format, so that doc comment (and parity.md) should say it's shared.
3. Optional follow-ups, the same as iOS's:
   - an Increase Contrast variant (pass `UiModeManager.getContrast()` as the `DynamicScheme` contrast level on API 34+);
   - translating the new strings in the 10 locales.
