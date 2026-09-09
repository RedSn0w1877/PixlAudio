# Handoff: Beta 2 lyric-sync rewrite (forced alignment)

Written by Claude (Sonnet 5) for the next agent (Codex) picking this up. Everything below is
current as of **2026-09-06**, right after a visual tuning fix that has **not been rebuilt or
tested on-device yet** — that's the very next step.

**Read this whole doc before touching anything.** You have no prior context on this codebase —
there's a `CLAUDE.md` at the repo root written for Claude Code specifically (your harness may or
may not surface it automatically the way it does for Claude), so read that file directly too if
you can. The "Project orientation" section right below pulls out what matters most for this task,
but `CLAUDE.md` has the full picture (every subsystem, conventions, CI setup, etc.).

## Project orientation (what this app even is)

**PixelPlayer** — an Android music player, 100% Kotlin, Jetpack Compose + Material 3. Package
`com.theveloper.pixelplay`. minSdk 30, compile/targetSdk 37, JVM target 21. It's a solo/small-team
fork of a larger open-source player with most remote-source integrations stripped out — it's
being shipped as a free public beta directly via GitHub Releases (not the Play Store). Beta 1
already shipped; this work is for **Beta 2**.

Architecture in brief (MVVM, Hilt DI, Room, Media3):

- **Playback**: `MusicService` (`data/service/MusicService.kt`) is a `MediaLibraryService` and
  the single source of playback truth. `DualPlayerEngine` wraps two ExoPlayer instances for
  crossfades. UI never binds to the service directly — it goes through a Media3
  `MediaController`.
- **State**: `PlayerViewModel` is a facade over many `@Singleton` `*StateHolder.kt` classes in
  `presentation/viewmodel/` — these are process-scoped singletons so state survives ViewModel
  recreation. If you're adding player-related state, extend a state holder, don't add fields to
  `PlayerViewModel` directly.
- **Data sources**: local library via `MediaStoreSongRepository` + `SyncWorker`. **Spotify is the
  only remote source** (`data/spotify/`) — but Spotify's API is metadata-only (no audio), so
  `data/youtube/` matches each track to a YouTube video and resolves playable audio from it. That
  YouTube layer is fragile and breaks whenever YouTube changes something server-side — see
  `CLAUDE.md`'s big section on `InnerTubeContexts.kt` / the `VISIONOS` client if you end up
  anywhere near playback resolution. (Relevant to this handoff: one of the "open items" below is
  a bug where some Spotify-imported tracks never get matched to a YouTube video at all, which
  breaks both playback *and* the lyric-sync feature for those specific tracks.)
- **TAIS** (Trinity Audio Intelligence System) — `data/tais/` — is the on-device AI/ML subsystem:
  stem separation (`data/tais/stems/`), and the lyrics/forced-alignment code this handoff is
  about (`data/tais/lyrics/`). `TaisAiEngine.kt` is the shared TFLite/NNAPI runtime wrapper used
  by the *other* TAIS features (stem separation, DJ intent embeddings) — the new lyrics code in
  this handoff deliberately does **not** go through it, since it uses ONNX Runtime instead of
  TFLite (see below for why). `TaisStudioWorker` (WorkManager) is the background job the "Sync
  Lyrics" / "Render Instrumental" buttons in the UI enqueue; its progress shows as a real Android
  foreground-service notification titled "Lyric Sync".
- **Build**: Gradle wrapper, `./gradlew.bat` on this Windows machine. Key command for this work:
  `./gradlew.bat :app:assembleDebug --no-daemon`. **Android Studio must be fully closed** before
  running this or you get file-lock build failures (`AccessDeniedException` on resource merge
  tasks) — this is a known, previously-diagnosed quirk of this specific machine, not new.
- **License**: proprietary (see `LICENSE`); contributions before 2026-05-12 remain MIT.

