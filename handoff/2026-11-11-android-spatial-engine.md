# Android Spatial audio: ENGINE handoff (2026-11-11)

Branch `audio-engine`, cut from `origin/audio-hq` 50cf187. Last pushed commit: see the report (`git log -1 origin/audio-engine`).
Contract files untouched. Everything below follows `handoff/2026-11-10-plans/cloud-audio-spec.md` (spec §N) and
`android-integration-map.md` (map N); where it does not, it says so under "Divergences".

## What is done

| Piece | Where |
|---|---|
| Table parser (kotlinx.serialization, once, off the main thread, primitive arrays) | `data/spatial/playback/SpatialPreviewTables.kt` |
| DSP of spec §7 (crossover, FIRs, sparse room, low band, gain, 80 ms wet/dry ramps) | `data/spatial/playback/SpatialStageKernel.kt` |
| Media3 `BaseAudioProcessor`, per-item bypass in `onFlush(StreamMetadata)` | `data/spatial/playback/SpatialStageProcessor.kt` |
| Shared stage parameters, table loading, the "which item is a render" registry, stream reports | `data/spatial/playback/SpatialStage.kt` |
| Render a song starts on (DAO video id + `SpatialLibrary.localRender`) | `data/spatial/playback/SpatialStartResolver.kt` |
| Route monitor on raw `AudioDeviceInfo` types | `data/spatial/playback/SpatialRouteMonitor.kt` |
| Pure rules: route, level, start render, 20 s rule, swap plan, media-time maths, `SpatialAudible` | `data/spatial/playback/SpatialRules.kt` |
| `SpatialPlayback` implementation (bound in `SpatialPlaybackModule`) | `data/spatial/playback/RealSpatialPlayback.kt` |
| Mid-song swap to and from a render (generalised Sing shadow player) | `data/service/player/SpatialRenditionSwap.kt`, numbers in `RenditionSwapTiming` (`CrossfadeRamp.kt`) |
| Chain, offload, float output, one resolver per media period, render in `resolveCloudUri` | `data/service/player/DualPlayerEngine.kt` |
| Level lift on the normalisation gain | `data/service/ReplayGainProcessor.kt` |
| Service hooks; Sing reports its instrumental | `data/service/MusicService.kt`, `InstrumentalCrossfadeController.kt` (one observer callback) |

How it plays:

- **The stage** is the last processor of every player's chain (after the haptics tee). 44.1 and 48 kHz stereo, 16-bit
  (what the chain gets with float output off) or float; anything else is left out of the pipeline for that stream.
  Until a profile is first selected it is a plain copy (no delay, no maths). The two FIRs run on the sum and the
  difference of the ears (the head is symmetric: `(direct ± cross) / 2`), tap-major over blocks of 512 with mirrored
  rings, so a frame is about 550 multiply-adds and every inner loop is a plain indexed loop with no dependency
  between frames. No allocation while processing (rings sized once from the whole table).
- **Per item**: a flush that follows a `configure` is a new stream; its item is looked up in the timeline and the
  registry says whether it plays a render (`spotify://<id>` → what that id's data source chose; a baked `file://` →
  whether the resolver handed it out as a render). A render item is bypassed from its first sample; the next stream
  item is spatialized again. A flush without a `configure` is a seek: the history starts from zero. An item boundary at
  the same rate keeps the history (gapless stays gapless) and ramps a changed decision in 10 ms.
- **Start of a song**: the resolver is one object per data source, i.e. per media period. Its first open asks
  `SpatialStartResolver` (Spatial active + matched video id + `localRender` + the file exists) and pins the answer for
  every later open of that item (seeks, retries): map 2.6 trap 1. `resolveCloudUri` answers the render too, so the
  crossfade player, a restored queue and the watch start on it as well; the UI's pre-play call only probes.
