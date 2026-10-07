# START HERE — PixelPlayer agent handoff (2026-10-04)

> **Update 2026-10-07:** `main-6hltyd` now sits on `android-int-oct3` (the newest Android code). The new Glyph logo is merged; the rest of the iOS batch still needs porting. Status, plans and owner decisions: [`2026-10-07-batch-status.md`](2026-10-07-batch-status.md).

You're a new agent with zero context on this codebase. Read this whole file before touching
anything. It covers what the app is, who you're working for, where the code actually lives
(this part is messy, see §2), how the code is organised, how to build and test, what was
last worked on, and the traps earlier agents hit.

The older notes in `handoff/` are dated and topic-specific. Read them when your task touches
that area. Don't treat them as the current state.

---

## 1. The 60-second version

- **What:** PixelPlayer (package `com.theveloper.pixelplay`). An Android music player, 100%
  Kotlin, Jetpack Compose + Material 3, with a "Liquid Glass" UI mode. Started as a fork of the
  open-source PixelPlayer by theovilardo. Now it's a solo project, shipped as a free public
  beta through GitHub Releases (not the Play Store). The repo is `RedSn0w1877/PixlAudio`, and the
  Gradle root project is still named `PixelPlay`.
- **Who:** Hoa (GitHub `RedSn0w1877`), 19. Techy, loves features and polish, but new to Android
  dev and only codes a little. Explain in plain language. When something's broken, say "found X,
  here's the fix" without the doom framing. Their main test device is a **Pixel 10 Pro**
  (Tensor G5, arm64, Android 16+). They also have a Pixel Watch 4, which is why the Wear OS
  module matters.
- **Version:** `0.7.6-beta2`, versionCode 11 (`gradle.properties`). Beta 2 shipped. Current work
  is post-Beta-2 polish: the glass UI, performance, lyrics, Spotify features.
- **Last session (2026-10-04):** the user reported small stutters on page/menu transitions, then
  said they were *mostly fixed* and asked for this handoff instead. No code changed in that
  session. The remaining leads are in §7 if they come back to it.

---

## 2. Where the code lives (branch map, read this twice)

There are **two unrelated git histories** in this repo, with no common ancestor. A plain
`git merge` between them refuses (`unrelated histories`) or produces nonsense. Know which one
you're on.

### Lineage A: the desktop line (Beta 2 + lyric-sync rewrite)

```
eb50496  Rewrite lyric-sync as on-device forced alignment, plus Beta 2 work   (Sep 9)
cb03033  Initial commit
086c5dc  Merge remote's initial README
7a4382c  WIP: uncommitted changes, moved to the cloud   (only .claude/settings.local.json)
   ├── origin/main-6hltyd   = the above + this handoff  (this session's branch)
   └── origin/main-015nzo   = 086c5dc + 4 commits (Sep 28): "Liquid Glass v2 on a tiered
                              engine + app-wide jank fixes". Its handoff is
                              handoff/2026-09-28-liquid-glass-v2-and-perf.md on THAT branch.
```

### Lineage B: GitHub `main` (default branch) and children

```
6dcfc4b  PixelPlayer Beta 2                                    (Sep 13)
  …  Remix Studio+ (Runpod stem backends), later reverted       (Sep 21–25)
  …  Karaoke lyrics renderer, "sync it yourself" tap-sync editor (Sep 25)
  …  Old Liquid Glass wiped, NexHome glass kit ported into ui/glass  (Sep 26)
  …  Speed passes: startup, shell, screens, lists, images, player  (Sep 26)
648c6ab  origin/main: "Fix review findings: glass playlist scroll, …"   (Sep 26)
   └── origin/android-int-oct3 = main + BiniLyrics (first online lyrics source)
                                      + Spotify Connect output (Echo / TV / speakers)  (Oct 3)
         (also on their own branches: origin/binilyrics, origin/spotify-connect)
```

Lineage B has more docs on it: `docs/premium-tier-plan.md`, `docs/plus-*.md`,
`docs/beta2-release-notes.md`, and handoffs dated 09-08 through 09-10 plus
`handoff/BETA2-VERIFICATION-CHECKLIST.md`. Read them with
`git show origin/main:handoff/<file>`.

### What this means for you

1. **Ask Hoa which branch is canonical before doing real work.** Most likely
   `origin/android-int-oct3` (newest, newer than `main`). Lineage A has the Sep 28 glass/perf
   pass, which may or may not have been ported to B.
