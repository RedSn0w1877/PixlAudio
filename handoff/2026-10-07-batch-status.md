# 2026-10-07 batch: Android status (what's done, what's not)

Hoa asked for a batch of changes on the iOS app and said: "make sure you put these changes into the
Android build too". The cloud session stopped at a clean point on Hoa's request (switching to
local work). **This branch (`main-6hltyd`) is now based on `android-int-oct3`** (the newest Android
code: BiniLyrics plus Spotify Connect). It carries the handoff docs and the new logo.

Read first:
- `CLAUDE.md`, then `handoff/2026-10-04-START-HERE.md` (which describes the two git histories), then this page.
- Owner decisions, binding for both apps: `handoff/2026-10-07-plans/DECISIONS.md`.
- Android plans, one per item: `handoff/2026-10-07-plans/<item>.json`. They're written against `android-int-oct3 @ 90d32ca`, with file:line refs, and were **not yet double-checked by a second reviewer**, so verify against the code.
- The iOS versions of the same plans are in `handoff/2026-10-07-plans/ios-plans/`, for intent only.
- The iOS repo's `docs/handoff/2026-10-07-batch-status.md` has the iOS side.

## Status by item (Android)

| Request | Android status | Notes from the investigation |
|---|---|---|
| New logo, concept **C · Glyph** | ✅ **Done, and the resources compile** (`:app` and `:wear` `processDebugResources`) | Adaptive icon foreground/background/monochrome at every density, the Wear icon, the in-app glyph (`pixelplay_base_monochrome.xml`), and `assets/icon.png`. Generator: `tools/make-launcher-icon.py`. Not done: the notification icon `monochrome_player.xml` still has the old silhouette. See `handoff/2026-10-07-logo.md`. |
| Lyrics: Synced/Static → **Translate · Sing**; screen always on; glass on the lyrics page | ❌ Not started | `lyrics-page.json`. Automatic synced/plain already exists (LyricsSheet.kt:394-402). The instrumental render and play exist. The manual override lives in LyricsFloatingToolbar.kt:125-161. |
| Sync editor "loading then vanishes" (iOS bug) | ✅ The iOS bug does **not** exist on Android | `sync-editor.json`. The editor is an overlay inside FullPlayerContent, but the investigation found a few smaller real Android defects; fix those. |
| Faster streaming | ❌ Not started | `streaming-speed.json`. Biggest suspect: the Ktor proxy body is never flushed (CloudStreamProxy.kt:365), so starts wait for about 1 MiB. Retries (R5a) are already there. |
| Local AI (on-device default, cloud optional, downloadable model behind a toggle) | ❌ Not started | `local-ai.json` (XL). Cloud is the hard-coded default today. MediaPipe Gemma exists, but only as a manual file import. Plan: Gemini Nano (AICore) as the default on the Pixel 10 Pro, and a downloadable Gemma behind "Use downloaded AI model". |
| More Liquid Glass incl. the queue | ❌ Not started | `glass-expansion.json`. The queue is full height on an opaque scrim; there's glass-on-glass in the toolbar; ⋯ and the undo bar are opaque in glass mode. |
| Heart only updates after another tap (iOS bug) | ✅ The bug does **not** exist on Android | `player-controls.json`. The heart is driven by a Room Flow and the lock-screen Like is wired. Still to port: prev/next timing equal to play/pause, and the top bar showing the device name instead of "Now Playing" plus the cloud icon. |
| Volume buttons control the Spotify Connect device | ⚠️ Mostly there already | `connect-volume.json`. A remote MediaSession volume exists (SpotifyConnectSessionPlayer.kt), so the keys already drive the device. Three fixes: the volume scale (max 100 with a 5-point step clashes with +1 per key), the key handling, and the other details in the plan. |
| App-wide accent colour | ❌ Not started | `accent-color.json`. Material You is the default today; add an accent preference with presets plus custom. The player keeps album colours. |
| Cloud Studio (RunPod BS-RoFormer + AI lyrics) | 📝 Design only, shared with iOS | `handoff/2026-10-07-plans/cloud-studio-design.md` §8 is the Android follow-up. The worker lives in the iOS repo (`cloud/runpod-worker/`, not built yet) and serves both apps. |
| CI never runs (workflows trigger on `master`; the default branch is `main`) | ❌ Not fixed | The plan for this item wasn't finished. Change the `on:` triggers in `.github/workflows/phone-debug.yml` etc. to `main` / all branches. |

## Building Android on Linux (what the cloud session had to fix)

The baseline `android-int-oct3` compiles (`:app:compileDebugKotlin` BUILD SUCCESSFUL) once these are in place:

1. **`gradlew` wasn't executable** in git (committed from Windows). **Fixed on this branch** (`git update-index --chmod=+x gradlew`).
2. **JetBrains JDK 21:** `gradle/gradle-daemon-jvm.properties` asks for vendor JETBRAINS, and its foojay download id now returns *"Package id not found"*. So a fresh machine without JBR 21 fails.
   - Fix locally: download the **jbrsdk** (not the slim `jbr`, which has no javac), e.g. `https://cache-redirector.jetbrains.com/intellij-jbr/jbrsdk-21.0.8-linux-x64-b1138.52.tar.gz`.
   - Then add `org.gradle.java.installations.paths=<dir>` to `~/.gradle/gradle.properties`.
   - Consider regenerating the file with `./gradlew updateDaemonJvm`.
3. **SDK platform naming:** the new Android CLI installs `platforms/android-37.0` (api-level "37.0"), but AGP 9.2.0-alpha07 with `compileSdk = 37` looks for `android-37`.
   - The workaround was a copy named `android-37`, with `package.xml` set to `path="platforms;android-37"`, `<api-level>37</api-level>` and `xsi:type="ns9:platformDetailsType"`.
4. **Maven Central sometimes returns HTTP 429.** Retry after 60 s; once the cache is warm it's fine.

On Hoa's Windows PC none of this matters if Android Studio already has JBR and SDK 37. Remember: close Android Studio before building from a terminal.

## What to do next (Android)

1. Port the items above in this order:
   1. player top bar and prev/next timing (small);
   2. Connect volume fixes (small);
   3. lyrics page;
   4. accent colour;
   5. more glass;
   6. streaming;
   7. local AI;
   8. Cloud Studio client (after the worker exists).
2. After each item:
   - compile with `bash ./gradlew :app:compileDebugKotlin`;
   - run unit tests with `bash ./gradlew :app:testDebugUnitTest`;
   - write a dated handoff note.
3. Fix the CI triggers so pushes build an APK artifact.
4. Open a PR into `main` (it will also carry the 4 `android-int-oct3` merge commits).

## Branches

- **`main-6hltyd`:** `android-int-oct3` + handoff docs + the logo merge + these plans.
- The previous `main-6hltyd` (the old desktop history, with only the 2026-10-04 handoff) was replaced. Its handoff commit was cherry-picked here, so nothing was lost.