- **Mid-song** (`RealSpatialPlayback.reconcile`): when the wanted profile's render is on the phone (RenderReady, a profile
  picked mid-song, Spatial active again) and at least 20 s are left with no seek (1 s settle) or transition in flight,
  `SpatialRenditionSwap` builds a muted second player on the FLAC, lines it up by media time (positions in ms, so any
  sample rate), tolerance 20 ms within 400 ms, then a 250 ms linear crossfade. It mirrors seeks, speed, play/pause,
  resyncs every 4 s past 200 ms of drift, follows engine rebuilds and next-song overlaps. A seek in flight retries
  once it settled. Going back (Spatial off, route, Sing, another profile with no render) is the same fade the other way.
- **Offload** is off while a profile is selected (one short gap when picked on an offloaded song; back with Off).
  **Float output** (Hi-Fi) is off while a profile is selected; the players are rebuilt only when that changes the
  effective float flag (Hi-Fi on). The offload stall fallback stands down while a profile is selected.
- **Sing**: "Sing's instrumental mode is on" = `InstrumentalCrossfadeController.isCrossfadedToInstrumental` (now
  observed through a callback). It blocks Spatial (`SpatialBlock.SING`, the stage goes dry). Before a Sing fade to the
  instrumental the service fades a render on the second player back first (one owner of the main volume at a time);
  before a fade back to the vocals the stage is switched on first, so the vocals return spatial. Sing's numbers, paths
  and tests are unchanged.
- **Level** (spec §6.3): `ReplayGainProcessor.setPlayerVolume` lifts only a normalisation gain, `min(1, gain ×
  10^(headroomDb/20))`, headroom from the render's manifest or the table; the in-app volume is never lifted; a change
  ramps over 80 ms (five 16 ms steps). Streamed songs have no normalisation gain today, so this changes nothing yet:
  Spatial plays 4 dB under stereo, as the spec expects.
- **Route**: wired headset/headphones, Bluetooth A2DP, BLE headset, BLE speaker, USB headset. Not: phone, line out,
  USB device/accessory, HDMI, SCO, hearing aids, LE broadcast. Cast and Spotify Connect fail the headphones rule
  (`ROUTE`); with `spatial.route = always` they report `REMOTE`.
- **SpatialAudible**: `CLOUD_RENDER` (main player started on a render, or the second player's), `ON_DEVICE` (a profile,
  nothing blocks, table loaded), else `STEREO` with the selected profile and the first block of ROUTE, SING, REMOTE,
  FORMAT. Before the stage reports a stream, the resolver's registry answers for the current item.
- **Library**: `request(songId, videoId, profile)` once per song and profile when Spatial is active (FORMAT included)
  and the song plays no render of that profile; `markPlayed` when the main player starts on a render and after every
  completed swap to one; `Removed` fades a render on the second player back and forgets registry entries. Works
  against `NoOpSpatialLibrary` (on-device stage only).

## Numbers

`SpatialStageBenchmarkTest` on the GitHub runner (x86-64, JDK 21), Concert Hall at 48 kHz (the longest room), wet:

| Run | DSP alone | Through the processor, 16-bit buffers of 1,024 frames |
|---|---|---|
| 43067407001 (DSP measured first, still warming up) | 199.1 ns/frame | 124.1 ns/frame |
| 43069152149 (both warmed up first) | 114.6 ns/frame | 118.1 ns/frame |

Under the 250 ns/frame bar, so the FIRs stay time-domain (no FFT, no added latency): about 5.7 ms of CPU per second
of 48 kHz audio on the runner. Nothing was measured on a phone (ART on ARM64 is expected to be slower; the inner loops
have no dependency between frames and the JIT may vectorise them). The test prints the numbers (they are in the test
report) and fails only above 2.5 µs/frame. Bypassed (a render item, a blocked route, Sing) the stage runs only the
crossover and the delay lines; with no profile ever selected it is a buffer copy; under offload it does not run.

## Divergences and decisions (please check)

1. **A song that started on a render finishes on it** when Spatial goes off or the route fails (spec §6.3 allows it:
   "finishes the song and the next song is normal"). A render that arrived mid-song (second player) does go back to
   the stream. Changing profile while the main player itself plays a render puts the new profile's local render on
   the second player when there is one; when there is none the song finishes on the old render (the spec asks for
   "back to the stream with the on-device stage", which needs the stream on a second engine-built player; not done).