2. The user's desktop is a Windows machine. Earlier handoffs mention paths like
   `C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master` and an older copy under
   `Downloads\Code Projects\…`. Some of those desktop checkouts had **no git metadata at all**,
   with originals backed up under `backups/<task>-<date>/`. Don't assume the desktop tree matches
   any branch.
3. To move work across lineages, use `git cherry-pick` or re-apply by hand. Never force-merge.
4. CI workflows (`.github/workflows/*.yml`) trigger on `master`, but the default branch is
   `main`, so **CI effectively never runs on push**. Don't read "no red X" as "builds".

---

## 3. Your environment probably can't build this

Know which kind of agent you are:

- **Cloud container (like the 2026-10-04 session):** has JDK 21 and Gradle 8.14, but **no
  Android SDK** (`ANDROID_HOME` unset). You can't compile, run tests or produce an APK. Reason
  from code, keep changes small, and tell the user nothing was compiled.
  (The Sep 28 cloud session *did* manage `:app:assembleDebug`, so a container with an SDK
  is possible. Check `echo $ANDROID_HOME` first.)
- **On Hoa's Windows desktop:** use `./gradlew.bat`. Two known traps:
  - **Close Android Studio completely before building.** Otherwise you get
    `AccessDeniedException` file-lock failures on the resource-merge tasks. Fix with
    `rm -rf app/build`, then rebuild. The build cache in `gradle.properties` exists because of this.
  - An earlier agent's sandboxed shell got `java.io.IOException: Unable to establish loopback
    connection` on every Gradle call and had to ask Hoa to run builds in their own PowerShell.
    Test once, early.

### Build and test commands

```bash
./gradlew :app:assembleDebug --no-daemon                         # debug APK
./gradlew :app:assembleDebug -Ppixelplay.enableAbiSplits=true    # per-ABI APKs (what CI does)
./gradlew :app:testDebugUnitTest                                 # JVM unit tests (~90 files, 500+ cases)
./gradlew :wear:assembleDebug                                    # Wear OS app
```

- The debug build has applicationId **`com.theveloper.pixelplay.debug`**, so it installs next to
  a release build.
- With ABI splits on, the phone APK is `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
- Install with `adb install -r <apk>`. `-r` keeps user data. **Never** uninstall or
  `pm clear` without asking: the user's library, downloads, lyrics cache and playlists live there.
- Release signing reads `keystore.properties` and a `.jks`. Both are gitignored, and the build
  falls back to debug signing if they're missing.
- `android.enableR8.fullMode=false` is deliberate. Full mode breaks Hilt's SwitchingProvider
  and the release build crashes on launch (`ClassCastException`). Leave it off.

### Toolchain versions (`gradle/libs.versions.toml`)

AGP 9.2.0-alpha07, Kotlin 2.4.0, Compose BOM 2026.06.00, Compose UI 1.11.3, Material3
1.5.0-alpha22, Navigation Compose 2.9.8, Media3 1.10.1, Room 2.8.4, Hilt 2.59.2, Coil 2.7.0,
Kyant `backdrop` 2.0.0 (glass), LiteRT 2.2.0, ONNX Runtime Android 1.22.0. minSdk 30,
compileSdk 37, targetSdk 35, JVM 21. Check the toml before you use any API. Several of these
are alphas, and APIs differ from what you might remember.

---

## 4. Device and testing rules

- **Ask before installing an APK or driving the phone.** Earlier sessions got explicit
  permission each time and recorded it. A "yes" for one install doesn't cover the next one.
- The phone is the Pixel 10 Pro, adb serial `59240DLCH004G6` (USB, sometimes disconnects).
- `tools/android_phone_qa.py` is a guarded ADB UI driver. It refuses to send input unless
  PixelPlayer is in the foreground. Its ADB path is hard-coded to Hoa's Windows SDK.
- During earlier phone QA, `stay_on_while_plugged_in` was changed. Its original value is
  **7**. Restore it if you touch it.
- A Bluetooth device once sent stray media-pause commands mid-test. Disabling Bluetooth isolated
  it. Turn it back on afterwards.
- When a test needs the user (listening quality, visual feel), write a checkbox checklist like
  `handoff/2026-09-07-beta2-manual-checklist.md` and hand it over. Don't claim things you
  didn't observe.

---

## 5. Architecture map