The user (Hoa) is new to Android development — plain-language explanations over jargon, and avoid
alarming framing when something's broken (e.g. "found a bug in X, here's the concrete fix" rather
than dwelling on severity).

## Background / why this exists

PixelPlayer is Hoa's Android music player, shipping as a public GitHub beta (not Play Store).
Beta 1 already shipped. This work is for **Beta 2**.

Hoa's complaint about the old lyric-sync feature: it was slow (multi-minute) and frequently
skipped whole lines. Root cause, diagnosed this session: the old pipeline
(`TaisWhisperAsr` + fuzzy-matching in `TaisLyricsAligner`) ran a small on-device Whisper model to
**transcribe** the song from scratch, then fuzzy-matched that guess against the real lyrics. Two
problems: Whisper's autoregressive decoder can't be NPU-accelerated (NNAPI rejects the `WHILE`
loop, silent CPU fallback, ~0.5x realtime), and transcription-then-match has no way to recover
when the guess is wrong — it just interpolates over the gap, which is what "skips a whole line"
looked like.

Hoa explicitly asked for something free, faster, and able to use the Pixel's Tensor NPU, and said
size doesn't matter ("do whatever approach you think has the best performance").

## The new approach: CTC forced alignment

Replaced ASR-then-guess with **forced alignment**: given lyrics we already know are correct, a
model figures out *when* each word falls in the audio — it can't skip a line because every input
word must be placed somewhere, in order. Model: `wav2vec2-base-960h` (Meta, Apache-2.0), run
through **ONNX Runtime** with the **NNAPI execution provider** requested first (the path to the
Tensor chip's NPU), CPU as automatic fallback.

## Files changed this session

- **New:** `app/src/main/java/com/theveloper/pixelplay/data/tais/lyrics/TaisWav2Vec2Aligner.kt`
  — loads the ONNX model, runs the acoustic model over a chunk of audio, does the actual CTC
  forced-alignment math (a Viterbi/DP over an "extended" blank-interleaved target sequence — same
  construction as `torchaudio.functional.forced_align`), returns per-word timestamps.
- **Rewritten:** `app/src/main/java/com/theveloper/pixelplay/data/tais/lyrics/TaisLyricsAligner.kt`
  — no longer does ASR; instead groups lyric lines into ~30s chunks (using real LRC line
  timestamps for window placement when available, proportional-by-character-count estimate
  otherwise) and calls `TaisWav2Vec2Aligner` per chunk. Has a `fillGaps` safety net that linearly
  interpolates any chunk that fails, so one bad chunk degrades gracefully instead of corrupting
  the whole result.
- **Deleted:** `TaisWhisperAsr.kt`, `WhisperVocab.kt`, and the old
  `assets/tais/whisper_tiny_en.tflite` / `whisper_vocab_en.bin` model files.
- **New assets:**
  `app/src/main/assets/tais/wav2vec2_base_960h_fp32.onnx` (~378MB — see "why fp32" below),
  `app/src/main/assets/tais/wav2vec2_vocab.json` (32-token char-level CTC vocab, tiny).
- **`app/src/main/java/com/theveloper/pixelplay/data/worker/TaisStudioWorker.kt`** — now passes
  real per-line LRC timestamps into `forceAlign()` when the song has `LineSyncedOnly` lyrics
  (most LRCLIB lyrics already are), instead of discarding them like the old code did.
- **Gradle:** added `com.microsoft.onnxruntime:onnxruntime-android:1.22.0` to
  `gradle/libs.versions.toml` + `app/build.gradle.kts`; added `noCompress.add("onnx")` next to
  the existing `noCompress.add("tflite")`.
- **Visual tuning fix (just made, NOT yet rebuilt/tested — see "Immediate next step" below):**
  `app/src/main/java/com/theveloper/pixelplay/presentation/components/LyricsSheet.kt`,
  `app/src/main/java/com/theveloper/pixelplay/data/preferences/UserPreferencesRepository.kt`,
  `app/src/main/java/com/theveloper/pixelplay/presentation/viewmodel/SettingsViewModel.kt`
  — see "Bug #3" below.

## Bugs found via on-device testing this session (all fixed, but #3's fix is unverified)

Testing method used: build via user's own PowerShell (`.\gradlew.bat :app:assembleDebug
--no-daemon`, **Android Studio must be closed first** or you get file-lock errors), install via
`adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` (device is arm64, ABI splits
are on), then watch `adb logcat` / `adb shell dumpsys notification --noredact` (the
`TaisStudioWorker` foreground notification shows live progress text) / `adb shell dumpsys
jobscheduler` (to see if the WorkManager job actually started/finished) while Hoa taps "Sync
Lyrics" in the app. **I cannot run Gradle myself** — my own sandboxed Bash gets a
`java.io.IOException: Unable to establish loopback connection` on every Gradle invocation. Every
build in this session was run by Hoa in their own PowerShell. Confirm whether this constraint
applies to you too before assuming you can build directly.

1. **`ConvInteger` kernel missing.** First model tried was the int8-quantized ONNX export
   (`model_int8.onnx`, ~95MB) for NPU-friendliness. Both the NNAPI attempt AND the CPU fallback
   threw `OrtException: ORT_NOT_IMPLEMENTED — Could not find an implementation for
   ConvInteger(10)`. This particular quantization export format (dynamic/QOperator-style,
   producing `ConvInteger`/`MatMulInteger` ops) isn't supported by this ORT build's kernel set at
   all, on any execution provider. **Fix:** switched to the fp32 export (`model.onnx` from the
   same HF repo, renamed to `wav2vec2_base_960h_fp32.onnx`), which has zero quantized ops
   (verified via `python -c "import onnx; ..."`, checked `op_type` set contains nothing with
   `Integer`/`QLinear`/`Quant` in the name). Also learned during this: the darwinn (Tensor NPU)
   NNAPI driver explicitly rejected int8 input too (`RET_CHECK failed: input.type !=
   TENSOR_FLOAT32`) — so fp32 wasn't just a CPU-compatibility fix, it's also what NNAPI itself
   wants for this graph.

2. **`OutOfMemoryError` loading the fp32 model.** After switching to fp32 (~378MB), the very
   first run threw `OutOfMemoryError: Failed to allocate a 377911904 byte allocation ... target
   footprint 536870912`. Root cause: `TaisWav2Vec2Aligner.getSession()` was calling
   `context.assets.open(MODEL_ASSET).use { it.readBytes() }` — reading the whole model into one
   contiguous Java `byte[]`. Android caps a single app's Java heap around ~512MB regardless of
   the phone's actual RAM; a single 378MB allocation (plus the transient ~2x overhead of
   `ByteArrayOutputStream`'s buffer-doubling while reading) blows straight through that, every
   time, deterministically. **Fix:** added `ensureModelFileOnDisk()` — copies the asset to
   `context.filesDir` once via a streamed `copyTo` (small fixed buffer, no big allocation), then
   calls `env.createSession(modelPath: String, options)` (a real ORT API — confirmed via ORT's
   own Javadoc) instead of the `byte[]` overload. Loading by path lets ONNX Runtime's native code
   memory-map/allocate the model off the Java heap entirely, no size ceiling. This is now
   correct — **do not revert to loading via `byte[]` or `ByteBuffer` for this model**, it will
   OOM again regardless of device RAM.

   Side note on this bug: because it happened *inside* the per-chunk `runCatching` in
   `TaisLyricsAligner`, every chunk failed identically and silently, the WorkManager job still
   reported `Result.success()`, and the `fillGaps` safety net produced lyrics where every word
   was interpolated to the same time (in the worst observed case, literally every word at 0ms).
   The `alignmentStateFor()` "looks degenerate" guard (checks if *all* words across every
   non-blank line are exactly `time == 0`) is what saved this from being silently trusted forever
   — it correctly re-flagged the song as still needing alignment on the next check. If you see a
   song whose lyrics look word-synced but every word fires at the same instant, that guard is
   telling you something failed silently upstream — go check `TaisWav2Vec2Aligner`/
   `TaisLyricsAligner`'s Timber logs (tag `TaisWav2Vec2Aligner` / `TaisLyricsAligner`) for a
   swallowed exception, don't just trust "Worker result SUCCESS" in the WorkManager logs.

3. **Lyrics screen visually "washed out"/broken-looking after a real word-synced result — fix
   made, NOT yet verified.** After bug #2's fix, a real successful alignment run (song "Noble")
   produced correct-looking data (`Worker result SUCCESS`, no errors in the log), but the lyrics
   screen then looked broken: every line except the currently-playing one was blurred and dimmed
   to near-invisibility. Traced this to **pre-existing** UI code in `LyricsSheet.kt` (not
   something this session's aligner rewrite touched) — a "fisheye" effect that dims/blurs lines
   by distance from the current line, using defaults (`unhighlightedColor` alpha 0.45,
   `targetAlpha` floor 0.3 for any line >1 away, blur strength 2.5dp/line capped at 10dp) that
   compound multiplicatively to ~13.5% opacity plus heavy blur for most of the visible list. This
   apparently was **always this aggressive** — it just never really engaged before, because
   Hoa's library never had reliable enough per-line timing to make `resolveCurrentLineIndex`
   correctly track a "current" line (confirmed: songs Hoa describes as having "unsynced lyrics"
   render fine — likely because with no real timing, `distanceFromCurrent` falls back to a
   uniform value for every line instead of one bright line against a sea of near-invisible ones).
   Now that this session's forced-alignment finally produces real, trustworthy timestamps, the
   spotlight effect is finally engaging as coded for probably the first time, and it turns out
   too extreme.

   **Fix applied (tune the constants, don't touch the alignment/data logic):**
   - `LyricsSheet.kt`: `unhighlightedColor` alpha `0.45f` → `0.6f`; `targetAlpha` `0 -> 1.0f, 1 ->
     0.6f, else -> 0.3f` → `0 -> 1.0f, 1 -> 0.75f, else -> 0.55f`; blur `coerceAtMost(10f)` →
     `coerceAtMost(4f)`; `animatedLyricsBlurStrength` default `2.5f` → `1.2f` (four occurrences:
     the inline DataStore-read fallback and its `collectAsStateWithLifecycle(initialValue=...)`,
     plus two Compose function-parameter defaults).
   - `UserPreferencesRepository.kt`: `animatedLyricsBlurStrengthFlow`'s `?: 2.5f` fallback →
     `?: 1.2f` (same DataStore key `animated_lyrics_blur_strength` as LyricsSheet.kt's own read —
     they must agree or the Settings slider shows a different "default" than what's actually
     rendered).
   - `SettingsViewModel.kt`: `SettingsUiState.animatedLyricsBlurStrength` default `2.5f` →
     `1.2f` (the value shown before the DataStore flow first emits).

   **This has not been rebuilt or looked at on-device.** That's the very next thing to do.

## Immediate next step

1. Have Hoa (or however you drive builds) run:
   ```
   cd "C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master"
   .\gradlew.bat :app:assembleDebug --no-daemon
   ```
   (Android Studio closed first.)
2. Install: `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
3. Open the lyrics screen for "Noble" (already has real word-sync data persisted from this
   session's successful run — no need to re-run Sync Lyrics unless you want to test the pipeline
   itself again) and confirm non-current lines are now legible, not a blurred-out haze.
4. If it still looks wrong, the four constants above are the knobs — nothing else in the
   rendering path should need to change for this specific complaint.

## Open items / things I did not get to

- **Never confirmed NNAPI is actually accelerating anything.** The debug log line
  `"wav2vec2 forced-aligner loaded with NNAPI execution provider"` (in
  `TaisWav2Vec2Aligner.getSession()`) should fire on success; I never caught it in the logcat
  ring buffer in this session (very chatty device, buffer rotates fast — use `adb logcat -d -s
  TaisWav2Vec2Aligner:V` right after a run, don't wait). It's entirely possible NNAPI is silently
  accepting the session but partitioning most ops back to CPU anyway (common for transformer
  graphs — NNAPI's op coverage for LayerNorm/GELU/attention patterns is historically weak). If
  the goal is genuinely "use the NPU," this needs real verification, not just "it didn't throw."
- **Never verified alignment *accuracy***, only that a run completes without error/OOM/garbage
  timestamps. The actual point of tonight's work was fixing lines being skipped — that needs a
  real listen-through with the lyrics screen open, checking words highlight in sync with what's
  sung, not just that *a* timestamp got assigned to every word.
- **Unrelated pre-existing bug, not touched:** some Spotify-imported tracks never get matched to
  a YouTube video at all (`SpotifyMatchWorker` never queued for them — confirmed via `adb shell
  dumpsys jobscheduler`, nothing scheduled). This breaks both normal playback (`ExoPlaybackException:
  Source error` / HTTP 404, since `SpotifyStreamProxy` has nothing to serve) and Sync Lyrics
  (`TaisStudioWorker` fails fast with "Couldn't download this song for local processing") for
  those specific tracks. Confirmed this is a separate issue from tonight's work by testing on a
  song ("Feel It") that fails the same way with zero relation to the aligner rewrite. Worth
  investigating separately: why isn't the Spotify→YouTube matching worker running for some
  imported tracks?
- **Model asset is ~378MB and lives directly in `assets/`, uncompressed** (`noCompress.add
  ("onnx")`). This is a large binary in the repo/APK. Hoa said size doesn't matter, but flagging
  it in case a future size-conscious pass wants to explore a smaller/quantized model *that
  actually has kernel support* (the int8 export tried this session did not — see bug #1 — but a
  different quantization scheme, e.g. QDQ/QLinear-based rather than ConvInteger-based, might work
  and wasn't tried).

## Environment gotchas learned this session (useful regardless of what you work on next)

- Gradle can't run from a sandboxed Bash tool here — `java.io.IOException: Unable to establish
  loopback connection` on every invocation, unfixable from inside the sandbox. All builds go
  through the user's own PowerShell.
- Command-line Gradle builds fail with `AccessDeniedException`/file-lock errors if **Android
  Studio is open** — its file watcher holds locks on the exact resource directories Gradle needs
  to write. Close it first.
- If you're driving `adb` from a Git-Bash-style shell (MSYS), a leading single `/` in a device
  path (e.g. `/sdcard/foo.png`) gets silently rewritten to a Windows host path by MSYS's
  path-conversion, breaking the command with a confusing "No such file or directory". Fix: use a
  doubled leading slash (`//sdcard/foo.png`) or set `MSYS_NO_PATHCONV=1` for that command.
- Pulling the Room SQLite DB off-device via `adb shell run-as ... cat databases/pixelplay_database
  > local.db` for direct inspection did **not** work cleanly — WAL mode means the main DB file
  alone is often in an inconsistent state mid-read, and pulling the `-shm` file alongside it also
  didn't help (`sqlite3.DatabaseError: database disk image is malformed` either way). Didn't find
  a clean solution this session; if you need to inspect persisted data directly, either find a
  way to force a WAL checkpoint before pulling, or add a debug-only way to dump state from inside
  the app instead.
- The device's `adb logcat` ring buffer is very chatty (media-session/notification spam
  dominates) and rotates fast — a specific tag's log line from even ~1-2 minutes ago can already
  be gone from a generically-sized `-t N` dump. Use `adb logcat -d -s <TAG>:V` (tag-only silent
  filter) rather than relying on line-count windows when you need to find something specific
  after the fact.
