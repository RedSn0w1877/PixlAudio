# Android integration map: Spatial audio (2026-11-11)

For the three agents that build the feature in parallel: **ENGINE** (on-device stage, playback, rendition
switching, route and level rules), **CLOUD** (library client, jobs, local store, background queue, caps) and **UI**
(player sheet, settings, strings). The contract is [`cloud-audio-spec.md`](cloud-audio-spec.md) ("spec §N" below).
This file says where everything is in the Android code, what is already there for you, and who may edit what.

- Written on branch `audio-foundation`. **Line numbers are those of that branch's head.** `MusicService.kt` already
  includes the foundation's own 13 lines, so its numbers are 4 to 13 higher than on `audio-hq`/`main`.
- **The spec changed twice on 2026-11-11 and this file follows it as of `audio-hq` 5eb5222.** First it was settled
  (stored forms, two switches, money, slots, timeout, status lines, level note); then **the owner cut Enhanced
  audio**: no restoration, no `enhance` task, no `enhanced.*` object, no `enhanced.enabled` setting, and "code or
  contract types that still carry any of those names are to be removed, not left dormant". The contract code on
  this branch has none of it. The on-device tables were regenerated twice the same day; this branch has the
  newest. `handoff/2026-11-11-spatial-roadmap.md` (also here) explains the whole pipeline; the spec rules.
  This file says "settled" where the spec answers something that was open when it was first written.
- **Before you start, check that `audio-hq` has not moved again**: `git log --oneline -3 origin/audio-hq` should
  start at 5eb5222. If it does not, the spec or the tables may be newer than this file.
- `Name.kt:120` is a line; `120-140` a range. Paths without a root are under
  `app/src/main/java/com/theveloper/pixelplay/`.
- "Verified" means read in the code or in the library's class files. Nothing here was run on a phone.
- Section 8 is the file ownership table. Read it before you touch a file that already exists.

## 0. The five things that will bite you

1. **Hi-Fi mode switches the whole processor chain off.** With float output on, Media3 1.11.1 leaves the custom
   `AudioProcessor`s out of the pipeline (2.4). The on-device stage needs float output off.
2. **What `resolveCloudUri` answers must not change during one item.** It runs again on every seek that re-opens
   the source; switching from the stream to a FLAC render mid-item feeds the wrong bytes to an extractor that is
   already chosen (2.6, trap 1).
3. **The exact per-item hook exists and is public API:** `AudioProcessor.flush(StreamMetadata)` carries the timeline
   and the period of the stream the processor is about to get (2.3). No renderer subclass is needed.
4. **Sing's shadow player is a plain ExoPlayer**: no processor chain, no `spotify://` resolver, aligned by position
   to within about 40 ms, fades clamped to 300-700 ms (2.7). The rendition swap needs its own numbers (spec §6.3,
   settled: about 250 ms, a new 20 s rule, and Sing's own behaviour must not change).
5. **Cloud Studio's money rules are Sing's**: one shared monthly cap hard-limited to $3 with the built-in keys, one
   cost estimate, and "has an instrumental" as the meaning of done (3.4, 3.2). Library jobs need their own monthly
   sum and their own cap (spec §6.1 "Money", settled): neither counts against the other.

## 1. What the foundation branch already gives you

Everything compiles and is green in CI (section 7). All of it is behind no-ops: listeners hear no difference yet.

| File | What |
|---|---|
| `data/spatial/SpatialProfile.kt` | `SpatialProfile` (ids `studio`, `wide`, `intimate`, `hall`, `surround`, `night`; `OFF_ID`, `DEFAULT`, `fromId`) and `SpatialContract` (`LIB_V = 1`, `ENGINE_ID`, `PREVIEW_ASSET`, `PREVIEW_SCHEMA`, `PREVIEW_VERSION`). |
| `data/spatial/SpatialSettings.kt` | `SpatialRouteMode` and the immutable `SpatialSettings` snapshot (spec §6.1), with `storageBytes`, `monthlyCapMicroUsd`, `defaults(builtInCloudKeys)`, and the limits of the stored forms (`MIN/MAX_STORAGE_MB` 256..65536, `MIN/MAX_DAILY_AHEAD` 1..100, `clampStorageMb`, `clampDailyAhead`). |
| `data/spatial/SpatialSettingsRepository.kt` | The settings in the shared `settings` DataStore under the spec's key names and stored forms. The only writer. `settings: Flow`, `current()`, `defaults`, one setter per key. |
| `data/spatial/SpatialRendition.kt` | `SpatialRenditionStatus` (spec §6.1 states plus `DOWNLOADING`; `shown(library, onDeviceAudible)`, `isInFlight`), `SpatialRendition`, `SpatialBlock`, `SpatialAudible`, `SpatialRender`, `SpatialLibraryStats`, `SpatialLibraryEvent`, `SpatialStatusLine` (one case per row of spec §6.5's table, + `of(...)`). |
| `data/spatial/SpatialLibrary.kt` | The seam CLOUD implements (`cloudRendersOn`, `localRender`, `request`, `status`, `events`, `stats`, `markPlayed`, `removeAll`), and `NoOpSpatialLibrary`. |
| `data/spatial/SpatialPlayback.kt` | The seam ENGINE implements, and `NoOpSpatialPlayback`. |
| `data/spatial/SpatialLibraryModule.kt` | Hilt: `provideSpatialLibrary()`. **CLOUD's file.** |
| `data/spatial/SpatialPlaybackModule.kt` | Hilt: `provideSpatialPlayback()`. **ENGINE's file.** |
| `presentation/viewmodel/SpatialAudioStateHolder.kt` | `@Singleton`, what the UI reads and calls. |
| `presentation/viewmodel/SpatialAudioViewModel.kt` | `@HiltViewModel` with one property, `spatial`: how a composable reaches the holder. |
| `data/service/MusicService.kt:235-238, 676-683` | The service collects `SpatialSettingsRepository.settings` and calls `SpatialPlayback.apply(settings)` (main thread). |
| `app/src/main/assets/spatial_preview_v1.json` | The on-device table, byte-identical to the worker's newest one (git blob `6c82e1ab`, 73,286 bytes; the same blob as in `handoff/…/spatial/` and in the iOS repo's worker). |
| `app/src/test/resources/spatial/spatial_preview_golden_v1.json` | The golden file that belongs to it (blob `3b994cd4`, 268,435 bytes). Its `schema` is `pixl.spatial.preview.golden`, `v` 1. |
| `.gitattributes` | `spatial_preview*.json text eol=lf`: every copy stays LF on Windows too. |
| `app/compose_stability.conf` | `com.theveloper.pixelplay.data.spatial.**` is stable. |
| `app/src/test/java/.../data/spatial/SpatialFixtures.kt` | Test helper: `previewTable()`, `previewTableBytes()`, `golden()`, `goldenBytes()`. ENGINE's golden test reads both files through it. |

### 1.1 Who calls what

```
UI ──reads/calls──> SpatialAudioStateHolder ──writes──> SpatialSettingsRepository (DataStore)
                         │  reads                              │ settings flow
                         ├── SpatialPlayback.audible           ├──> MusicService ──> SpatialPlayback.apply(settings)   [ENGINE]
                         └── SpatialLibrary.status/stats/      └──> CLOUD reads it itself (cloud switch, caps, prepare ahead)
                             cloudRendersOn

ENGINE ──> SpatialLibrary.localRender(videoId, profile)       (on the load path, no network)
ENGINE ──> SpatialLibrary.request(songId, videoId, profile)   (a song plays with Spatial active and has no local render)
ENGINE ──> SpatialLibrary.markPlayed(videoId, profile)        (it started playing that render)
ENGINE <── SpatialLibrary.events   (RenderReady, Removed)
```

- The UI never calls `SpatialPlayback.apply` and never talks to the library directly. A setter on the holder only
  writes the setting; the service and the cloud side react to the stored value. So everything works the same when
  only the service is alive (Bluetooth button, Android Auto, Wear).
- `SpatialPlayback.audible` must carry the current item's `songId` and `videoId`: the holder asks
  `SpatialLibrary.status(videoId, profile)` with it for the status line.
- `SpatialLibrary.localRender` is called from ExoPlayer's loader thread inside a `runBlocking` (2.6). It must
  answer from memory or one file check. No network, no database scan.

### 1.2 What the contract takes from the settled spec, and what it still decides itself

From the settled spec (and the same as the iOS contract, `PixlModel/CloudAudio/` on the iOS `audio-foundation`):

1. **Stored forms** (§6.1): "No limit" for `cloudAudio.monthlyCapUSD` is `-1` (`NO_MONTHLY_CAP` in the
   repository), `null` in `SpatialSettings.monthlyCapUsd`, and any negative stored number reads as it;
   `cloudAudio.storageMB` is kept within 256..65536 and `cloudAudio.dailyAhead` within 1..100, on write and on read;
   a stored `spatial.route = always` is honoured whether or not Developer mode is on. Android stores the cap as an
   `Int` of whole dollars (iOS stores a number); the two stores never meet.
2. **Two switches** (§6.1): Cloud Studio's "Process songs in the cloud" (`CloudSettingsSnapshot.enabled`, the engine
   sends nothing while it is off, `CloudStudioEngine.kt:829-832`) is the master, `spatial.cloud` sits under it, and a
   library job, lookup or download needs both. `SpatialLibrary.cloudRendersOn: StateFlow<Boolean>` is the two
   together. **CLOUD implements it** (`CloudStudioSettings.settings` and `SpatialSettingsRepository.settings`); the
   no-op says false. With built-in keys both switches default to on.
3. **The status line has one case per row of §6.5's table** (`SpatialStatusLine`): `ON_PHONE_FOR_NOW`,
   `GETTING_CLOUD_VERSION`, `CLOUD_VERSION`, `ON_PHONE_ONLY`, `CLOUD_FAILED`, `HEADPHONES_ONLY`, `PAUSED_FOR_SING`,
   plus `NONE`. `SpatialStatusLine.of(settings, audible, status, cloudRendersOn)` checks them in the order iOS does:
   off or nothing loaded, the route, Sing, a cloud render, then the on-device cases.
4. **Spatial only** (the cut): seven settings, no `enhanced.enabled`; `request(songId, videoId, profile)` and
   `markPlayed(videoId, profile)` always take a profile; a job is `tasks: ["spatial"]` with one render entry; a
   render's diagnostics are source codec, stem mode and loudness (the manifest has no cutoff any more).
5. **Money** (§6.1): library jobs have their own monthly sum and cap. `SpatialLibraryStats` carries
   `spentThisMonthMicroUsd` and `sentToday` for Developer mode's rows; CLOUD fills them.

Decided here because the spec does not say (tell the lead if one is wrong):

6. **`DOWNLOADING` is a status of its own.** §6.1 lists `none, preview, queued, processing, ready, failed`; §6.5 has a
   row "render downloading" with its own line, so the library must be able to say it. `QUEUED` covers the
   shared-library check, preparing, uploading and waiting for a worker; `PROCESSING` is the GPU.
7. **`PREVIEW` is the player's fact, not the library's** (as on iOS): `SpatialLibrary.status` never returns it;
   `SpatialRenditionStatus.shown(library, onDeviceAudible)` turns the library's `NONE` into `PREVIEW` while the
   on-device stage plays, and the holder's `currentStatus` applies it. ENGINE does not write statuses.