Source root: `app/src/main/java/com/theveloper/pixelplay/` (~590 Kotlin files).

### Modules

| Module | What |
| --- | --- |
| `:app` | The phone app. Almost everything. |
| `:wear` | Wear OS app: remote control, local playback of transferred songs, lyrics, queue sync (~40 files). |
| `:shared` | Data classes exchanged between phone and watch (`WearPlayerState`, `WearLibraryItem`, …). |
| `:baselineprofile` | Baseline profile generator. Startup and scroll speed rely on it. |

### Layers (MVVM + Hilt + Room + Media3)

- **Playback.** `data/service/MusicService.kt` is a `MediaLibraryService` and the single source
  of playback truth. `data/service/player/DualPlayerEngine.kt` wraps two ExoPlayers for
  crossfades and custom transitions. The UI never binds to the service. It talks through a
  Media3 `MediaController`.
- **State.** `presentation/viewmodel/PlayerViewModel.kt` (3,300+ lines) is a *facade* over many
  `@Singleton` `*StateHolder.kt` classes in the same folder (`PlaybackStateHolder`,
  `QueueStateHolder`, `LyricsStateHolder`, `SearchStateHolder`, `ThemeStateHolder`, `CastStateHolder`,
  …). They're process-scoped so state survives ViewModel recreation. **To add player state,
  extend a state holder. Don't add fields to PlayerViewModel.**
  - `StablePlayerState` holds slow-changing playback state. `PlayerUiState` is the big UI blob.
    `currentPosition` ticks every 250 ms. **Never `collectAsState()` the whole `playerUiState`
    or `stablePlayerState` in a screen.** Slice it with `.map { … }.distinctUntilChanged()`
    inside `remember { }` (see `UnifiedPlayerSheetV2.kt` and the `currentSongId` slice in
    `MainActivity.kt`). Unsliced collection caused a lot of the past jank.
- **Preferences.** `data/preferences/UserPreferencesRepository.kt` (1,500+ lines, DataStore).
  Pref flows are `distinctUntilChanged`, because one write used to re-run ~95 collectors.
- **Database.** Room, `data/database/PixelPlayDatabase.kt`, schema v42+, with exported schemas
  in `app/schemas/`. Every schema change needs a migration. **Never** use
  destructive migration: users' playlists, lyrics and engagement history live there.
  `pixelplay_database.db` at the repo root is a stray local snapshot used for debugging.
- **Library.** `data/repository/MediaStoreSongRepository.kt` + `data/worker/SyncWorker.kt`
  (throttled MediaStore scans). Folder filters live in `utils/DirectoryRuleResolver.kt`.
- **Remote sources.** Spotify (`data/spotify/`) is metadata-only. `data/youtube/` matches each
  track to a YouTube video and resolves audio from it. `InnerTubeContexts.kt` (client contexts,
  incl. the `VISIONOS` client) is **fragile**: it breaks whenever YouTube changes things
  server-side. `data/stream/CloudStreamProxy.kt` is a local proxy that handles ranges, signed-URL
  expiry and coalesced resolves. Navidrome/Jellyfin clients live under `data/network/`.
  Lineage B adds **Spotify Connect output** and **BiniLyrics**.
- **TAIS** (Trinity Audio Intelligence System, `data/tais/`) is the on-device ML:
  - `stems/`: vocal/instrumental separation (UVR-MDX-NET, `assets/tais/stem_separation.tflite`, LiteRT).
  - `lyrics/`: **forced alignment** for word-synced lyrics. `TaisWav2Vec2Aligner.kt` runs
    `wav2vec2-base-960h` through ONNX Runtime (NNAPI first, CPU fallback) and does CTC
    Viterbi alignment. `TaisLyricsAligner.kt` orchestrates (decode to 16 kHz mono, align,
    persist). The original 09-06 design interpolated over failed chunks. Later work replaced
    that with *rejecting* empty, all-zero or backward timings, so a failed sync no longer
    pretends to succeed. Must be **fp32**: the int8 export has no `ConvInteger` kernel, and
    the Tensor NPU rejects int8 input for this graph.
  - ⚠️ **The model file `assets/tais/wav2vec2_base_960h_fp32.onnx` (~378 MB) is not in git.**
    It's too big. Only `wav2vec2_vocab.json` is committed. Without the model, lyric sync fails.
    On the desktop it was downloaded from the Hugging Face `wav2vec2-base-960h` ONNX export.
    On first use it's copied from assets to `filesDir` and handed to ONNX Runtime **by path**,
    so it loads off the Java heap. Never `readBytes()` it: a single ~378 MB `byte[]` OOMs.
  - `TaisNpuRunner.kt` / `TaisNpuModelContract.kt`: Tensor G5 NPU work. See
    `handoff/2026-09-07-tensor-g5-npu-integration.md` and `tools/tensor-g5/`, `tools/litert-runtime/`.
  - `data/worker/TaisStudioWorker.kt` is the WorkManager job behind "Sync lyrics" / "Render
    instrumental". It shows as a foreground notification ("Lyric Sync").
