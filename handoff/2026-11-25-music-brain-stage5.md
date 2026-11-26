# Music brain, stage 5 (Android): on-device tempo and loudness, flow ordering

Branch `brain-stage5` (from `everything`). Spec: `docs/brain-spec.md` 3.3 (Stage 5 source), 3.5 (energy), 6.5 (`flowCost`), 10, 14 "Stage 5".
Deviations continue the numbering of the earlier handoffs as S5-1, S5-2, ... **Nothing here has run on a phone.** The estimator is measured
on synthetic PCM only (numbers below); the decoder tap, the stall rule and the diagnostics row are traced from code, not observed.

## What was built

### 1. The estimator (`:brain`, pure) — `AudioFeatures.kt`

- `AudioFeatures.Estimator(sampleRate, channels)`: `feed(ShortArray | FloatArray, offset, frames)` (interleaved), `full`, `finish()`.
  Arrays sized once per window; the decode loop allocates nothing per block.
- Tempo: 5 ms hops (`sampleRate / 200`), three bands by one-pole splits at 200 Hz and 3 kHz, log energy per hop, half-wave rectified
  flux summed with the low band x4 (`LOW_WEIGHT`), mean removed, unbiased autocorrelation up to 8 beats at 48 BPM. Coarse grid 50-220 BPM
  every 0.5: comb = mean r(k*T) for k=1..4, minus 0.35 x off-beat lags (k-0.5)T, minus 0.3 x the third-lags (k-1/3)T, (k-2/3)T; times a
  log-Gaussian preference around 120 BPM (1.1 octaves). Fine search +-1.5 BPM every 0.02 with 8 beats, no preference.
  Confidence 0..1000 = clip(2.5 x (winner comb - mean comb)); 0 when the onset curve's std is under 0.05 (held tones, drones).