8. **The on-device cases of the line**: cloud renders off → `ON_PHONE_ONLY`; `FAILED` → `CLOUD_FAILED`;
   `DOWNLOADING`, and `READY` while the stage still plays (the fade is next) → `GETTING_CLOUD_VERSION`; everything
   else (`NONE`, `PREVIEW`, `QUEUED`, `PROCESSING`) → `ON_PHONE_FOR_NOW`, which is iOS's rule. It means the line
   promises "about a minute" even when the library cannot start (the month's cap is reached, no allowed network):
   section 9, question 1.
9. **`SpatialBlock`**: `ROUTE` covers every output the headphones rule rejects, another device (Cast, Spotify
   Connect) included, and shows "Headphones only. Plug in to hear it."; `REMOTE` is only for another device while
   `spatial.route` is `always`; `FORMAT` is a rate the stage does not take. The last two have no row in §6.5 and
   show nothing. When several hold, ENGINE reports the first of `ROUTE`, `SING`, `REMOTE`, `FORMAT`.
10. **`markPlayed`** exists because the storage budget evicts least-recently-played first and only the player knows
    what played.
11. **`SpatialTablesTest` pins no generated number.** It checks that the app's two copies equal the handoff copies
    byte for byte, that none has a carriage return, and their shape. When the worker regenerates the pair, copy
    both to the three places (handoff, assets, test resources); no test needs editing.

## 2. Playback

### 2.1 Versions

- Media3 **1.11.1** (`gradle/libs.versions.toml:51-52`), plus `org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1`
  (`libs.versions.toml:167`, `app/build.gradle.kts:387`). Kotlin 2.4.20, Hilt 2.60.1, coroutines 1.11.0.
- Every Media3 statement below was read from the class files in the Gradle cache (`javap -p -c` on
  `media3-exoplayer-1.11.1.aar`, `media3-common-1.11.1.aar` and `media3-ffmpeg-decoder-1.9.0+1.aar`), not from
  memory of older versions.

### 2.2 How the players are built (`data/service/player/DualPlayerEngine.kt`, 1,814 lines)

- `@Singleton class DualPlayerEngine` (245). Master `playerA` (303), auxiliary `playerB` (304, made on demand by
  `getOrCreateAuxiliaryPlayer()` 1285-1291). **Both come from `buildPlayer()` (1117-1283), so each has its own
  renderers, its own `DefaultAudioSink` and its own processor instances.** Shared values reach the processors
  through a `@Volatile` field read by a lambda (see 2.5).
- The sink and the chain (1128-1146):

  ```kotlin
  return DefaultAudioSink.Builder(context)
      .setEnableFloatOutput(hiFiModeEnabled)
      .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
      .setAudioProcessorChain(
          DefaultAudioSink.DefaultAudioProcessorChain(
              HiResSampleRateCapAudioProcessor(),
              SurroundDownmixProcessor(),
              MidSideVocalProcessor { vocalAttenuation },
              // Analysis only (music haptics): passes the audio through unchanged.
              androidx.media3.exoplayer.audio.TeeAudioProcessor(musicHaptics.createPcmSink())
          )
      )
      .build()
  ```

  `DefaultAudioProcessorChain` appends Media3's own silence-skipping and Sonic (speed) processors after these.
  **Put the stage last in this list**, after the tee, so music haptics keep analysing the dry signal.
- Renderers: an anonymous `DefaultRenderersFactory` (1127) with video, text and camera-motion renderers removed
  (1148-1177), then `.setEnableAudioFloatOutput(hiFiModeEnabled)` (1178), a codec selector that sends only ALAC and
  MIDI to extension decoders (`AudioDecoderPolicy.kt:10-26`), `.setEnableDecoderFallback(true)`,
  `.setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)` (1181). So the audio renderers are
  `MediaCodecAudioRenderer` first, then MIDI and ffmpeg's `FfmpegAudioRenderer` (a `DecoderAudioRenderer`). They all
  share the one sink. FLAC, Opus and AAC go through `MediaCodecAudioRenderer`.
- Data: `ResolvingDataSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory()…), resolver)`
  (1188-1246) into `DefaultMediaSourceFactory(resolvingFactory, extractorsFactory)` (1260). See 2.6.
- The players are thrown away and rebuilt by `rebuildPlayersPreservingMasterState` (1003-1078) when Hi-Fi mode is
  toggled (`setHiFiMode` 1372-1380) or audio offload falls back (`disableAudioOffloadForSession` 987-1001). New
  processor instances each time; the service hears about it through the swap listeners (705-723).
- After a next-song crossfade the auxiliary player becomes the master: `playerA = incomingPlayer` (1661).
- A third kind of player exists outside the engine: Sing's shadow player (2.7).

### 2.3 The per-item hook: use `AudioProcessor.flush(StreamMetadata)`

The spec needs the on-device stage bypassed for an item that is already a spatial render, exactly at the item
boundary. **Recommendation: decide it inside the stage, in `onFlush(StreamMetadata)`.**

Verified in Media3 1.11.1:

- `AudioProcessor.StreamMetadata` has three public fields: `positionOffsetUs: long`, `timeline: Timeline`,
  `periodUid: Object`. `StreamMetadata.DEFAULT` has an empty timeline and a null uid.
- Both `MediaCodecAudioRenderer.onOutputFormatChanged` and `DecoderAudioRenderer` configure the sink with
  `new AudioSinkConfig.Builder(format).setOutputChannelMapping(…).setTimeline(getTimeline()).setMediaPeriodId(getMediaPeriodId()).build()`.
  This happens for the first output buffer of **every** stream, same format or not. `MediaCodecRenderer` keeps a
  `needToNotifyOutputFormatChangeAfterStreamChange` flag for exactly that. `DecoderAudioRenderer` gets the format
  again at the start of every stream (a new period's `SampleQueue` hands out its format first), its
  `canReuseDecoder` answers "no" by default and the Jellyfin `FfmpegAudioRenderer` (1.9.0+1) does not override it,
  so the decoder is re-created and `audioTrackNeedsConfigure` is set each time.
- `DefaultAudioSink.configure(AudioSinkConfig)` keeps `timeline` and `mediaPeriodId.periodUid` in its
  `Configuration` and, when an output already exists, parks it as `pendingConfiguration`.
- `DefaultAudioSink.handleBuffer`, with a pending configuration: `drainToEndOfStream()` first (it queues end of
  stream into the **old** pipeline and writes out all it returns; `handleBuffer` returns false until that is done),
  then takes the new configuration (reusing the AudioTrack when it can), then
  `applyAudioProcessorPlaybackParametersAndSkipSilence(pts)` → `setupAudioProcessors(pts)` →
  `audioProcessingPipeline.flush(new StreamMetadata(timeline, periodUid, positionOffsetUs))`.
- `AudioProcessingPipeline.flush(metadata)` calls `flush(metadata)` on **every** processor of the chain, active or
  not, and only **then** asks `isActive()` to build the list of processors that take part in this stream.
- `BaseAudioProcessor`: `configure` is final and calls `onConfigure`; `flush(StreamMetadata)` is final, makes the
  pending formats current, then calls `onFlush(StreamMetadata)` (which by default calls `onFlush()`); `isActive()`
  is open.
- A seek goes `DefaultAudioSink.flush()` → `resetSinkStateForFlush()` → `setupAudioProcessors(TIME_UNSET)`: the
  same configuration's timeline and uid again, offset 0.

So on the playback thread the order at a gapless boundary is: all of item A's PCM through `queueInput`, then
`queueEndOfStream` and the drain, then `flush(StreamMetadata of B)`, then B's first `queueInput`. A decision taken in
`onFlush` applies from B's first sample and to none of A's. That is sample-exact at the processor's input, with no
thread hand-off.

Reading the item (allocate `Timeline.Period` and `Timeline.Window` once, as fields):

```kotlin
override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
    val timeline = streamMetadata.timeline
    val uid = streamMetadata.periodUid
    val index = if (uid != null && !timeline.isEmpty) timeline.getIndexOfPeriod(uid) else C.INDEX_UNSET
    if (index != C.INDEX_UNSET) {
        val item = timeline.getWindow(timeline.getPeriod(index, period).windowIndex, window).mediaItem
        // item.mediaId is Song.id; the Spotify id is in mediaMetadata.extras (MediaItemBuilder.EXTERNAL_EXTRA_SPOTIFY_ID)
        // or in a spotify:// localConfiguration.uri; a baked file:// uri names the file itself (see below).
    } // else: keep the previous decision
    // reset FIR history, delay ring and biquad states here: a flush means a new stream or a seek
}
```

What to know before relying on it:

1. `getMediaPeriodId()` is `BaseRenderer`'s: the period of the stream being **read**. At an item's first output
   buffer that is the item itself, unless its whole stream was already read into the codec (only possible for clips
   of a second or so).
2. `StreamMetadata.DEFAULT`, or a uid no longer in the timeline (the queue was edited between `configure` and
   `flush`): `getIndexOfPeriod` returns -1. Keep the previous decision.
3. The master player's items are `spotify://<id>`; which file an item really plays is chosen by the resolver on
   the loader thread (2.6). The stage therefore needs a small thread-safe registry written at resolve time
   ("this song id plays the render of profile X"). The entry always exists before the item's first PCM, because
   the data had to be loaded first.
4. The **auxiliary** (crossfade) player and a **restored** current item carry the already-resolved URI in the
   MediaItem (`prepareNext` 1514-1526 bakes it; `MusicService.kt:2294` does the same for the restored queue's
   current item; `WearCommandReceiver.kt:215` too). A `file://…/cloud_audio/<videoId>/sp-<profile>.flac` there
   identifies the render directly, and such an item never reaches the resolver again.
5. The list of active processors is rebuilt only at a flush. To switch the stage on or off **during** an item
   (profile picked, headphones unplugged) the stage has to be in the chain and do its own wet/dry ramp.
   Returning `isActive() == false` from a flush is only for "not at all for this stream" (a rate that is not 44.1
   or 48 kHz, more than two channels); it saves the copy. Do not use it for the render bypass if a render item may
   have to go wet again mid-item.
6. `configure()` for the next item arrives while the tail of the current one may still sit in the output buffer.
   Extend `BaseAudioProcessor` (pending formats, `replaceOutputBuffer`). The three existing processors implement
   `AudioProcessor` directly and apply `configure` at once; do not copy that.
7. Not on the path at all: Hi-Fi float output and audio offload (2.4).

Alternatives that were looked at:

- **Subclass `MediaCodecAudioRenderer`** (through `DefaultRenderersFactory.buildAudioRenderers(context,
  extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out)`).
  Its protected hooks in 1.11.1: `onStreamChanged(Format[], long startPositionUs, long offsetUs, MediaPeriodId)`
  (the read side: early, by up to the whole buffer), `onProcessedStreamChange()` (the output side: the last buffer of
  the previous stream has been handed to the sink; it calls `audioSink.handleDiscontinuity()`),
  `onOutputFormatChanged(Format, MediaFormat)`, `onOutputStreamOffsetUsChanged(long)`. It works, but it must mirror
  `MediaCodecRenderer`'s private queue of pending stream changes, it does not cover the ffmpeg
  `DecoderAudioRenderer`, and it still needs a shared object to reach the processor. Not recommended.
- **`Player.Listener.onMediaItemTransition` / `onPositionDiscontinuity`** on the main thread: they follow the
  position that is being *heard*; the processors run ahead of that by the AudioTrack buffer. A flag flipped there
  lands tens to hundreds of milliseconds late in the processed audio. Not exact.
- **`ExoPlayer.createMessage(...).setPosition(...)`**: delivered on the playback thread, but again at the renderer
  position, not the processing position. Not exact.

### 2.4 Audio offload and Hi-Fi float mode

**Offload.** While offload is on, compressed audio goes to the audio HAL and no PCM processor runs.

- Default: `audioOffloadEnabled = !shouldDisableAudioOffloadByDefault()` (292). Off by default on Xiaomi-family
  phones from SDK 36, Google Pixel from SDK 37, some Lava/MediaTek phones (103-138). On a Pixel 10 Pro with SDK 36
  it is therefore **on**.
- `private val offloadAllowed get() = audioOffloadEnabled && exactTimingSessions == 0 && !pcmAnalysisActive` (1310).
  Applied when a player is built (1264-1278) and live, with no rebuild, by `applyOffloadMode(player, enabled)`
  (1320-1330: `trackSelectionParameters.buildUpon().setAudioOffloadPreferences(…)`), on both players.
- Two switches already force PCM: `setPcmAnalysisActive(active)` (1312-1318), owned by music haptics
  (`MusicService.kt:585`: `musicHaptics.attach(serviceScope) { wantsPcm -> engine.setPcmAnalysisActive(wantsPcm) }`,
  reset in `onDestroy`, `MusicService.kt:1970`), and exact-timing sessions for the lyrics sync editor (1332-1350).
- **ENGINE: add a third flag** to `offloadAllowed` (for example `spatialStageWanted`) with its own setter. Do not
  reuse `pcmAnalysisActive`: haptics writes `false` into it whenever haptics stop.
- Flipping the offload preference mid-song is **not silent when the song is really offloaded** (read in the
  1.11.1 class files, not measured on a phone): `DefaultTrackSelector.maybeConfigureRendererForOffload` gives the
  audio renderer its own `RendererConfiguration` while offload is preferred and the format supports it, so the new
  selection is not `isEquivalent` to the old one, the renderer gets a new stream and is disabled and enabled again,
  and `MediaCodecAudioRenderer.onDisabled` flushes the sink. Expect a short gap at the current position; its length
  was not measured. A song that was not offloaded is left alone. So turn offload off once, when a profile is
  selected (or at start-up with Spatial on), not at each song.
- `disableAudioOffloadForSession` (987-1001) rebuilds the players when the HAL stalls; it skips the rebuild while
  `exactTimingSessions > 0`. Consider the same guard for the new flag.

**Hi-Fi mode.** Preference `hi_fi_mode_enabled`, default **off** (`UserPreferencesRepository.kt:207, 484-489`), a
Developer mode switch (`SettingsCategoryScreen.kt:948-958`), collected at `MusicService.kt:664-668` into
`engine.setHiFiMode` (1372-1380, rebuilds both players). It sets `setEnableFloatOutput` (1134) and
`setEnableAudioFloatOutput` (1178).

Verified in the 1.11.1 class files:

```java
// DefaultAudioSink.configure(...), PCM input
pipelineProcessors.addAll(availableAudioProcessors);            // trimming, channel mapping
if (shouldUseFloatOutput(inputFormat.pcmEncoding)) {             // enableFloatOutput && isEncodingHighResolutionPcm
    pipelineProcessors.add(toFloatPcmAudioProcessor);           // the custom chain is NOT added
} else {
    pipelineProcessors.add(toInt16PcmAudioProcessor);
    pipelineProcessors.add(audioProcessorChain.getAudioProcessors());
}
```

and `MediaCodecAudioRenderer.getMediaFormat` asks the decoder for float PCM whenever the sink reports float as
directly supported, which `DefaultAudioSink.getFormatSupport` does exactly when `enableFloatOutput` is true.
`FfmpegAudioRenderer.shouldOutputFloat` follows the same answer (float for everything but AC-3).

So **with Hi-Fi mode on, a decoder that honours the request outputs float and none of the custom processors
run**: not `MidSideVocalProcessor`, not the haptics tee, and not a stage added to that chain. With it off, the
chain always gets 16-bit PCM (the 16-bit conversion runs before it), so the stage only needs a 16-bit path with
float maths inside and a saturating write (spec §7).

**ENGINE: build the sinks with float output off while a profile is selected**, for example
`val floatOutput = hiFiModeEnabled && !spatialStageWanted` at 1134 and 1178, with one
`rebuildPlayersPreservingMasterState` when it flips. Say in your handoff note that Spatial audio and Hi-Fi mode do
not combine (Hi-Fi is Developer mode only and off by default).

### 2.5 `MidSideVocalProcessor`: the precedent for a live parameter (`data/service/player/MidSideVocalProcessor.kt`)

- One instance per sink, built with a provider: `MidSideVocalProcessor { vocalAttenuation }`
  (`DualPlayerEngine.kt:1140`). `vocalAttenuation` is a `@Volatile private var` on the engine (291), written by
  `setVocalAttenuation` (1387-1389), which `MusicService.kt:670-674` feeds from a preference flow. Both players
  read the same field, and a rebuilt player picks it up without any re-wiring. **Copy this for the stage's
  settings** (one immutable, volatile parameter object: profile, wet/dry target, table).
- Per buffer it reads the provider once (65), writes into its **own** direct buffer that only grows
  (`ensureOutputBuffer`, 40-47) and consumes all input (`inputBuffer.position(inputBuffer.limit())`). The comment
  at 67-73 explains why it must never alias the input buffer (a native crash in `AudioTrack.write`).
- It has no ramp: a parameter change is a step. The stage needs the 80 ms wet/dry ramp of spec §7.
- Do not copy its per-sample `ByteBuffer.getShort()`/`repeat { }` loop for the stage. At 48 kHz the stage does
  about 26 million multiply-adds a second (two 192-tap FIRs and about 80 sparse taps per frame, per channel pair):
  read the buffer in bulk into a preallocated `ShortArray`/`FloatArray` and run plain indexed loops.
- The tee next to it (`TeeAudioProcessor(musicHaptics.createPcmSink())`, 1142; sink at
  `data/haptics/MusicHapticsEngine.kt:313`) is the precedent for analysis without touching the audio.

### 2.6 Source resolution: where a local render is returned

- Items: `MediaItemBuilder.build(song)` (`utils/MediaItemBuilder.kt:97-100`); a Spotify song's URI is its
  `contentUriString`, `spotify://<spotifyId>` (`data/spotify/SpotifyRepository.kt:1162`); `mediaId` is `Song.id`.
  Extras: `EXTERNAL_EXTRA_SPOTIFY_ID`, `EXTERNAL_EXTRA_FILE_PATH` (empty for Spotify songs),
  `EXTERNAL_EXTRA_DURATION` (`MediaItemBuilder.kt:88-93, 285-292`).
- The MediaSession rebuilds every item from its id, so a URI set by a client never reaches the player
  (`DualPlayerEngine.kt:1203-1208`, `MusicService.kt:3315-3325`). Everything goes through the resolver.
- `resolveDataSpec` (`DualPlayerEngine.kt:1189-1230`): only for the `spotify` scheme;
  `runBlocking { withTimeoutOrNull(25 s) { resolveCloudUri(uri) } }` on ExoPlayer's loader thread, on **every
  open** of the source: the first load, every seek outside the buffer, every retry, the next-item preload.
- `resolveCloudUri(uri)` (1442-1480): a complete local copy first (`audioCacheManager.getPlayableFile(spotifyId)`
  → `Uri.fromFile`, 1459-1461), else the loopback proxy (`spotifyStreamProxy.ensureReady()` + `resolveUri`,
  1471-1479: `http://127.0.0.1:<port>/spotify/<id>`).
- **This is where a render is returned**: before the `getPlayableFile` check, when Spatial is active, ask
  `SpatialLibrary.localRender(videoId, profile)` and return `Uri.fromFile(render.file)`.
- Other callers of `resolveCloudUri` / `resolveMediaItem`: the next-item pre-resolution (596-608, result thrown
  away), `triggerAdjacentPreResolution` (779-809), `prepareNext` (1514, **bakes** the answer into the auxiliary
  player's item), `MusicService.kt:2294` (queue restore, bakes it into the current item),
  `data/service/wear/WearCommandReceiver.kt:215` (bakes), `PlaybackDispatchStateHolder.kt:930` (the UI's pre-play
  call, thrown away).

**Trap 1: one answer per item.** A seek re-opens the source, and the extractor of that period is already chosen
(`BundledExtractorsAdapter.init` returns at once when it has one). If the first open returned the stream
(WebM/Opus) and a later one returns `sp-studio.flac` because the render arrived meanwhile, FLAC bytes are read at
a byte offset of another file by the Matroska extractor: a playback error. Switching to the listening cache
mid-item works today because that file is a copy of the same stream the proxy serves (the proxy even refuses a
changed itag mid-stream, `data/stream/CloudStreamProxy.kt:328-331`). So decide once per item instance and keep
that answer until the instance ends. One `DataSource` instance is one media period
(`ProgressiveMediaSource.createPeriod` calls `createDataSource()` once and the period reuses it for all its
loads), so a thin wrapper around the resolving source that remembers its first answer gives exactly that; write
the registry of 2.3 from the same place.

**Trap 2: probes are not plays.** The pre-play call and the pre-resolutions must not call `markPlayed`, must not
pin a decision, and must not start a `request`. Only the data-source path (or `onMediaItemTransition`) may.

**Trap 3: a render that arrives mid-song** is not handled here at all (trap 1): it is the shadow swap of 2.7.

**The listening cache** (`data/cache/AudioCacheManager.kt`): `getPlayableFile(spotifyId)` (122-130, complete files
only); automatic copies in `cacheDir/audio_cache` (57, at most 1 GB, 347), downloads in `filesDir/downloads` (58).
The files are the proxied stream's own container (`.webm`, `.m4a`, `.ogg`, `.mp3`, by content type, 339-344).
`downloadAndCache(proxy, spotifyId, isPermanent)` (181-242) is what Cloud Studio uses to get a song's audio. The
cache is keyed by Spotify id, not by video: after a re-match a cached file can be the old video's.

**The videoId.**

- Stored in `spotify_songs.matched_video_id` (`data/database/SpotifySongEntity.kt:54`).
- `SpotifyDao.getMatchedVideoId(spotifyId): String?` (`data/database/SpotifyDao.kt:219-220`, suspend; null until
  the song is matched).
- For the playing item: `spotifyIdOf(mediaItem)` (`MusicService.kt:1437-1441`: the extra first, then the
  `spotify://` URI), then the DAO. For a library song: `song.spotifyId`, then the DAO.
  `LiveCloudStudioHost.streamIdentity(song)` (`data/cloudstudio/LiveCloudStudioHost.kt:80-83`) is exactly that.
- Matching is lazy: a song's first play matches it inside `SpotifyStreamProxy.resolveStreamUrl`
  (`data/spotify/SpotifyStreamProxy.kt:138-163`). So the very first resolve of a never-played song has no videoId
  yet: play the stream.
- It can change: an unplayable video is re-matched once (`SpotifyStreamProxy.kt:222-238`), and the person can pick
  one by hand. A render keyed by the old video is then simply never found again.
- The pattern check exists: `CloudStreamSecurity.validateYouTubeVideoId` (`data/stream/CloudStreamSecurity.kt:20,
  44`), the spec's `^[A-Za-z0-9_-]{11}$`.

**Format and duration of a stream.**

- The resolver returns only `ResolvedStream(url, userAgent, strategyName, nTransformMs)`
  (`data/youtube/YouTubeStreamResolver.kt:16-22`). `YouTubeAudioFormat` (`data/youtube/InnerTubeClient.kt:29-48`:
  `itag`, `mimeType`, `bitrate`, `contentLength`, `approxDurationMs`, `isMuxedFallback`) is dropped after
  `pickBestAudio` (`YouTubeStreamResolver.kt:101, 441-466`).
- What survives is the googlevideo URL's own query (`itag`, `mime`, `clen`, `dur`, `expire`). The proxy reads
  `itag` and `clen` from it (`CloudStreamProxy.kt:258-261, 303`) but its URL cache is private (89, 285): **there is
  no accessor for the itag of a song's stream today.**
- While playing: `DualPlayerEngine.currentAudioFormatSnapshot()` (365-381: mime, sample rate, channels, PCM
  encoding, bitrate) and `PlaybackAudioMetadata` (`presentation/viewmodel/MediaControllerSyncStateHolder.kt:40-47,
  289-320`), which the full player's file-info label shows.
- For an upload: `LiveCloudAudioPreparer.inspect()` (`data/cloudstudio/LiveCloudAudioPreparer.kt:105-128`) knows the
  MIME and the rate but keeps them private; `CloudPreparedAudio` (`CloudStudioSeams.kt:64-76`) carries
  `sampleRate`, `frames`, `durationMs`; the worker reports `input.codec` back (`CloudJobSchema.kt:378-386`).
- **Settled (spec §6.2 step 3): `library.source` names the STREAM the audio came from (`itag`, `codec`), not the
  uploaded FLAC.** The worker only records it (§4), but the apps are to send it. Codec is easy (add it to
  `CloudPreparedAudio`); the itag needs a new accessor on the proxy, which is CLOUD's to add (section 8).
- Usual streams: itag 251 is Opus in WebM at 48 kHz; itag 140 is AAC-LC in MP4 at 44.1 kHz; the fallback itag 18
  is a muxed MP4 whose audio is 22 kHz, which the stage must bypass (spec §7).
- **A render's sample rate is the uploaded file's, not this phone's stream's** (spec §6.2 step 3, settled): 44.1 kHz
  when an iPhone made it, the stream's own rate when an Android phone did. So a render fetched from the shared
  library can be 44.1 kHz for a song this phone streams at 48 kHz. Line the two up by media time, never by frame
  count, and expect the sink to be reconfigured when an item starts on a render of another rate.

### 2.7 The Sing swap: `InstrumentalCrossfadeController` and `MusicService.applyStudioAudioOverride`

The path of a Sing tap:

`LyricsSingStateHolder` → `PlayerViewModel.switchToInstrumental` (`PlayerViewModel.kt:3128-3160`) →
`MediaController.sendStudioAudioCommand(args)` (`presentation/lyrics/sing/StudioAudioCommand.kt:23-35`) →
`MusicService.onCustomCommand`, `CUSTOM_COMMAND_SET_STUDIO_AUDIO_OVERRIDE` (`MusicService.kt:907-930`; offered to
controllers at 777) → `applyStudioAudioOverride(session, path, request, clip)` (3326-3365) → the controller.

- The command's extras are in `data/service/MusicNotificationProvider.kt:21-42`. `EXTRA_STUDIO_AUDIO_PATH` only
  means "not the vocals": the service never opens a path a client sent.
- **The file is hard-wired**: `TaisInstrumentalIndex.bestAvailableFile(context, songId)` in `prepareShadow`
  (`InstrumentalCrossfadeController.kt:174`) and in `swapClipToFull` (483).
- One controller per service instance (`MusicService.kt:261-273`).

API (`data/service/player/InstrumentalCrossfadeController.kt`, 770 lines):

| Member | Lines | What |
|---|---|---|
| constructor | 60-71 | `context`, `scope`, `audioSessionIdProvider`, `isTransitionRunning`, `incomingVolumeTarget`, `onClipLeft` |
| `onSongChanged(songId, mainPlayer)` | 131-139 | tears the shadow down; restores the main volume if it was silenced |
| `crossfadeToInstrumental(mainPlayer, request, clipSource)` | 293-365 | build shadow, wait ready, align, wait for `startAtPositionMs`, ramp |
| `crossfadeToOriginal(mainPlayer, durationMs)` | 368-369 | ramp back, free the shadow |
| `onMainSeek`, `onPlaybackParametersChanged`, `onPlayWhenReadyChanged` | 255-281 | the shadow mirrors the main player |
| `onMainVolumeChanged(player, volume): Boolean` | 631-656 | the service's **first** look at every volume change (`MusicService.kt:1664-1667`) |
| `onMainPlayerSwapped`, `onTransitionFinished` | 663-689 | engine rebuilds and next-song overlaps |
| `teardown()` | 691-708 | |
| `suspend(owner)` / `resume(owner)` | 749-757 | the lyrics sync editor forces the vocals back |
| `isCrossfadedToInstrumental`, `isRamping`, `ownsMasterVolume` | 110-119 | plain vars, main thread |

How it works, and what that means for a spatial render:

- **Shadow player** (`createPlayer`, 200-223): `ExoPlayer.Builder(context).build()`, no audio focus, same audio
  session id as the main player (211), `volume = 0`, `MediaItem.fromUri(Uri.fromFile(file))`. Default renderers
  and a default sink: **no custom processors, so a render played on the shadow is never spatialized twice, for
  free.** It also cannot open `spotify://` and has no stage, so it cannot play "the stream with the on-device
  stage".
- **Alignment** (`awaitReady` 561-572, `alignShadow` 581-605): it seeks the shadow to the main player's position,
  measures how far behind it landed and aims ahead by that much (`CrossfadeRamp.nextSeekLead`, at most 400 ms),
  until the two are within `ALIGN_TOLERANCE_MS = 40` or `ALIGN_BUDGET_MS = 250` is used up. A resync loop then
  re-seeks every 4 s when the drift exceeds 200 ms, never during a ramp (225-252). **This is position-based: tens
  of milliseconds, not samples.** That is what the settled spec asks for ("line them up by media time, never by
  frame count across files", §6.2), since the render may not even have the stream's sample rate. During a 250 ms
  overlap of the same song 40 ms apart you can still hear a short comb effect. If that is audible on the phone,
  tighten the tolerance for rendition swaps or shorten the overlap; say what you chose in your handoff note.
- **Ramps** (`data/service/player/CrossfadeRamp.kt:54-79`): linear, both volumes written every 16 ms from the wall
  clock. `clampFadeMs` forces 300..700 ms (26-28, 42). **The spec's "about 250 ms" is below `MIN_FADE_MS`**: give a
  rendition swap its own duration and do not pass it through `clampFadeMs`. `SingClipTimeline.SWAP_FADE_MS = 300`
  (`SingClipTimeline.kt:61`) is the existing instrumental-to-instrumental value. Settled (spec §6.3): "The existing
  Sing swap fades over 300-700 ms and has no 'time left' rule; for these switches the fade is about 250 ms and the
  20 s rule above is new. Sing's own behaviour must not change." So add the new numbers beside Sing's; do not edit
  `MIN_FADE_MS`, `clampFadeMs` or any path a Sing swap takes, and keep `CrossfadeRampTest` and the Sing tests green.
- **"At a quiet moment when the app can tell"**: `CrossfadeRequest.startAtPositionMs` and `maxWaitMs`
  (`CrossfadeRamp.kt:15-19`, `awaitPosition` 86-100) already wait for a planned position. Sing plans it from the
  gaps between sung words (`CrossfadePlan` in `LyricsSingStateHolder.kt:134`); that planner lives in the UI layer,
  so the service has no gap information of its own today.
- **Volume ownership**: while the alternate rendition is audible the main player's volume is 0 and `baseVolume`
  (93) remembers what it was. Every other writer of the main volume (ReplayGain, a resume, a seek) is absorbed by
  `onMainVolumeChanged`. The shadow plays at `baseVolume`, so whatever level rule set the main volume applies to
  the rendition too.
- **Next-song overlap**: the shadow fades out with the incoming player (641-647) and is released in
  `onTransitionFinished`.

How it can carry other renditions:

1. Make the file a parameter instead of the two `TaisInstrumentalIndex` lookups (174, 483), and name what is
   audible: `InstrumentalAudible { NONE, CLIP, FULL }` (`SingClipTimeline.kt:20`) gets a spatial render.
2. Rendition to rendition already exists: `swapClipToFull` (479-526) starts a second shadow (`incomingPlayer`),
   aligns it, crosses the two in 300 ms and releases the old one. A profile change while a render plays is the
   same move.
3. Back to the stream is `crossfadeToOriginal` **when the main player is on the stream**. That covers a render
   that arrived mid-song.
4. If the item **started** on a render (the main player plays the FLAC), going back to the stream mid-song would
   need the stream on a second player built like the engine's (resolver and processor chain). Spec §6.3 allows
   finishing the song instead; say which you did.
5. Sing and Spatial must share the one controller: there is one owner of the main volume. By the spec they are
   never audible together ("Spatial active" excludes Sing's instrumental mode), but the transitions between them
   happen (Sing tapped while a render is on the shadow, Sing switched off again).
6. For Spatial the **service** starts the swaps itself (it listens to `SpatialLibrary.events`); no session command
   and no UI round trip are needed.

"Sing's instrumental mode is on": in the service it is `instrumentalCrossfadeController.isCrossfadedToInstrumental`
(no flow); in the UI layer it is `LyricsSingStateHolder.active` (`LyricsSingStateHolder.kt:194`).

### 2.8 Gain: where the "+4 dB capped at unity" rule lives

- `ReplayGainManager` (`data/media/ReplayGainManager.kt`) reads tags with TagLib **from a file path** (69-106);
  `getVolumeMultiplier` (123-141) → `gainDbToVolume` (113-117): `10^(dB/20)`, kept within 0..2.
- `ReplayGainProcessor` (`data/service/ReplayGainProcessor.kt`, built at `MusicService.kt:249-256`) is the only
  code that sets the player's volume for normalisation. **Everything goes through
  `private fun setPlayerVolume(player, volume)` (91-95)**, which clamps to 0..1 and remembers `expectedVolume` so
  `onPlayerVolumeChanged` (80-89) can tell its own echo from a gesture. Callers: `apply` (123-201),
  `reapplyLastAppliedVolume` (102-105), `onTransitionFinished` (235-259), and `prepareForTransition` (113-117),
  which sets `engine.incomingTrackReplayGainVolume`, the level the crossfade loop fades the incoming player to
  (`DualPlayerEngine.kt:389, 1647-1656`).
- Other writers of `player.volume`: the engine resets it to `1f` in `cancelNext` (1559) and on aborted transitions
  (1579, 1600, 1609, 1617), the fade loop (1648-1656), the rebuild (1055); the in-app volume
  (`PlayerViewModel.setTrackVolume`, 1011-1017); the Sing controller's ramps. ReplayGain re-applies after the
  engine's resets, so they pass through `setPlayerVolume` again.

**The one place: `ReplayGainProcessor.setPlayerVolume`.** Give the processor a lift supplied by ENGINE and write
`min(1f, volume × 10^(headroomDb / 20))` while `SpatialAudible.isSpatial`. Keep `lastAppliedVolume` and
`userSelectedVolume` un-lifted, so re-applying stays idempotent, and lift `engine.incomingTrackReplayGainVolume`
(115, 186) the same way for the incoming item. `headroomDb` comes from the table (`headroomDb` at its top level)
for the stage and from `SpatialRender.headroomDb` for a render.

What you will find in practice, and should say in your note:

- ReplayGain is **off by default** (`UserPreferencesRepository.kt:1064-1067`) and is a Developer mode switch.
- Spotify songs have **no file path** (`SpotifyRepository.kt:1166`), so even with it on, `apply` takes the "No file
  path" branch (144-150) and sets the in-app volume. YouTube's own loudness value is not used anywhere.
- So the app applies no loudness normalisation to streamed songs today: the gain is the in-app volume, 1.0 unless
  the person moved it. By the spec's own formula that gives `min(1.0, 1.0 × 1.585) = 1.0`: **on Android, Spatial
  plays 4 dB under stereo, always, and never clips.** Settled (spec §6.3 "Level"): "Streamed songs have no
  normalisation gain today, so in practice Spatial plays 4 dB under stereo: that is expected", and the sheet says
  so in one quiet line (§6.5, UI's). Still build the rule in the one place above, so it is right the day a
  normalisation gain exists (spec §9 lists it as a possible follow-up).
- Apply the lift only to a normalisation gain, not to the person's own in-app volume (`userSelectedVolume`): lifting
  a volume the person turned down would undo their choice.
- `player.volume` changes in a step. Ramp the lift over the same 80 ms as the stage (a few 16 ms steps, like
  `CrossfadeRamp.ramp`), or apply the lift inside the stage's own gain where the ramp is per sample. The shadow
  player has no stage, so the volume route is the one that always works.

### 2.9 Route: what counts as headphones

- `ConnectivityStateHolder.localAudioOutput: StateFlow<LocalAudioOutput>`
  (`presentation/viewmodel/ConnectivityStateHolder.kt:86-87`), updated by `updateLocalAudioOutput()` (353-368) from
  `currentMediaOutputDevice()` (371-383): `AudioManager.getAudioDevicesForAttributes(media)` on API 33+, else the
  preferred connected sink (`legacyMediaRouteIndex`, `AudioOutputCategory.kt:81-98`).
- It is refreshed by an `AudioDeviceCallback` (264-273) and by an `AudioPlaybackCallback` (279-284), because moving
  the output in the system Output Switcher adds or removes no device. `DualPlayerEngine` already injects this
  holder (247).
- `LocalAudioOutput(category, name)` and `AudioOutputCategory { BuiltIn, Bluetooth, Usb, Wired, Cast, Other }`
  (`presentation/viewmodel/AudioOutputCategory.kt:7-51`); the mapping from `AudioDeviceInfo.TYPE_*` is at 16-39.
- **The category is too coarse for the spec's rule.** `Wired` includes line out (`TYPE_LINE_ANALOG`,
  `TYPE_LINE_DIGITAL`), `Usb` includes `TYPE_USB_DEVICE` and `TYPE_USB_ACCESSORY` (docks, DACs into speakers),
  `Bluetooth` includes SCO, hearing aids and LE broadcast. The spec's list is: wired headset or headphones,
  Bluetooth A2DP/LE (a Bluetooth speaker counts), a USB headset.
- **ENGINE: keep the raw device type.** Add a second flow next to `_localAudioOutput` (the device type, or a
  ready `Boolean`) in `updateLocalAudioOutput()`, and put the rule in a pure function with a unit test (spec §8
  "route rule"). A similar list already exists for "resume when headphones reconnect":
  `MusicService.isReconnectableHeadsetOutput` (`MusicService.kt:2096-2107`): `TYPE_WIRED_HEADSET`,
  `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`, `TYPE_BLUETOOTH_A2DP`, `TYPE_BLUETOOTH_SCO`, `TYPE_BLE_HEADSET`,
  `TYPE_BLE_SPEAKER`.
- Unplugging also pauses playback: the players use `setHandleAudioBecomingNoisy(true)`
  (`DualPlayerEngine.kt:1279`).
- Cast and Spotify Connect are not routes of this phone: during a Connect session the service swaps
  `SpotifyConnectSessionPlayer` into the MediaSession (`MusicService.kt:244, 477-494`) and the local player stays
  paused; Cast goes through `CastPlayer`. Report those as `SpatialBlock.REMOTE`.

### 2.10 Service hooks you will use (`data/service/MusicService.kt`, 3,611 lines)

| Hook | Lines | Note |
|---|---|---|
| `onCreate` | 544 | engine init, listeners, preference collectors |
| settings → `spatialPlayback.apply` | 676-683 | already wired by the foundation |
| `playerListener` | 1663 | attached to the session player; follows player swaps (`publishMediaSessionPlayer`, 443-462) |
| `onVolumeChanged` | 1664-1676 | the Sing controller sees it first |
| `onIsPlayingChanged`, `onPlayWhenReadyChanged` | 1678, 1711 | |
| `onPositionDiscontinuity` | 1777-1809 | seeks reach the shadow here; a repeat is "same media id, auto transition" (1793-1803) |
| `onMediaItemTransition` | 1811-1855 | the place to start spec §6.2 for the new song and to publish `SpatialAudible` |
| `onPlayerError` → `recoverFromPlaybackError` | 1884, 1575-1605 | a render file that fails to play ends here: retry in place, skip, or stop |
| `playerSwapListener`, `transitionDisplayPlayerListener`, `transitionFinishedListener` | 420-441 | engine rebuilds and crossfades |
| `spotifyIdOf(item)` | 1437-1441 | |
| `updateNextStreamPrefetch`, `updateListeningCache` | 1456-1517, 1533-1562 | what is fetched ahead, and when |
| `onDestroy` | 1959-2001 | `engine` is a singleton and outlives the service: reset what you set on it |

Also relevant:

- Next-song transitions are scheduled by `TransitionController` (`data/service/player/TransitionController.kt`,
  `scheduleTransitionFor` 190, `engine.prepareNext` 282, `engine.performTransition` 330/368). Crossfade is a user
  setting; with it off the master plays gaplessly on its own.
- `engine.setNextItemPreload` (823-836) preloads 10 s of the next item (`NEXT_ITEM_PRELOAD_US`, 276): the next
  item's source is opened, and so resolved, well before it is heard.
- The full player's file-info label is built from the playing format
  (`presentation/components/player/FullPlayerContent.kt:1647-1679`): a FLAC render on the main player would read
  like "48.0 kHz • Ultrasound • FLAC". Decide with UI what it should say while a render plays.

## 3. Cloud Studio (`data/cloudstudio/`)

### 3.1 The shape of it

- `CloudStudioEngine` (`CloudStudioEngine.kt`, 2,014 lines, provided at `CloudStudioModule.kt:77-137`) is the
  orchestrator. Every rule is a pure function in `CloudQueuePolicy.kt`; every side effect is a seam in
  `CloudStudioSeams.kt` (`CloudStudioDependencies`, 238-269), so the engine tests run on fakes
  (`app/src/test/java/.../data/cloudstudio/`, with the worker's examples in
  `app/src/test/resources/cloudstudio/worker/`).
- A job is a `CloudJobRecord` (`CloudJobRecord.kt:23-118`), a serializable value; the list is one JSON file,
  `noBackupFilesDir/cloud_studio/jobs.json` (`CloudJobStore.kt`). No Room table. Every field after the identity has
  a default, so old files keep decoding: **new fields must have defaults too.**
- States: `QUEUED → PREPARING → UPLOADING → UPLOADED → SUBMITTED → RUNNING → RESULTS_READY → DOWNLOADING →
  IMPORTED`, plus `FAILED`, `CANCELLED`, `EXPIRED` (`CloudQueuePolicy.kt:167-206`; machine 230-273).
- Passes (`pass`, 825-882): settle interrupted work → `sendReady` (priority lane, then one submit burst) →
  `watchRunPod` → `collectResults` → `runPhoneSteps` (full passes only: up to 3 songs prepared and uploaded at
  once, 891-930) → `sendReady` again. A light pass runs every 15 s while the app is visible (`setAppVisible`,
  312-320; `PixelPlayApplication.kt:113-126`); full passes run in `CloudStudioWorker` (`CloudStudioWork.kt:41-135`).
- Origins and lanes (`CloudJobRecord.kt:8-12, 148-156`): `SING` or `priorityAtMs` (lane 0), `MANUAL` (lane 1),
  `AUTO` (lane 2). A priority job that is uploaded makes automatic and batch jobs still waiting in RunPod's queue
  step aside (`prioritizeQueue`, 1236-1311). **"A Sing job still has priority over library jobs" (spec §6.2) is
  this lane: give library jobs an origin that is never priority.**

### 3.2 What is reusable as it is, and what needs a new job kind

| Piece | Where | Verdict |
|---|---|---|
| SigV4 presigning | `S3Signer.kt:80-116` (`presignedUrl(method, key, query, expiresSeconds, nowSeconds)`) | As is. Any key, so `lib/v1/…` works. |
| Small bucket requests | `CloudObjectStoring` (`CloudObjectClient.kt:11-23`): `presignedUrl`, `head`, `get`, `put`, `delete`, `list` | As is. `get("lib/v1/<videoId>/sp-<profile>.json")` is spec §6.2 step 2 (null on 404). |
| Big transfers | `CloudTransfers.upload/download` (`CloudHttp.kt:53-58`, `OkHttpCloudHttp.kt`) | As is. `download` reports the HTTP status, 404 included. |
| HTTP client | `@CloudStudioHttpClient` (`CloudStudioModule.kt:34-56`): no logging, no redirects | As is. Never log a presigned URL (`CloudRedaction`, `CloudHttp.kt:61-84`). |
| RunPod client | `RunPodJobsApi` (`RunPodJobsClient.kt:203-214`): `run`, `status`, `cancel`, `health`, `selftest` | As is. |
| Keys and consent | `CloudStudioSettings` (plain `SharedPreferences` `cloud_studio_settings`, secrets in `EncryptedSharedPreferences`), built-in keys `CloudBuiltInConfig` (`CloudDefaults.kt:190-245`) | As is. `isBundled` is the default of `spatial.cloud`. Its `settings: StateFlow<CloudSettingsSnapshot>` (`CloudStudioSettings.kt:51`) has the master switch, `enabled`: **`SpatialLibrary.cloudRendersOn` is `enabled && spatial.cloud`, and with either off the library does no lookup, no download and no job** (spec §6.1 "Two switches", settled). |
| Client build, clients, notices | `resolveConfig`/`clients` (1909-1936), `CloudNotice` (31-46) | As is. |
| Prepare (download + decode) | `CloudStudioHost.audioSource` (`LiveCloudStudioHost.kt:50-68`), `LiveCloudAudioPreparer.prepare` (`LiveCloudAudioPreparer.kt:49-71`) | As is. **A streamed song is always decoded to 16-bit FLAC at its own rate** (`forceDecode = record.isStreamed`, `CloudStudioEngine.kt:1097`). Settled (spec §6.2 step 3): "exactly as Cloud Studio does today on that platform (the same preparer and the same decode-to-FLAC rule; do not add a resample that isn't there today)". So a render made from this phone has the stream's own rate. |
| Upload, submit, watch, retry ladder, lost jobs, bursts (`last_in_batch`) | 1146-1173, 1315-1491, 1495-1547, `CloudTiming` (`CloudQueuePolicy.kt:114-158`) | As is, once a record can describe a library job. |
| Import checks | `CloudImportCheck.matches` (bytes + SHA-256) and `samplesMatch` (`CloudQueuePolicy.kt:840-872`), `CloudDigest.file` (`CloudStudioSeams.kt:272-290`) | As is for "verify bytes and sha256". |
| Job schema | `CloudTask` (`CloudJobSchema.kt:43-61`), `CloudJobInput` (119-146), `CloudJobResult` (326-348) | **Extend** (spec §4, Spatial only): the task `spatial`; a nullable `library` on the input (`v`, `videoId`, `source`, `renders`); `library` on the result (`videoId`, `libV`, `renders`, `stemMode`) and the `sp-<profile>` output slots; `stems6Ms`, `renderMs` in `CloudTimings`; `stems6` in `CloudModelsInfo`. `CloudJson` leaves nulls out and ignores unknown keys (567-572), so old jobs stay byte-identical (the golden `run.request.json` test proves it). |
| Builder | `CloudJobBuilder.request` (`CloudJobBuilder.kt:78-126`), `outputSlots` (16-21) | **Extend**: the `library` block with presigned `audioPut`/`manifestPut`/`manifestGet` for the canonical keys, and `library.source` naming the stream (`itag`, `codec`; 2.6). `output.put.manifest` stays required. `CloudTask.outputSlots` must be empty for the new task: library files have no `out/` slot. `tasks: ["spatial"]` with one render entry, for the profile asked for (spec §6.2 step 3). |
| Object clean-up | `CloudJobBuilder.objectKeys` (132-146) only lists keys inside `out/<jobKey>/` | Already safe: a `lib/` key named by a manifest is never deleted (spec §6.2 step 4). Keep it that way. |
| Selection | `CloudSelector.plan` (`CloudQueuePolicy.kt:323-329`): skips a song that **has an instrumental** | **New rule** for library jobs (skip when the local store or the shared library has it). |
| Collect and import | `collect` (1691-1751), `importInstrumental` (1762-1807), `finishIfDone` (1849-1861), `isFullyImported` (`CloudJobRecord.kt:259-260`), `outcome` (1007-1008) | **New branch**: all of these mean "the instrumental slot". A library job reads the manifest's `library`, downloads each `ok`/`exists` output from its `lib/` key and installs it in the local store. `collect` rejects a key outside `out/<jobKey>/` (1715-1718): library keys need their own check against the canonical key. Settled (spec §6.2 step 3): "where the transfer layer only accepts today's slot names, teach it `sp-<profile>`". |
| Stale fields | `settleInterruptedWork` (1047-1067) and `submitReadyBatches` (1381) call `withoutLyrics()`; a job with no task left is removed | Check a library-only job is not treated as "nothing left". |
| Cost estimate | `CloudCost.estimatedSeconds` (`CloudQueuePolicy.kt:497-503`): 33 s per 4-minute song | **New figures** for stems6 + render. Unknown until the worker's first bench (spec §9). |
| Monthly cap | `CloudBudget` (538-600); `CloudKeyChoice.BUILT_IN_MONTHLY_CAP_MICRO_USD = 3_000_000` (`CloudDefaults.kt:138, 175-176`) | **New ledger** (3.4). |
| Timeouts | `CloudTiming.EXECUTION_TIMEOUT_MS = 900_000` (118) feeds `policy.executionTimeout` and the presign lifetime (122) | Settled (spec §6.2 step 3): "The request's `executionTimeout` policy is 1 800 000 ms for a job with library tasks (it overrides the endpoint's)." Make it per job kind and leave Sing's 900 000; the worker URLs' lifetime follows. |
| Progress labels | `CloudProgress.label` (`RunPodJobsClient.kt:48-62`) | Add `stems6`, `render` (unknown stages already show as their capitalised name). |
| Worker examples | `app/src/test/resources/cloudstudio/worker/*.json` | Copy the worker's new examples from the iOS repo (`cloud/runpod-worker/schema/v1/examples/`) and test the builder against them (spec §8). When this was written they were on the iOS repo's `audio-worker` branch only, not yet on its `audio-hq`: `job.input.spatial.json`, `job.input.spatial.combined.json`, `job.result.spatial.json`, `job.result.spatial.exists.json`, `job.result.spatial.partial.json`, `library.render.spatial.json`, `run.request.spatial.json`, and the schema `library.render.schema.json`. That branch still carried examples from before the cut; take the files once the worker has followed it, and ask the lead which commit. |

### 3.3 Keys, lifetimes and the things a library client needs

- Bucket keys today: `CloudKeys` (`CloudQueuePolicy.kt:21-59`): `in/<jobKey>.<ext>`, `out/<jobKey>/<slot>.<ext>`,
  `out/<jobKey>/manifest.json`, `attempt.json`, `probe/<id>`. Add the `lib/v1/<videoId>/…` keys of spec §3 next to
  them, built from `SpatialContract.LIB_V` and `SpatialProfile.id`.
- Lifetimes: the phone's own PUT and GET are signed for 24 h (`UPLOAD_PRESIGN_SECONDS`, 120), small requests for
  15 minutes (`CloudObjectClient.REQUEST_EXPIRY_SECONDS`), the worker's URLs for `ttl + executionTimeout + 1 h`
  (122).
- The record already has `videoId` (`CloudJobRecord.kt:30`), filled at prepare time from
  `host.streamIdentity(song)` (`CloudStudioEngine.kt:1088, 1121`), and `collect` refuses the result when the song
  now plays another video (1700-1711). For a library job the videoId is the key, so know it **before** preparing.
- `CloudStudioHost` is the seam to the app (`CloudStudioSeams.kt:30-61`): `song`, `librarySongs`, `audioSource`,
  `cachedAudioSource`, `streamIdentity`, `hasInstrumental`, `installInstrumental`. A local render store is the
  same kind of thing as `installInstrumental`.

### 3.4 Budget and caps

- `committedThisMonthMicroUsd()` (201-205) = recorded costs + estimates of jobs at RunPod (`CloudBudget`, 538-600) +
  what removed jobs spent (`CloudStudioSettings.removedSpendMicroUsd`, `CloudStudioSettings.kt:155-164`). It counts
  **every** job in the list.
- The cap the guards use is `effectiveMonthlyCapMicroUsd` (`CloudStudioSeams.kt:146`): with the built-in keys never
  above **$3** (`CloudKeyChoice.effectiveMonthlyCap`). Submitting stops at the cap with `CloudNotice.CAP_REACHED`
  (1363-1370).
- Automatic Sing jobs have their own share inside it (`autoPrepareMonthlyBudgetMicroUsd`, $1 by default, and
  `autoPrepareDailyCap`, 12; `CloudStudioSeams.kt:131-135, 157-169`), with their own ledger kept apart from the job
  list so clearing the list frees nothing (`CloudStudioSettings.kt:166-186`).
- **Settled (spec §6.1 "Money"): "Library jobs have their own monthly sum and their own cap
  (`cloudAudio.monthlyCapUSD`). They are not counted against Sing's cap and Sing's jobs are not counted against
  theirs; Sing's $3 rule with built-in keys does not clamp them."** So library jobs must not count toward
  `committedThisMonthMicroUsd`'s cap check and Sing jobs must not count toward the library cap: two ledgers split
  by job kind, including the "removed jobs" part, and `effectiveMonthlyCap` must not be applied to the library
  cap. `SpatialSettings.monthlyCapMicroUsd` is the library cap in the engine's unit (`null` = no limit);
  `dailyAhead` is the background queue's daily count. Publish the library's month and day in
  `SpatialLibraryStats.spentThisMonthMicroUsd` and `sentToday` (Developer mode's rows read them).
- The actual cost of a finished job: `CloudCost.actualMicroUsd(timings, gpu, fallbackPrice)` (521-526) from the
  manifest's `timings.totalMs` and `coldStartMs`. A job whose outputs were all `exists` costs a few seconds.
- A fetch from the shared library (spec §6.2 step 2) costs nothing and is not a job at all.

### 3.5 Prepare ahead: ranking and scheduling (`data/cloudstudio/auto/`)

- Facts: `CloudAutoFactsGatherer.gather(pendingCanonicalIds, nowMs)` (`CloudAutoFactsGatherer.kt:50-93`): the
  library with favourites, play counts and last plays (`EngagementDao`), completions and skips
  (`MusicTasteRepository`), date added, playlist neighbours, blocked artists and taste adjustments.
- Ranking: `CloudAutoPrepare.score` and `rank` (`CloudAutoPrepare.kt:136, 192`); selection inside budgets:
  `select` (218). **The scoring is reusable as it is** ("the same ranking Sing's prepare-ahead uses", spec §6.4).
  The exclusions are Sing's (`excludedBecause`, 154: has an instrumental, 1 to 8 minutes long, skip rate, pending
  job, blocked artist): write the library's own.
- Scheduling: `CloudAutoPrepareWorker` every 6 hours (`CloudAutoPrepareWork.kt:33-57`), constraints unmetered +
  **charging** + battery not low + storage not low (87-93), synced with the switches at app start
  (`PixelPlayApplication.kt:117`) and on every change (`CloudStudioStateHolder.updateSettings`,
  `presentation/viewmodel/CloudStudioStateHolder.kt:124-141`). The run itself is `CloudAutoPrepareRunner.run`
  (`CloudAutoPrepareRunner.kt:72-115`), free of WorkManager and tested.
- Conditions: `CloudAutoConditions.block` (`CloudAutoEnvironment.kt:45-53`: Wi-Fi, charger, battery, heat, storage,
  not while music plays) and `workAllowed` (60-61), which the engine asks before every automatic job's own step
  (`autoWorkAllowed`, `CloudStudioModule.kt:134`; `stepAllowed`, `CloudStudioEngine.kt:964-965`).
- **Where spec §6.4 differs from Sing's rules** (do not just reuse the conditions):
  - it asks for Wi-Fi/unmetered and battery not low, **no charger**;
  - "never while Data Saver is on": read `StreamNetworkConditions.current().dataSaver`
    (`data/stream/StreamNetworkConditions.kt:26-48`), WorkManager has no such constraint;
  - "a song whose audio is not on the phone may be fetched (Wi-Fi only)": Sing's automatic jobs never download
    (`cachedAudioSource`, `CloudStudioEngine.kt:1082-1087`, and the cache check in the runner, 89-91);
  - 25 songs a day, against Sing's 12, and its own monthly cap.
- 6 hours between runs and 3 songs a run (`CloudAutoPrepare.BATCH_SIZE`) cannot reach 25 a day: the library queue
  needs its own cadence.

### 3.6 Local result storage

- Sing's results: `filesDir/tais_stems/<songId>_cloud_inst.m4a|.flac` (`data/tais/TaisInstrumentalIndex.kt:102-108`),
  found by file presence with an in-memory index (`refresh`, 46-65). The class comment explains why it is `filesDir`
  and not `cacheDir`. Files are moved in atomically with `RetainedAudioFiles.copyAtomically`
  (`data/cache/RetainedAudioFiles.kt`; used at `LiveCloudStudioHost.kt:89-100`).
- Staging: `noBackupFilesDir/cloud_studio/staging` (`CloudStudioModule.kt:127`); uploads in
  `noBackupFilesDir/cloud_studio/uploads` (`LiveCloudAudioPreparer.kt:37`).
- Spec §6.2: `filesDir/cloud_audio/<videoId>/sp-<profile>.flac` + its `.json`; the file plus a valid manifest is
  the index. No database change (Room stays at version 1).
- **Android backup**: `allowBackup="true"` (`AndroidManifest.xml:40`) and the rules exclude only preference files
  (`res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml`). `filesDir/cloud_audio/` would go into cloud
  backup (25 MB quota) and into device-to-device transfer (no limit: gigabytes of FLAC). **Add
  `<exclude domain="file" path="cloud_audio/"/>` to both files**, in `cloud-backup` and `device-transfer`.
- Deleting a file that is playing: on Android an open file keeps playing, but a seek re-opens it. Emit
  `SpatialLibraryEvent.Removed` so ENGINE can leave it, and never evict the most recently played render.

### 3.7 Active jobs UI

- Home's "Active jobs" sheet lists WorkManager work tagged `PIXELPLAY_JOB_TAG` (`data/worker/JobTags.kt:16`):
  `JobsStateHolder` (`presentation/viewmodel/JobsStateHolder.kt:33-58`) → `JobsBottomSheet`
  (`presentation/components/JobsBottomSheet.kt:30`). A row shows the kind's label, an optional `percent` and a
  `detail` string from the work's progress data.
- Kinds: `PixelPlayJobKind` (`JobTags.kt:19-31`), chosen from the work's tags in `pixelPlayJobKind()` (38-54).
  `CloudStudioWorker` passes show as "Cloud processing" with `detail = engine.summaryLine()`
  (`CloudStudioWork.kt:105-109`; `CloudStudioEngine.kt:186-195`: "Cloud: 3 waiting, 1 processing").
- Sing's automatic worker is deliberately **not** tagged and shows nothing. Spec §6.4 wants the library queue to
  be "silent: no notification, only the Active jobs row": tag the work, never call `setForeground`.
- The Cloud queue screen (`presentation/screens/cloudstudio/CloudQueueScreen.kt`, texts in `CloudStudioCopy.kt`,
  plain English constants, not `strings.xml`) lists every `CloudJobRecord` that is not a clip. Library jobs will
  appear there too unless filtered; their rows need a title that is not "instrumental".

## 4. Settings, state and UI

### 4.1 Settings storage

- One DataStore for the whole app: `Context.dataStore`, name `settings` (`UserPreferencesRepository.kt:39`),
  provided at `di/AppModule.kt:116-120`. Repositories share it: `UserPreferencesRepository` (1,857 lines),
  `ThemePreferencesRepository`, `EqualizerPreferencesRepository`, `AiPreferencesRepository`,
  `PlaylistPreferencesRepository`, and now `SpatialSettingsRepository`.
- Always read with `dataStore.prefFlow { … }` (`data/preferences/PreferenceFlows.kt:19-20`): the store emits on
  every write of any key, and `prefFlow` drops repeats.
- Backups: `.pxpl` exports every key of the store except a short deny list
  (`UserPreferencesRepository.kt:140-150, 1759-1778`), so the seven spatial keys travel with no extra code (tested
  in `SpatialSettingsRepositoryTest`). Android's own backup excludes the store.
- Keys, defaults and stored forms (spec §6.1): `spatial.profile` (`off`), `spatial.route` (`headphones`),
  `spatial.cloud` (on when `CloudBuiltInConfig.isBundled`), `spatial.prepareAhead` (true), `cloudAudio.storageMB`
  (3072; kept within 256..65536), `cloudAudio.monthlyCapUSD` (30; -1 = no limit; at most 200),
  `cloudAudio.dailyAhead` (25; kept within 1..100). The repository clamps on write and on read; a slider can take
  its ends from `SpatialSettings.MIN_/MAX_STORAGE_MB` and `MIN_/MAX_DAILY_AHEAD`.
- Cloud Studio's own settings are **not** in the DataStore: `CloudStudioSettings` (`CloudStudioSettings.kt`), a
  plain preference file kept out of every backup. A library ledger (month spend, day count) belongs in a file like
  that, not in the DataStore.

### 4.2 Settings screens and Developer mode

- Categories: `SettingsCategory` (`presentation/model/SettingsCategory.kt:27-110`); the main list iterates
  `entries` (`presentation/screens/SettingsScreen.kt:241-271`), with ABOUT, DEVELOPER and DEVICE_CAPABILITIES
  placed by hand.
- A category's content is one `when (category)` inside a `LazyColumn` in
  `presentation/screens/SettingsCategoryScreen.kt` (3,280 lines): `LIBRARY` 453, `APPEARANCE` 555, `PLAYBACK` 784,
  `LYRICS` 964, `SING` 1046, `BEHAVIOR` 1057, `AI_INTEGRATION` 1067, `BACKUP_RESTORE` 1676, `DEVELOPER` 1734.
- **There is already a "Sound" group**: Settings › Playback, `item(key = "playback_quality")` at 823-843,
  `SettingsSubsection(title = stringResource(R.string.settings_sound_section))` (the string is "Sound",
  `res/values/strings_settings.xml:311`), holding only the Audio quality selector. The spec's "a Sound group" fits
  here: one new composable called from this item, or a new `item` right after it. A new category instead costs
  three more edits: the enum, both `when`s of `getCategoryColors` (`SettingsScreen.kt:625-657`) and the
  `when (category)`.
- The pattern for a section in its own file: `SettingsSingCloudSection(navController, viewModel = hiltViewModel())`
  (`presentation/screens/SettingsSingSection.kt:54-133`), called from `SettingsCategoryScreen.kt:1053-1055`. Do the
  same with `SpatialAudioViewModel`; `SettingsViewModel` (1,711 lines) needs no change.
- What the group holds (spec §6.5): Spatial audio (profile, cloud toggle, prepare ahead), and "Songs ready: N ·
  X GB on this phone" with "Remove downloaded renders" (`stats.songsReady`, `stats.bytesOnPhone`,
  `removeDownloadedRenders()`). Developer mode: route mode, the caps (`stats.spentThisMonthMicroUsd` and
  `stats.sentToday` say what is used), the storage budget, per-song diagnostics (`currentRender`: source codec,
  stem mode, loudness). No Enhanced audio row: it was cut.
- **The cloud toggle is `spatial.cloud` only.** Cloud Studio's own "Process songs in the cloud" switch is the
  master (spec §6.1 "Two switches") and stays where it is (the Cloud processing screen that Settings › Sing opens,
  `SettingsSingSection.kt:74-80`; the screens are CLOUD's); do not write it from here.
  `SpatialAudioStateHolder.cloudRendersOn` is the two together: when it is false although the toggle is on, the
  master is off, and the row should say so rather than look switched on and do nothing.
- Building blocks (`presentation/screens/SettingsComponents.kt`): `SettingsSubsection(title, addBottomSpace)` 1049,
  `SettingsItem` 125, `SwitchSettingItem` 172, `ThemeSelectorItem` 240 (a labelled choice), `SliderSettingsItem`
  572, `ActionSettingsItem` 780, `rememberSettingsLiveSliderValue` 1129.
- **Developer mode**: `LocalDeveloperMode` (`presentation/model/DeveloperMode.kt:11`), provided once in
  `MainActivity.kt:775, 893` from `UserPreferencesRepository.developerModeFlow` (1670-1675); the switch is the last
  group of the main settings list (`SettingsScreen.kt:291-305`). The convention: advanced rows sit at the end of
  their category in a subsection titled `R.string.settings_section_advanced`, inside `if (developerMode)`
  (`SettingsCategoryScreen.kt:920-962` is Playback's). Hiding a row never changes the value behind it.
- Routes: `presentation/navigation/Screen.kt` and `AppNavigation.kt` (the settings category route at 315-330). A
  route that should hide the bottom bar must be added to `routesWithHiddenNavigationBar` in `MainActivity`.

### 4.3 The full player and its sheets

- Host: `presentation/components/UnifiedPlayerSheetV2.kt` → `FullPlayerContent`
  (`presentation/components/player/FullPlayerContent.kt:203`, 2,754 lines). Colours come from
  `LocalMaterialTheme.current` (the album-art scheme, `UnifiedPlayerSheetShared.kt:73`), not from
  `MaterialTheme.colorScheme`.
- Where actions live today:
  - top bar: `FullPlayerTopActions` (`presentation/components/player/PlayerOutputPill.kt:167-199`), the output
    pill and the queue button;
  - **the song row**: `SongMetadataDisplaySection` (`FullPlayerContent.kt:1461-1640`): title and artist, then the
    Lyrics button (1606-1619) and the Ask Taizo button (1625-1638), 48 dp circles in `chipColor` /
    `chipContentColor`. **A Spatial audio button fits here as a third sibling** ("a chip/button near the other
    player actions", spec §6.5). In landscape the row shows Lyrics + Queue as a split pill (1556-1603) plus Taizo;
  - under the transport: `BottomToggleRow` (shuffle, repeat, favourite; `FullPlayerContent.kt:1168`,
    `player/BottomToggleRow.kt`).
- Wiring a new button: `onClickTaizo` is the model. State `showTaizoSheet` (257), the lambda (383), the sheet
  (385-391), the parameter through `FullPlayerSongMetadataSection` (1299-1368, both the portrait and landscape
  calls at 623-669) into `SongMetadataDisplaySection` (1472).
- Sheet pattern: `AskTaizoSheet(playerViewModel, onDismissRequest, sheetState)`
  (`presentation/components/taizo/AskTaizoSheet.kt:108-146`) uses `AdaptiveModalBottomSheet`
  (`presentation/components/AdaptiveM3.kt:39`) with `containerColor = MaterialTheme.colorScheme.surfaceContainerLow`.
  Other sheets: `PlayerArtistPickerBottomSheet`, `CastBottomSheet` (the output pill opens it), `QueueBottomSheet`.
- The route for the status line is already in the UI: `playerViewModel.localAudioOutput`
  (`PlayerViewModel.kt:773`). Do not derive the status from it; use `SpatialAudioStateHolder.statusLine`.
- **The sheet's lines (spec §6.5, settled: "Every state has one").** One string per `SpatialStatusLine` case, the
  spec's words exactly:

  | Case | Line |
  |---|---|
  | `ON_PHONE_FOR_NOW` | On your phone for now. The cloud version takes about a minute. |
  | `GETTING_CLOUD_VERSION` | Getting the cloud version. |
  | `CLOUD_VERSION` | Playing the cloud version. |
  | `ON_PHONE_ONLY` | On your phone only. Turn on cloud renders for the full version. |
  | `CLOUD_FAILED` | The cloud version didn't work this time. Still playing on your phone. |
  | `HEADPHONES_ONLY` | Headphones only. Plug in to hear it. |
  | `PAUSED_FOR_SING` | Paused while Sing is on. |
  | `NONE` | no line |

  Under the profile list, always, one quiet line: "Spatial plays a little quieter so the peaks stay clean." The
  profile names and one-liners are in spec §6.5 too (`hall` is shown as "Concert Hall").
- **Performance rules that are enforced by review here** (see `handoff/2026-10-04-START-HERE.md` §6):
  never collect the whole `playerUiState`/`stablePlayerState` in a screen (slice with
  `.map { }.distinctUntilChanged()` inside `remember`, as at `FullPlayerContent.kt:1506-1508`); read animated
  values in `graphicsLayer { }`/`drawBehind { }`, not in composition; collect a flow in the smallest composable
  that shows it (the output pill collects its own state so a route change does not recompose the 2,700-line
  player, `PlayerOutputPill.kt:162-166`). The Spatial button should collect `settings` (or just "is on") inside
  its own small composable, and the sheet should collect only while it is open.
- Android is Material 3 Expressive only: no glass, no blur (CLAUDE.md "Theming").

### 4.4 Strings, translations, changelog

- English strings are split by area in `res/values/strings_*.xml` (`strings_player.xml`, `strings_settings.xml`,
  `strings_cloud_services.xml`, …). Eleven locales (`values-ar`, `-de`, `-es`, `-fr`, `-in`, `-it`, `-ko`, `-nb`,
  `-ru`, `-tr`, `-zh-rCN`) translate the older files only; the newest files (`strings_android_features.xml`,
  `strings_diagnostics.xml`, `strings_listening_history.xml`, `strings_taste_changes.xml`) are English only. Lint
  does not run in CI, so a missing translation falls back to English.
- **Each agent that needs strings adds its own file**: `strings_spatial_ui.xml` (UI), and, only if needed,
  `strings_spatial_engine.xml` / `strings_spatial_cloud.xml`. Nobody edits an existing strings file for this.
- Some screens keep their English in Kotlin: `CloudStudioCopy` (`presentation/screens/cloudstudio/CloudStudioCopy.kt`),
  `PixelPlayJobKind.label`, `CloudJobState.label`, `AutoBlock.label`. Those are CLOUD's.
- In-app changelog: `presentation/components/ChangelogBottomSheet.kt:66-78` lists versions; the items are
  `string-array`s in `res/values/strings_changelogs.xml` (plus `CHANGELOG.md` at the root). Both are written in
  the owner's voice (lower case, chatty). Adding the next beta's entry is the lead's or UI's job, once.
- **Words that must never be in user-visible text** (spec §1, and the standing rule): *lossless*, *hi-res*,
  *Dolby*, *Atmos*, *360 Reality*, the cloud AI's model name and its maker, the lyrics source's old name. The
  feature name is "Spatial audio"; nothing user-visible mentions Enhanced audio, which was cut. The existing
  file-info label can print "FLAC" (2.10).

### 4.5 Compose stability, Hilt, state holders

- `app/compose_stability.conf` marks packages and classes stable; the build passes it to the compiler
  (`app/build.gradle.kts:329`). `data.spatial.**` is in. A new UI state class in another package needs its own
  line.
- Hilt modules: `di/AppModule.kt` (everything general), `di/BackupModule.kt`, and feature modules next to their
  code (`data/cloudstudio/CloudStudioModule.kt`, the two in `data/spatial/`). **Add bindings in your own module
  file, never in `AppModule`.** The app-wide scope is `@AppScope CoroutineScope` (`di/Qualifiers.kt:61-63`,
  `AppModule.kt:141-145`, `Dispatchers.IO`).
- A binding swap looks like `AppModule.kt:110-114`:
  `@Provides @Singleton fun provideX(impl: RealX): X = impl`.
- State holders: `@Singleton class …StateHolder @Inject constructor(…)` in `presentation/viewmodel/`, shared by
  the UI and (when needed) the service; `PlayerViewModel` is a facade and gets no new fields
  (CLAUDE.md "State holders"). Small models: `SpotifyConnectStateHolder.kt` (narrow `StateFlow` slices with
  `stateIn(scope, WhileSubscribed(5_000), …)`) and `CloudStudioStateHolder.kt` (its own scope with an exception
  handler; a `CloudStudioViewModel` facade for composables).
- Workers are `@HiltWorker` + `@AssistedInject` (`CloudStudioWork.kt:41-46`); WorkManager is provided at
  `AppModule.kt:148-151`.
- Logging is Timber; `DiagnosticsLog.event("Area", "text")` writes to the shareable log (no names, ids or URLs in
  it). `PerformanceMetrics` is the allocation-light recorder; read `docs/performance-report.md` before adding to it.

## 5. The on-device table and the golden test

- The table: `assets/spatial_preview_v1.json`, read with `context.assets.open(SpatialContract.PREVIEW_ASSET)`.
  Top level `schema`, `v`, `engine`, `headroomDb` (4.0), `profiles`; six profiles × `"44100"` and `"48000"`; each
  preset has `xoverHz` (120.0), `lowDelay` (50-55), `taps` (192), `direct` and `cross` (192 floats each), `sparseL`
  and `sparseR` (40 triples `[delaySamples, gainFromLeft, gainFromRight]` each), `gain` (0.568-0.654). The largest
  sparse delay is 8,559 samples (`hall` at 48 kHz), so the ring is 16,384. Some numbers are written with an
  exponent. These are the values of the third table of 2026-11-11 (the first had `lowDelay` 38-47 and `gain`
  0.43-0.66, the second 39-52 and 0.575-0.661); **read every one from the file and
  hard-code none**, the worker retunes them.
- Parse it once, off the main thread, into primitive arrays; keep the parsed table in a singleton. `org.json` is
  stubbed in JVM unit tests (`unitTests.isReturnDefaultValues = true`), so **parse with kotlinx.serialization**
  (`Json.parseToJsonElement`) if the parser is to be unit-tested, which the golden test needs.
- The golden file: `{"schema": "pixl.spatial.preview.golden", "v": 1, "frames": 1024, "tolerance": 0.0001,
  "input": {"left": […], "right": […]}, "output": {"<profile>": {"<rate>": {"left": […], "right": […]}}}}`. The
  output is the reference implementation from a zero state. Spec §7: every profile and rate within the tolerance.
- In a unit test: `SpatialFixtures.previewTable()` and `SpatialFixtures.golden()` (assets are not on the unit-test
  classpath; the helper reads the table from the source tree).
- `SpatialTablesTest` already checks that the app's two copies are the handoff copies byte for byte, that none has
  a carriage return, the shape of both files and that the profile keys equal `SpatialProfile.entries`. It pins no
  generated number. Your golden test is what proves the table and the golden file belong together, so it must fail
  loudly, never skip, when they do not.

## 6. Tests

- JVM unit tests live in `app/src/test/java/…` (228 files before this work), JUnit 5 with the vintage engine for
  old JUnit 4 tests; resources in `app/src/test/resources/<area>/`. `unitTests.isReturnDefaultValues = true`:
  Android framework calls return defaults instead of throwing, so keep rules in pure Kotlin and test those.
- Patterns to copy: a DataStore in a test (`SpatialSettingsRepositoryTest`: `PreferenceDataStoreFactory.create(scope
  = backgroundScope, …)` in a temp directory); fakes for a state holder (`SpatialAudioStateHolderTest`); the cloud
  engine on fakes (`CloudStudioEngineTest`); golden JSON against the worker's examples (`CloudJobBuilderTest`,
  `CloudFixtures`); timing rules with a virtual clock (`CrossfadeRampTest`).
- What spec §8 asks of the apps, by owner: CLOUD: builder output equals the examples, manifest decoding and
  rejection of mismatches, store index and eviction, the state machine. ENGINE: the stage against the golden file,
  the route rule, the bypass-for-render rule. UI: nothing is required; a reducer-style test for any pure mapping
  is welcome.
- Instrumented tests exist (`app/src/androidTest`) but nothing runs them in CI.

## 7. CI

- **`.github/workflows/android-ci.yml` is the only workflow that runs on a feature branch.** Trigger: a push to
  any branch, any pull request, or a manual dispatch. **A push that only changes `handoff/**`, `docs/**` or `*.md`
  does not start it.**
- Steps: `./gradlew :app:compileDebugKotlin` → `:app:testDebugUnitTest` → `:wear:compileDebugKotlin` →
  `:app:assembleDebug -Ppixelplay.enableAbiSplits=true` → upload of the arm64 debug APK (kept 14 days). The unit
  test reports are uploaded as the artifact `unit-test-reports` only when the tests fail.
- Time: 6.5 to 8.5 minutes in this branch's runs (compile 3.4 to 4.2, tests 1.7 to 2, APK 1.1 to 1.4).
- `concurrency: android-ci-${{ github.ref }}` with `cancel-in-progress: true`: **a new push to the same branch
  cancels the run in progress.** Push, then let the last run finish before you call it green.
- Commands:

  ```bash
  gh run list  --repo RedSn0w1877/PixlAudio --branch <branch> --limit 5
  gh run watch --repo RedSn0w1877/PixlAudio <run-id> --exit-status
  gh run view  --repo RedSn0w1877/PixlAudio <run-id> --log-failed
  ```

- One test class (on a machine that may run Gradle; this PC may not):
  `./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.spatial.SpatialSettingsRepositoryTest"`.
  CI always runs the whole suite; there is no input to pick one class.
- Not covered on a branch: the release build with R8 (`phone-release.yml` runs on `main` and on pull requests to
  `main` only), CodeQL, the Wear APK. Code that only breaks when minified (reflection, missing `@Serializable`)
  shows up there, not here.
- The debug APK of a branch run carries the built-in cloud keys (the `CLOUD_DEFAULTS_KEY` secret) and is
  downloadable by anyone signed in to GitHub (the workflow's header comment). Never print anything from
  `BuildConfig` or the decrypted configuration in a test or a log.

## 8. File ownership

Three rules:

1. **New code goes in your own package**: ENGINE `data/spatial/playback/`, CLOUD `data/spatial/library/`, UI
   `presentation/spatial/` (or `presentation/components/spatial/` + one settings section file). Tests mirror the
   package under `app/src/test/java/`.
2. **An existing file has exactly one owner below.** If you need a change in a file you do not own, do not make it:
   say what you need in your report and the lead routes it.
3. **The contract files are frozen**: `data/spatial/SpatialProfile.kt`, `SpatialSettings.kt`,
   `SpatialSettingsRepository.kt`, `SpatialRendition.kt`, `SpatialLibrary.kt`, `SpatialPlayback.kt`. Only the lead
   changes them. If the contract is wrong or too small for you, implement the closest thing and report it.

| File or folder | Owner | For |
|---|---|---|
| `data/spatial/SpatialPlaybackModule.kt` | ENGINE | swap the no-op for the real `SpatialPlayback` |
| `data/spatial/playback/**` (new) | ENGINE | stage processor, table parser, route rule, rendition registry, the `SpatialPlayback` implementation |
| `data/service/player/**` (`DualPlayerEngine.kt`, `InstrumentalCrossfadeController.kt`, `CrossfadeRamp.kt`, `SingClipTimeline.kt`, …) | ENGINE | chain, offload and float flags, resolver, rendition swap |
| `data/service/MusicService.kt` | ENGINE | song-start hooks, events from the library, `SpatialAudible` |
| `data/service/ReplayGainProcessor.kt` | ENGINE | the level rule |
| `data/service/MusicNotificationProvider.kt` | ENGINE | only if a new session command is really needed |
| `presentation/viewmodel/ConnectivityStateHolder.kt`, `AudioOutputCategory.kt` | ENGINE | the raw output device type for the route rule |
| `data/cache/AudioCacheManager.kt` | ENGINE | only if the load path needs it (it should not) |
| `app/src/test/java/…/data/spatial/playback/**`, `…/data/service/**` | ENGINE | |
| `data/spatial/SpatialLibraryModule.kt` | CLOUD | swap the no-op for the real `SpatialLibrary` |
| `data/spatial/library/**` (new) | CLOUD | manifests, local store, shared-library client, queue, ledger, worker |
| `data/cloudstudio/**` (engine, schema, builder, policy, record, seams, settings, module, host, preparer, work, `auto/`) | CLOUD | the new job kind |
| `data/worker/JobTags.kt` | CLOUD | the Active jobs row |
| `presentation/screens/cloudstudio/**`, `presentation/viewmodel/CloudStudioStateHolder.kt`, `CloudStudioViewModel.kt` | CLOUD | queue rows and texts for library jobs |
| `PixelPlayApplication.kt` | CLOUD | scheduler sync at app start (next to line 117) |
| `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` | CLOUD | exclude `cloud_audio/` |
| `data/spotify/SpotifyStreamProxy.kt`, `data/stream/CloudStreamProxy.kt` | CLOUD | only for the itag accessor that `library.source` needs (2.6) |
| `app/src/test/resources/cloudstudio/**`, `app/src/test/java/…/data/cloudstudio/**`, `…/data/spatial/library/**` | CLOUD | |
| `presentation/viewmodel/SpatialAudioStateHolder.kt`, `SpatialAudioViewModel.kt` | UI | may add UI-only state and derived flows; must not add rules |
| `presentation/spatial/**` or `presentation/components/spatial/**` (new) | UI | the sheet, the button, the settings section |
| `presentation/components/player/FullPlayerContent.kt`, `PlayerOutputPill.kt`, `BottomToggleRow.kt` | UI | the entry in the full player |
| `presentation/screens/SettingsCategoryScreen.kt`, `SettingsScreen.kt`, `SettingsComponents.kt`, `presentation/model/SettingsCategory.kt` | UI | the Sound group |
| `presentation/navigation/**`, `MainActivity.kt` | UI | only if a new screen is needed |
| `res/values/strings_spatial_ui.xml` (new), `res/drawable/**` (new icons) | UI | |
| `presentation/components/ChangelogBottomSheet.kt`, `res/values/strings_changelogs.xml`, `CHANGELOG.md` | UI | the next beta's entry, in the owner's voice, once the lead asks |
| `app/compose_stability.conf` | UI | new UI state classes outside `data.spatial` |
| `app/src/test/java/…/presentation/**` (new tests), `SpatialAudioStateHolderTest.kt` | UI | |
| `res/values/strings_spatial_engine.xml`, `strings_spatial_cloud.xml` (new, only if needed) | ENGINE, CLOUD | each its own |
| `di/AppModule.kt`, `UserPreferencesRepository.kt`, `PlayerViewModel.kt`, `SettingsViewModel.kt`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `.gitattributes`, existing `res/values/strings*.xml` | nobody | no change should be needed; ask the lead |
| `handoff/2026-11-1x-android-spatial-<role>.md` (new) | each agent | your handoff note |

Where two agents meet, and who moves:

- **ENGINE ↔ CLOUD** meet only at `SpatialLibrary`. ENGINE can build and test everything against a fake library
  (a file dropped into a temp folder); CLOUD against a fake player. Until CLOUD lands, `localRender` is always
  null and `request` does nothing, which is a valid state of the app.
- **UI ↔ ENGINE** meet at `SpatialPlayback.audible`. Until ENGINE lands it is `SpatialAudible.NOTHING`, so the
  status line is empty and the sheet still stores the pick.
- **UI ↔ CLOUD** meet at `SpatialLibrary.status`, `stats`, `cloudRendersOn`, `removeAll`, through the holder. The
  Cloud queue screens stay CLOUD's. Until CLOUD lands `cloudRendersOn` is false, so with ENGINE in and CLOUD not
  the line reads "On your phone only. …", which is true of that build.
- The file-info label of the full player (2.10) is UI's file; what it should say for a render needs ENGINE's
  `SpatialAudible`. UI reads it from the holder.

## 9. Open questions for the lead

The spec as of `audio-hq` 5eb5222 answers what this list first asked: Enhanced audio is cut; every state has a
line; "4 dB under stereo" is expected and the sheet says so; the swap lines up by media time; `library.source`
names the stream, so the itag accessor is needed. What is still open:

1. **The line when a render is wanted but cannot start** (the month's cap is reached, or no network the person
   allows): the contract shows "On your phone for now. The cloud version takes about a minute.", which is the same
   rule as iOS and is not true there. A row for it in §6.5, or leave it?
2. **"Render downloading" needs a status of its own** to show "Getting the cloud version."; Android has
   `DOWNLOADING`, the iOS contract (as of its `audio-foundation` 315c556) folds it into `processing`. The two apps
   should agree.
3. **Hi-Fi mode and Spatial audio do not combine on Android** (2.4). Is "Spatial wins while a profile is selected"
   the wanted rule? (Hi-Fi is a Developer mode switch, off by default.)
4. **Picking a profile mid-song on a phone with audio offload on gives one short gap** (2.4), because offload has to
   go off for the processors to run. Accept it, or keep offload off for everyone from now on?
5. **Another device with `spatial.route = always`, and a rate the stage does not take**, have no row in §6.5; the
   contract shows nothing (`SpatialBlock.REMOTE`, `FORMAT`).
6. **Prepare ahead** without a charger and with downloads (3.5) is a new battery and data cost next to Sing's
   rules. It is what §6.4 says; this only flags that the existing conditions cannot be reused as they are.