- **Music intelligence.** `data/recommendation/`, `DailyMixManager.kt`,
  `MusicIntelligenceViewModel.kt`. Local learning from completions, skips and favourites. It's an
  adaptive ranker, not a trained model. Don't oversell it.
- **AI features.** `data/ai/` (Gemini/Gemma, Groq, OpenRouter): lyric translation, AI playlists,
  TAIS chat. Users supply their own keys.

### UI

- `MainActivity.kt` (~1,460 lines): the app shell. Scaffold, bottom nav bar (Material or glass
  `LiquidGlassNavBar`), sidebar drawer, page-backdrop recording for glass, and the player sheet
  (`presentation/components/UnifiedPlayerSheetV2.kt`).
- `presentation/navigation/AppNavigation.kt` + `Screen.kt`: one `NavHost`, string routes.
  Home / Search / Library are "main root" tabs and slide horizontally between each other.
  Every other destination uses the AOSP shared-axis transition. Every destination is wrapped in
  `presentation/components/ScreenWrapper.kt`, which adds the depth effect (rounded corners,
  dim, 24 dp blur) to the screen behind during a push or pop.
- If a new route should hide the bottom bar, add it to `routesWithHiddenNavigationBar` in
  `MainActivity.MainUI`. It's easy to forget.
- `ui/glass/`: Liquid Glass components on Kyant `backdrop`. Details differ by lineage. A has a
  tiered engine (Refractive API 33+ / Frosted API 31–32 or battery saver / Solid API 30). B
  has the NexHome kit port. **"Disable blur all over" always wins** and forces Material 3.
  Glass inside screens must **not** sample the page backdrop its ancestor is recording.
  That self-reference once crashed the app with a native stack overflow in
  `RenderNode::prepareTreeImpl`. See the comment around `LocalAppBackdrop provides
  emptyBackdrop()` in `MainActivity.kt`.
- `app/compose_stability.conf` and strong skipping are on. Keep params stable (use `ImmutableList`
  from kotlinx-collections-immutable for lists).

---

## 6. Conventions