- Loudness: BS.1770 K-weighting (pyloudnorm's biquad formulas at the actual rate; `tan` by an 8-term Taylor series, `tanSmall`),
  100 ms mean squares, 400 ms blocks, absolute gate -70, relative gate -10 LU. Every channel weighs 1. Stored as LUFS x 10.
- `chooseTempo(deezerBpmX10, measured)`: trusted measurement (conf >= 550) > Deezer > weak measurement (conf >= 250) > unknown.
  `chooseGain`: Deezer's `gain` first (it has 498/500), the measured LUFS fills the gap.
- `TrackDna.TEMPO_UNKNOWN / DEEZER / ACOUSTICBRAINZ / ON_DEVICE / ON_DEVICE_WEAK` (0..4) name `tempoSource`.
- `windowStartMs(duration)`: 20% in, never so late the 40 s window runs off the end.

**Measured on the fixtures** (`AudioFeaturesTest`, `AudioFixtures`): 16 rhythm fixtures (10 click tracks 60-174 BPM at 44.1/48 kHz,
6 drum grooves with kick, snare on 2 and 4, hats on eighths, 85-150 BPM): **15 within 1% (every one exact to 0.1 BPM), 1 octave error
(groove 85 read as 170)**. No 2:3 errors left. Every rhythm fixture has conf 1000; noise 40-45; tones 0. Loudness: stereo 1 kHz sine at
-23 / -18 / -10 dBFS reads -23.0 / -18.0 / -10.0 LUFS; mono -23 dBFS reads -26.0 (as BS.1770 says). Real music will be worse; the octave
ambiguity does not hurt flow (`tempoDist` folds octaves).

### 2. Flow ordering (`:brain`, pure) — `Flow.kt`, `ListBuilder`, `StationPlanner`, `MixPlanner`

- `Flow.step(prev, next)` = the spec's tempo and gain terms + an energy step `min(0.1, 0.1 * max(0, |dE| - 32) / 64)`.
- `Flow.arc`: finite lists only (Daily Mix and daypart mix): target energy = median of the pool's known energies + 36 x shape(t),
  shape -0.5 at the start, +0.5 at 65%, -0.3 at the end; cost `min(0.1, 0.1 * max(0, |E - target| - 20) / 64)`.
- `ListBuilder.Request.flow: Flow.Context?` (facts by rid, `lead` = what plays before the list, `arc`). Subtracted in `value()` only
  (familiar, novel and liked slots); opener, closer and bridge slots are unchanged. `prev` = the slot before, or `lead`.
- `StationPlanner.Request.flow`, `MixPlanner.Request.flowFacts` (arc when `kind == KIND_DAILY`).
- **Null flow builds byte-identical lists**: no earlier golden file changed; `SPEC_VERSION` stays 2.
- On random pools `FlowTest` measures the summed step cost of 40 lists: 49.5 without flow, 12.0 with it.

### 3. Energy nudge — `TrackDna.feel(atlas, tags, gainDbX10, tempoBpmX10)`

The old `feel`, then `+ clamp(floorDiv(bpmX10 - midX10, 30), -15, 15)`, `mid` = tag-weighted middle of the descriptors' tempo bands.
Valence is not nudged (S5-3).

### 4. App

- `brain.db` **version 8**: `audio_measure` (`AudioMeasureEntity`, `BrainEntitiesV8.kt`), `MIGRATION_7_8`, schema `8.json`,
  `MusicBrainMigrationsTest` holds the statement against Room's.
- `AudioMeasureStore` (`AudioMeasureLookup`, `FlowFactsSource`): reads/writes the measurements; `flowFacts(rids)` merges DNA rows and
  measurements (`AudioMeasurePolicy.facts`). Retry rule: a failure waits a day; three failures wait 30 days; a new `VERSION` re-measures.
- `AudioMeasurer` (singleton, one background-priority thread): `MusicService.onMediaItemTransition` hands it the current track and the
  next one (full queue, not the player's window); `onPlaybackStateChanged` sets the stall flag. It waits 10 s, resolves the rid
  (`TrackCatalog` + `TrackResolver`), decodes 40 s from 20% with `MediaExtractor` + `MediaCodec` (16-bit or float PCM), from the
  downloaded/auto-cached file when there is one, otherwise through the local stream proxy **only on unmetered networks without Data
  Saver** (marked `X-PixelPlay-Purpose: download` so stream-start timings ignore it). A stall pauses the decode; 8 s of stall gives the
  window up without counting a failure. 60 s deadline. A skip cancels the work.
- `DnaAssembler.assemble(..., measured)` / `DnaEnricher`: tempo by `chooseTempo`, gain by `chooseGain`, energy with the tempo nudge.
- Planners: `CandidateDna.Known.flowFacts` → `BrainMixes` (Daily, daypart, Your Mix, First Listen) and `AssistantMixer`;
  `AutoplayController.planOnBrain` reads facts for the pool, the prior tail and the lead (`PlanInputs.leadRid`) and passes `flow`.
- Developer mode › Maintenance: "Tempo and loudness" (`BrainMeasureRow.kt`, `BrainSessionStateHolder.audioMeasure`), e.g.
  "128.0 bpm, 92% sure · -8.4 LUFS · used for flow · 0.6 s", or why not ("Waits for Wi-Fi ...").
- Spotify Connect: autoplay there already ran since stage 2a; a pick Connect cannot play is now logged (`autoplay: n of m picks left out`).

## Not done, and why

- **Cast autoplay (task 3, Cast half): not built.** Traced: the Cast queue is loaded once (`CastPlayer.loadQueue`) as `/song/{id}` urls of
  `MediaFileHttpServerService`, which serves a song through `contentResolver` (or MediaStore). Every song in this Spotify-only fork has a
  `spotify://` content uri, which no ContentProvider opens, so **Cast cannot play any streamed track today**, library or engine pick.
  Also, during Cast the local player sits paused, so `onMediaItemTransition` never fires and the autoplay trigger never runs. Making it
  work needs: (a) the file server relaying `spotify://` songs (and `playable` engine tracks) from `SpotifyStreamProxy` with Range
  pass-through and a content type from the itag, (b) the allowlist extended on append, (c) a trigger from the remote status
  (`CastSyncCoordinator` callback: remote item changed, items left), (d) `remoteMediaClient.queueInsertItems` for the picks, (e)
  `AutoplayPolicy.remoteRule(CAST)` with `picks = true, onlyWhatTheOutputPlays = true, rescore = false`. That is a Cast feature first and
  an autoplay change second; it should be built and tried on a Chromecast by someone with one. `RemoteRule(CAST)` is unchanged (off).
- Shared Worker cache: needs the owner (brief).

## Deviations

- S5-1: gain term of `flowCost` capped at 0.15, plus the energy step and the arc (spec 6.5 updated).
- S5-2: measured loudness is stored in `gainDbX10` as LUFS, assuming Deezer's `gain` is on a similar scale [unverified offset]; Deezer
  wins whenever it has one, so mixing scales only happens for tracks Deezer lacks.
- S5-3: valence not nudged (the spec gives no rule).
- S5-4: measuring over the network only on unmetered connections (about 1 MB per track); cached files on any network.

## For the iOS port (must match bit for bit)

- Port `AudioFeatures.kt`, `Flow.kt`, the `TrackDna.feel` overload, the `ListBuilder` flow hook, operation by operation (no FMA,
  `BrainMath.ln/exp/log2` only). Test fixtures: port `brain/src/test/.../AudioFixtures.kt` (it is the definition of the PCM) and check
  `pcmHash` first. Golden files: `audiofeatures.jsonl`, `flow.jsonl`; replay rules in `brain-golden/README.md` ("Stage 5").
- Decode tap on iOS: `AVAssetReader` to 16-bit interleaved PCM, feed in any block size (results do not depend on it; tested).

## To check on a phone

The diagnostics row fills in about 10-20 s into a song on Wi-Fi; a downloaded song measures on mobile data; a stall (airplane mode
mid-song) pauses it; a Daily Mix built after a few days of measuring steps smoothly in tempo; `brain.db` upgrades from 7 without loss.