2. **Offload and float output follow "a profile is selected"**, not "Spatial is active" (map 9 item 3): headphones in
   or out and Sing never rebuild the players or cost a gap. Cost: with Hi-Fi on, a selected profile keeps float off
   on speakers too.
3. **Gapless**: the stage keeps its history across an item boundary at the same rate (resetting there would put
   `lowDelay` samples of silence between gapless songs). It resets on seeks and rate changes.
4. **No "quiet moment"** for the swap: the service has no gap information (map 2.7); it fades as soon as aligned.
5. **Alignment tolerance 20 ms** (Sing's is 40 ms): two spatial renderings of the same song overlap for 250 ms.
6. **Mid-song swaps also cover a local render found at song start** when the item had already been resolved to the
   stream (its next-item preload ran before the render arrived, or Sing or the route blocked Spatial then).
7. **Known limitation: a preloaded next song keeps its decision.** Its source is opened up to a minute or two before
   it plays; if Spatial is switched off or the profile changes after that, that one song may still start on the old
   render (heard spatial, without the stage). Fixing it means dropping the preloaded period on a settings change.
8. **Route monitor** is the engine's own (registered only while a profile is selected and the service runs), not
   `ConnectivityStateHolder`, whose callbacks live and die with the UI's ViewModel. `ConnectivityStateHolder.kt` and
   `AudioOutputCategory.kt` are untouched (`legacyMediaRouteIndex` is reused).
9. **Virtualizer**: already forced off by the service at start (the equalizer lock); nothing added. If that lock is
   lifted, the virtualizer must stay off while a profile is selected (`EqualizerManager` is not ENGINE's file).
10. The stage writes 16-bit with saturation and float clamped to ±1 (spec §7); no other limiter.

## For the other roles and the lead

- **CLOUD**: `localRender` is called (a) on ExoPlayer's loader thread inside `runBlocking` at the first open of every
  `spotify://` item while Spatial is active, and on the UI's pre-play probe, and (b) from the service via
  `Dispatchers.IO` mid-song. Memory or one file check only. The engine checks `videoId`, `profile` and
  `file.isFile` itself and does not parse file names. Emit `RenderReady` only for a complete, verified file, and
  `Removed` before (or right after) deleting files: a render on the second player fades back on it.
- **CLOUD**: the listening cache still copies the stream of a song that plays its render (harmless, wasteful);
  skipping it would need `MusicService.updateListeningCache` to ask the engine. Say if you want it.
- **UI**: `SpatialAudible.videoId` is null until the matched id is read (and on the very first play of a song, until its
  stream resolved). While a render plays, `rendition == CLOUD_RENDER` with the render's profile (file-info label).
- **Lead**: the contract held; no change needed. `SpatialPlayback.apply`'s doc ("a cloud render that is playing goes back
  to the stream") is true for the second player and "finishes the song" for a song that started on a render (item 1).

## Tests (JVM, `app/src/test/java/com/theveloper/pixelplay/data/spatial/playback/`)

- `SpatialStageGoldenTest`: every profile and rate against `spatial_preview_golden_v1.json` from a zero state (fails,
  never skips, when table and golden disagree), block boundaries, table shapes.
- `SpatialStageRampTest`: no output step larger than the signal's own through profile changes, bypass and off; the
  ramp lasts about 80 ms; bypass is the input delayed by `lowDelay` bit for bit; no profile is a plain copy; the dry
  delay changes only while it is not heard.
- `SpatialStageProcessorTest`: formats taken and left out; a render item bypassed from its start and the next stream
  item spatialized again; a seek keeps the item's decision; passthrough; 16-bit saturation.
- `SpatialRulesTest`: route rule, level rule and ramp, start-on-render rule, the registry's per-item rule, the 20 s
  rule and "in flight", the swap plan, media-time alignment across 44.1/48 kHz, `SpatialAudible`.
- `SpatialStageBenchmarkTest`: ns per frame (prints; fails only above 2.5 µs).

## CI

PLACEHOLDER_CI