- **Match the surrounding style.** The codebase uses long *why*-comments next to non-obvious
  decisions, especially performance and glass code. Keep them, and add one when you make a
  non-obvious choice. Some legacy comments are in Spanish (the original author's). Leave them.
- **Commit messages** are plain-English imperative sentences about what the user sees, e.g.
  "Speed up the lyrics screen: no more measuring every line in one frame" or "Stop player-sheet
  recomposition during gestures and queue drags".
- **Performance rules learned the hard way:**
  - Read animated values in **draw/layout lambdas** (`graphicsLayer { }`, `drawBehind { }`,
    `offset { }`), not in composition. Pass `() -> Float` providers, not `Float`.
  - Don't key `remember(...) { derivedStateOf { } }` on a per-frame value. That rebuilds it
    every frame and defeats the point.
  - Cache `RenderEffect`s and shapes (see `BlurEffectCache`, `NavBarShapeCache`). Quantize blur radii.
  - No unsliced big-StateFlow collection in screens (see §5).
  - No `AnimatedVisibility` per list item, and no stacks of `animate*AsState` per row.
  - Images: always give Coil a size.
- **Don't commit:** APKs/AABs (gitignored), keystores, `local.properties`, API keys, the ONNX
  model. The repo root has stray debug files (`screen*.png`, `logcat_reset.txt`,
  `replay_pid*.log`, `message.txt`, `pixelplay_database.db`). Ask before deleting them, but
  don't add more.
- `CLAUDE.md` is in `.gitignore`. Hoa keeps a detailed one on the desktop that isn't in the
  repo. If you're on the desktop, read it. In the cloud, this file plus `AGENTS.md` replace it.
- **License:** proprietary (`LICENSE`). Contributions before 2026-05-12 remain MIT. Third-party
  notices are in `THIRD_PARTY_NOTICES.md`. Update it when you add a dependency or model.

---

## 7. Open threads and known leads

### Transition stutter (user says "mostly fixed", so park it unless they bring it back)

Verified by reading the code on lineage A (2026-10-04), not profiled. Two remaining causes:

1. **`ScreenWrapper.kt` reads its transition animations during composition.**
   `animatedCornerRadius`, `animatedDimAlpha` and `animatedBlurRadius` are `by`-delegated
   values from `transition.animateFloat/animateDp`. So both the outgoing and incoming screen
   wrappers recompose on every frame of every push/pop (~350 ms), and `Modifier.blur(radius)`
   builds a new RenderEffect each frame. **Fix:** keep the `State<…>` objects and read them
   inside one `graphicsLayer { }`. Set
   `renderEffect = BlurEffect(r.toPx(), r.toPx(), TileMode.Clamp)` there (API 31+, cached by
   quantized radius), instead of `.blur()`. Also, `navController.visibleEntries` is
   collected twice there, which is redundant.
   Separately, non-root screens keep `CompositingStrategy.Offscreen` even when idle, which
   costs on scroll. The comment explains why it isn't toggled, so think carefully before
   changing it.
2. **`MainActivity.MainUI` reads `navBarVisibilityProgress` in composition.** It animates for
   220 ms every time the bar hides/shows, i.e. on every Settings or detail-page open. It feeds
   `remember(…, navBarVisibilityProgress) { derivedStateOf { … } }` (rebuilt per frame) and then
   `miniPlayerBottomMargin`, and from there `sheetCollapsedTargetY` (a `Float` param to
   `UnifiedPlayerSheetV2`). Result: the root shell **and** the player sheet recompose every
   frame. **Fix:** key those `derivedStateOf`s only on non-animated inputs, read
   `navBarVisibilityProgressState.value` *inside* them, and pass the collapsed target Y as a
   provider lambda. `predictiveBackCollapseFraction` is also collected in `MainUI` and only
   used inside a `graphicsLayer`. Read it from the flow's `.value` in the lambda instead, so
   predictive-back drags stop recomposing the shell.
   (The Sep 28 handoff flagged both items as "not done".)

### Other open items from earlier handoffs

- Some Spotify-imported tracks never get matched to a YouTube video. That breaks both playback
  and lyric sync for those tracks.
- Lyric sync visual tuning (`LyricsSheet.kt`) and instrumental quality need listening and visual
  checks on the phone. See the 09-06 and 09-07 handoffs.
- Long-term retention of downloads/instrumentals ("they disappeared after a few days") needs
  a re-check after days, not minutes.
- The queue snapshot shares the settings DataStore file, a perf/robustness concern (from the 09-28 handoff).
- The four `LyricsFormatDeviceTest` instrumentation tests compile but have never been run.

---

## 8. First 10 minutes checklist

1. `git status`, `git log --oneline -5`, `git branch -a`. Figure out which lineage you're on (§2).
2. Ask Hoa which branch to build on if your task isn't trivially local.
3. `echo $ANDROID_HOME`. Can you build? If not, say so up front (§3).
4. Read the topic handoff for your area:
   - lyrics and alignment: `2026-09-06-lyric-sync-forced-alignment.md`, `2026-09-07-lyrics-resync.md`, `2026-09-07-provider-first-lyrics.md`
   - NPU / Tensor: `2026-09-07-tensor-g5-npu-integration.md`
   - Beta 2 reliability (downloads, playlists, streaming, intelligence): `2026-09-07-beta2-stability-music-intelligence.md`
   - glass and performance: `2026-09-28-liquid-glass-v2-and-perf.md` (on `origin/main-015nzo`),
     plus `docs/performance-report.md` and `app/performance_analysis.md` (Spanish; some items
     are already fixed, so verify against the code)
   - release, Plus tier, packaging: lineage B `docs/` and `handoff/` (§2)
5. Before pushing: re-read your diff the way a compiler would (imports, types, API versions).
   That matters doubly when you can't build.
6. Finish with a handoff note in `handoff/YYYY-MM-DD-topic.md`. Include what changed, what was
   verified (and how), what wasn't, and the exact next step. Future agents depend on it.
