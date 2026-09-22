# Handoff — Remix Studio+ (September 22, 2026)

Written by Claude (Opus 5). Approved plan lives at
`C:\Users\Hoa\.claude\plans\let-s-build-remix-studio-kind-torvalds.md`.

Active tree: `C:\Users\Hoa\Downloads\Code Projects\PixelPlayer-master\beta2-release`
(note the user profile changed from `Hoa Vo` to `Hoa`; both paths currently resolve to the same
content, and git works there only after
`git config --global --add safe.directory '<path>'` — the folder is owned by a different Windows
user, `CodexSandboxOffline`.)

## What this is

An interactive spatial remixer: four stems placed in 3D around the listener on a draggable
top-down stage, a click-free region looper, tape speed, a swept filter, and an algorithmic room.
It replaces DJ Space, which has been deleted.

## The three constraints that shaped every decision

1. **Media3 cannot sample-align four sources.** Every existing "mix" in this app is N
   `AudioTrack`s summed by the platform: `DualPlayerEngine.performOverlapTransition`
   (`:1382-1456`) lands within ±125 ms; `InstrumentalCrossfadeController` (`:103-116`) resyncs on a
   200 ms tolerance. Stems of the *same song* that far apart comb-filter, and per-stem HRTF needs
   an inter-aural delay of **29 frames** at the extreme. Hence a dedicated engine.
2. **`Sensor.TYPE_HEAD_TRACKER` is closed to apps** — "typically not available for apps to use" in
   the platform javadoc; the buds' pose is consumed by the system spatializer. The app probes for
   it anyway (cheap, lights up free if that changes) and defaults to phone rotation.
3. **Nothing on-device produces 4 stems.** `TaisStemSeparator` is vocals+instrumental only. So the
   studio must be fully usable with **zero** cloud: it falls back to mid/side, which works on
   every song instantly.

## Architecture

`data/remix/` — all DSP is pure Kotlin with no Android imports, so it unit-tests at any block size
and can later render offline faster than real time.

```
per stem : LoopReader (tape rate + ITD taps) -> BinauralPanner -> gain -> [dry, reverb send]
shared   : StateVariableFilter -> bit-crush/decimate -> FdnReverb -> MasterLimiter -> AudioTrack
```

- `RemixAudioEngine` — `@Singleton`; one `AudioTrack` (PCM_FLOAT, device rate,
  `PERFORMANCE_MODE_LOW_LATENCY`), one thread at `THREAD_PRIORITY_URGENT_AUDIO`, blocking write as
  the clock. Ducks on transient focus loss rather than tearing down. **Foreground only** — no
  notification, no lock screen, like the old DJ decks. Exporting to WAV (not built) is how a remix
  becomes a normal backgroundable track.
- `RemixParams` → `RemixParamsSnapshot` — `@Volatile` ingress, snapshotted **once per block**.
  Reading a volatile twice inside a block can hand you two values mid-buffer; that is the same
  rule as `MidSideVocalProcessor.kt:65`.
- `ParamSmoother` — one-pole per block **plus** a per-sample linear ramp. The ramp is the part
  that kills zipper noise; the one-pole alone still steps once per block.
- `LoopReader` — the seam is crossfaded into **the audio immediately preceding the loop start**,
  not into the loop's own beginning, so the blend is equal-power *and* plausible. That is why
  every buffer carries a guard of `xfade + maxITD + margin` frames on both sides.
- `BinauralPanner` — parametric (Woodworth ITD, head shadow, elevation notch, distance + air, rear
  shelf), ~35 flops/stem/sample. `update()` **must** be paired with `endBlock()` or the smoothers
  never commit — this silently produced a zero ITD in the first test run.
- `FdnReverb` — 4 prime delay lines, Householder feedback, damping, 2 input allpasses, ~44 KB.

Memory: the loop region is resident, mono, deinterleaved — ~5 MiB per stem for 30 s. Full-song
residency for four stems would be 339 MB, which is why the region is capped.

## Stem precedence (what makes it work on day one)

| Available | Result |
|---|---|
| `<id>_stem_{vocals,drums,bass,other}.wav` | four pucks |
| `_instrumental.wav` / `_hq_roformer_inst.wav` | instrumental + `mix − instrumental` (sample-exact) |
| nothing | `center = (L+R)/2`, `sides = (L−R)/2` — no model, no network, no waiting |

New stem files use a `_stem_<kind>.wav` suffix that deliberately does **not** match
`TaisInstrumentalIndex.stemSongId()`, so the existing index is untouched.

## Cloud: the separation server

A **Runpod pod** runs Demucs `htdemucs` behind a small job API
(`tools/runpod/stem_server.py`, deployed with `tools/runpod/deploy_stem_server.sh`).

- `POST /run` (multipart `file`) → `GET /status/{id}` → `GET /download/{id}/{stem}`; Bearer token
  on everything except `/health`.
- Job-id polling, **not** one long POST: `DirectPostStemApiClient.kt:25-33` documents that the
  single-request approach dies at ~5m40s to NAT idle timeouts.
- Verified end-to-end against the live pod: four stereo 16-bit 44.1 kHz WAVs.

Four traps already paid for:
1. `pkill -f stem_server` matches the SSH command line containing that string and kills its own
   shell. Bracket it: `pkill -f '[s]tem_server'`.
2. The server must launch demucs via `sys.executable`; `"python"` resolves to the image's system
   python, which has no demucs.
3. Runpod's proxy **403s python-urllib's default user-agent**. curl and OkHttp are fine.
4. The pod has **no network volume**: the container disk is wiped on stop/restart, so the whole
   install disappears. Redeploy with the script (~10 min).

Client/worker: `data/remix/cloud/RemixStemJobClient` (plain `@Singleton`, uniform sealed returns —
the previous attempt at this protocol was abandoned over a KSP/Hilt failure) and
`data/worker/RemixStemSeparationWorker` (foreground, per-song unique work).
Config is in DataStore (`remix_backend_url`, `remix_backend_token`, `remix_upload_consent`),
edited **in the studio screen**, not in Settings — `SettingsViewModel` builds its state from two
positional indexed `combine`s, and inserting a flow there silently shifts every later index.

## Removed

`MashupScreen.kt`, `MashupViewModel.kt`, `exts/DeckController.kt` (634 lines), `Screen.DJSpace`,
its nav entry, and all `mashup_*` strings. The Home quick action and options sheet now point at
`Screen.RemixStudio`. This removed the app's only `PlaybackParameters` speed control; Remix
Studio's tape replaces it.

## State of play

- Engine, loader, pose sources, ViewModel, stage/waveform/control UI, nav, cloud client and worker
  are all written and compiling.
- Unit tests: `LoopReaderTest` (seam continuity at 0.6–1.4×), `RemixStateTest` (clamps hostile
  input), `RemixDspTest` (smoother, filters, reverb, limiter, panner geometry).
- **Never run on a device.** Nothing here has made a sound yet.

## Next, roughly by value

1. **Run it on a phone.** Check for underruns in logcat (the engine logs them), confirm dragging is
   smooth and the seam is silent.
2. Wire the studio's separation button end-to-end against the pod with a real track.
3. Export-to-WAV — buys back background playback, notification, lock screen, Android Auto.
4. Dual-deck smart transition (two `RemixGraph`s; trivially sample-aligned in-process).
5. AI Auto-Vibe: analysis → server → `RemixState.sanitized()` → animated apply. The sanitiser is
   already the security boundary and is already tested.
