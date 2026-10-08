# 2026-10-08 Android: faster streaming (port of the 2026-10-07 iOS batch)

Branch `port-streaming` (worktree `PixlAudio-android-streaming`), on top of `main` @ 5aa975e. Plan:
`handoff/2026-10-07-plans/streaming-speed.json`; owner decisions: `DECISIONS.md` › Streaming speed (the plan's
"suggested" answers apply to its own `ownerDecisions`). iOS behaviour and constants: PixlAudio-iOS
`docs/handoff/2026-10-07-streaming-speed.md`. **Nothing here has run on a phone.**

## What changed (one commit per step, in the owner's order)

| Step | What the user gets | Where |
|---|---|---|
| R12 measure | Every streamed start (tap, skip, auto-advance) records where its time went. The last 8 show in Spotify dashboard › **Test playback** ("Recent stream starts") and in logcat (`adb logcat -s StreamStart`); aggregates (`stream_*` timings, no track ids) go to the performance report. | new `data/diagnostics/StreamStartTimings.kt`; hooks in `DualPlayerEngine` (transition, READY, playing, resolver time, a `TransferListener` for the player's first byte), `CloudStreamProxy` (request kind, URL time, upstream TTFB, first body byte, retries, errors), `SpotifyStreamProxy` (match / resolve time, client, `n`), `PreSignedStreamResolver` (`n` transform time), `PlaybackDispatchStateHolder` (the tap); card in `SpotifyDashboardScreen`; `PlaybackDiagnosticsReport.streamStarts` |
| R2 (Android form) | **The big one.** The proxy now flushes every 64 KB piece to ExoPlayer. Before, Ktor held the bytes until 1 MiB piled up, so every uncached start and every seek waited for two whole googlevideo requests plus 1 MiB of transfer. | new `data/stream/ProxyBodyWriter.kt`, used by `CloudStreamProxy` |
| Start buffer | Streams start after 1 s of audio (Media3's default) instead of 2.5 s; local files keep 2.5 s; a rebuffer still waits 5 s. | `DualPlayerEngine.buildAdaptiveLoadControl` now uses the (already tested, previously unused) `loadControlBufferProfileFor` |
| Listening cache (prerequisite) | The full-song "listening cache" download now only takes the song you are actually hearing, 5 s in, never under Data Saver; the next song too only on unmetered Wi-Fi. Before: the tapped song **and both neighbours** were downloaded in full at the tap, on any network, competing with the first bytes. | `resolveCloudUri` no longer calls `maybeAutoCache`; `MusicService.updateListeningCache`; the 600 ms neighbour pre-resolution is next-song-only and cheap now |
| R3 prefetcher v2 + R7 | While **this phone** plays: the next 2 songs on Wi-Fi/Ethernet, 1 on cellular or a metered Wi-Fi, none under Data Saver / offline / paused / Spotify Connect. Prepared = matched + URL resolved (both songs), plus ExoPlayer's own preload of the **next** song's first 10 s, so a skip or auto-advance to it starts from memory (Media3 reuses the preloaded period, R7). The warm-up now reads the local player, so it no longer runs while the Echo plays. Warmed URLs live 10 min (was 2). | new `StreamPrefetchPolicy` (pure) + `StreamNetworkConditions`; `StreamUrlPrefetcher.prefetch(list)`; `CloudStreamProxy.prefetchStreamUrls`; `MusicService.updateNextStreamPrefetch`; `DualPlayerEngine.setNextItemPreload` (re-applied on rebuild and crossfade swap) |
| R11 matching | An unmatched song (playing, or one of the next two) runs the searches after the first one at once, judged in the sequential order with the same rules: same video, sooner. A song with no match isn't searched again for 10 min. The background `SpotifyMatchWorker` stays sequential. | `TrackMatcher.findMatchFanOut` (shared `MatchFold` with `findMatch`); `SpotifyStreamProxy` |
| R8 (flag, **off**) | Overlapping InnerTube clients: the next client starts when the current one fails or hasn't answered in 1.5 s, first URL wins, the rest are cancelled. Only on the proxy's path, only when `innertube.hedge.enabled` is `true` in **PixlAudio-iOS `remote/config.json`** (one switch for both apps; it ships `false`). Read in the background at most every 6 h, saved in `filesDir/stream-remote-flags.json`, never awaited (R4's rule). Same client order, cookies and visitorData. | new `HedgedRace`, `StreamHedging` (parse + iOS clamps), `StreamRemoteFlags`; `ChainedYouTubeStreamResolver.resolveHedged` |
| Preload cost | A preloaded start says how much ExoPlayer read and how much the proxy fetched for it beforehand (`preload: yes (N KiB read, M KiB fetched)`). | `StreamStartTimings.proxyBytesServed` |

Left out on purpose: R4 (Android has no remote client table), R5a (already Android's), R5b (owner: no), R6 (OkHttp has no
HTTP/3; Cronet would be a new stack), R9 base.js/WebView warm-up and the optional proxy/visitorData warm-ups (only if the
timings show they matter: look at `n:` and the first tap of a fresh process).

Invariants kept (CLAUDE.md): VISIONOS stays first, cookies only where `supportsCookies`, a fresh visitorData on every
player call, `@YouTubeOkHttpClient` for everything YouTube (the remote-flags fetch talks to GitHub with the default client).
No Room changes, no PlayerViewModel fields, no UI outside the Test playback card.

## How to read a stream-start line

`TAP 1840 ms: request→transition 40, dataSpec 12, url 620 (resolve 610 VISIONOS, n: no), upstream TTFB 140, player first
byte 690, flush gap 3, ready 1500, playing +30; proxy 1 player + 1 download, 0 retries, preload: no`

All numbers are ms. `ready` and `player first byte` count from the moment the player switched to the song; `flush gap` is
player first byte minus the proxy's first upstream byte (should be tiny now; it was the 1 MiB wait). `url cached` = warmed
up ahead. `match N` = matched on the spot. `n: yes` means base.js is on the start path (decides R9). `preload: yes` = R7 hit.
`rebuffered within 10 s` = the 1 s start buffer may be too short on that network.

## What CI proved (android-ci.yml: compile, unit tests, Wear compile, arm64 debug APK)

- 37771412844: **red**, one compile error (JDK 21's `Deque.reversed()` member shadowed Kotlin's `reversed()`; fixed in c29eda2).
- 37772332523: green (R12, flush, start buffer, prefetch v2, preload, listening cache).
- 37773039127: green (+ R11 fan-out, + R8 hedging).
- 37773888453: green on 1e87236 (+ preload bytes fetched).
- 37779156771: green on 472b3c3, the last code commit (the review fixes below). Its `pixlaudio-arm64-debug-apk` artifact is
  the build to test. (This note is docs-only; CI skips handoff/ pushes.)
- CI doesn't print per-test lines; the unit-test task compiled and ran the whole suite and would fail on any failing test.
- New unit tests: `StreamStartTimingsTest`, `ProxyBodyWriterTest` (proves on Ktor 3.6.0 that an unflushed 64 KiB write is
  invisible to the reader and that `copyUpstream` hands each piece over while the upstream is still open),
  `StreamPrefetchPolicyTest`, `StreamUrlPrefetcherTest` (list cases), `LoadControlBufferProfileTest` (stream vs local),
  `TrackMatcherFanOutTest` (same result as `findMatch`, sooner; out-of-order completion; the failure rule),
  `HedgedRaceTest` (virtual time), `StreamHedgingTest` (iOS clamps, off unless the JSON boolean `true`).
- Checked by hand in the Gradle cache (javap), because the plan was written for older versions: Ktor **3.6.0** still flushes
  only at 1 MiB on CIO's non-autoFlush response channel (and CIO's pipeline flushes to the socket after every copy, so our
  flush reaches ExoPlayer); Media3 **1.11.1** has `setPreloadConfiguration`, the per-source buffer setters (parameter order
  verified) and `DefaultLoadControl.shouldContinuePreloading` (preloads while the current song isn't loading).

## Not verified (needs the phone)

The actual speed-up, the preload byte cost on cellular, crossfade + preload together, Data Saver honoured, Connect gate.

## Checklist for Hoa's Pixel 10 Pro

Use the APK from the last green run. After each step, open Spotify dashboard › **Test playback** and copy the "Recent stream
starts" lines (or `adb logcat -s StreamStart`).

- [ ] Wi-Fi: tap a Spotify song that was never played (not downloaded). It should start noticeably sooner than before. Line shows a small `flush gap`.
- [ ] Wi-Fi: let it play 20 s, then skip. The next song should start almost at once; its line says `preload: yes` and `url cached`.
- [ ] Wi-Fi: let a song end on its own. Auto-advance is seamless (`AUTO … preload: yes` or `AUTO 0 ms`).
- [ ] Turn on crossfade, skip and auto-advance a few times: no glitch, no double audio. Crossfaded auto-advances show as `CROSSFADE 0 ms` lines.
- [ ] Mobile data (Wi-Fi off): tap and skip again. Note the `KiB fetched` on preloaded SKIP/AUTO starts (owner budget: about 512 KB to 1 MiB per prepared song). Ignore it on `CROSSFADE` lines: that is the crossfade deck's own loading.
- [ ] Skip a song 2 s in while the next one is slow to start (best on mobile data, a never-played song): the skipped song must not show up as cached later.
- [ ] Data Saver on (phone Settings › Network & internet › Data Saver): play for a minute, skip once. The skipped-to line should say `preload: no`, and PixlAudio's auto-cache size in its storage settings shouldn't grow (no listening-cache download).
- [ ] Pause for a minute, then play: nothing is prepared while paused; resuming works.
- [ ] Spotify Connect to the Echo for a minute: the phone shouldn't warm local streams (no new lines while the Echo plays).
- [ ] Seek to 70 % of a song that isn't downloaded: the jump is quicker than before.
- [ ] A song played for more than 5 s on Wi-Fi shows up as cached later (offline replay still works).
- [ ] Any `rebuffered within 10 s` lines? Send them; they'd mean the 1 s start buffer is too short on that network.
- [ ] Send me five TAP and five SKIP lines from Wi-Fi and from 5G. Look at `n:` and the first tap after the app was closed.

## Adversarial review (2026-10-08, commits 5f32b82, 74cfd15, 472b3c3)

Reviewed `git diff origin/main...HEAD` against the plan, DECISIONS and the Android rules. Fixed:

- **Major, listening cache:** a song skipped before its 5 s were up was still downloaded in full when the next song took a
  while to start (the old check returned early while nothing played, so the skimmed song's wait ran out; on mobile data
  too). New pure `data/stream/ListeningCacheGate` (tested in `ListeningCacheGateTest`): a wait belongs to its song; a skip,
  a pause or a local song drops it, a rebuffer keeps it. The download job also re-checks the player before it fires.
- Pause while a song is still loading changed neither `isPlaying` nor the state, so the preload and warm-up stayed on for up
  to 60 s. `onPlayWhenReadyChanged` now runs `updateNextStreamPrefetch` too. `onDestroy` switches the engine's preload off
  (the engine is a singleton; a stale "on" carried over to the next service instance).
- R8 (flag off): a client ending in its own `CancellationException` left the overlapped race waiting (forever after the last
  client); it now counts as a failed client (`HedgedRaceTest`). The remote-switch fetch retries in 30 min after an offline
  failure instead of 6 h.
- R12: a crossfade swap left the timings on the old song, so the incoming song's loading was later reported as a "preload"
  of it. The swap now records a `CROSSFADE 0 ms` line. Crossfade lines and starts that waited for the play button (a
  restored queue) stay out of the `stream_start_*` averages. The byte counter no longer allocates per read, and the Test
  playback lines are selectable so they can be copied on the phone.

Checked and left as is: the flush helper and its test, the Media3 1.11.1 local-vs-stream buffer split (decided by the
MediaItem URI scheme, re-checked with javap), the fan-out matcher's equivalence, the Connect gate, `@YouTubeOkHttpClient`
use (the GitHub fetch uses the default client on purpose), no Room or PlayerViewModel changes, no glass/M3E UI changes.

## Divergences from iOS (tell the iOS integrator)

Flip the "Android later" rows in PixlAudio-iOS `docs/parity.md` › iOS-first divergences to these:
- R12: ported (`StreamStartTimings`, Test playback card; no signposts, logcat tag `StreamStart` instead).
- R2: a flush fix instead of a chunk ramp. ExoPlayer reads a streaming body, so the first audio depends on googlevideo's
  first byte, not on the chunk size; 512 KB upstream chunks stay.
- R3: ExoPlayer's playlist preload (next song only, 10 s target, about 0.5–1 MiB in practice) instead of a 512 KiB head cache;
  the second song on Wi-Fi gets match + URL only. No 30 s top-up (gapless loading covers the end of a song).
- R7: Media3 reuses the preloaded period; no "adopt the prepared deck" (would need MediaSession player rebinding).
- R8: ported, same switch and clamps, off.
- R11: ported (`findMatchFanOut`).
- R4/R6: n/a on Android.

## Leads (not changed)

- `DualPlayerEngine.wakeModeFor` only treats `http/https` as remote, so `spotify://` streams run with `WAKE_MODE_LOCAL` (no Wi-Fi
  lock). If streams stall with the screen off, add `CLOUD_PROXY_SCHEMES` there.
- If the preload costs more than about 1 MiB per song on cellular, switch the player's media source factory to
  `ProgressiveMediaSource.Factory(resolvingFactory, extractorsFactory).setContinueLoadingCheckIntervalBytes(256 * 1024)`.
- The performance report prints timings generically but counters only by name, so the new preload / retry / early-rebuffer
  facts live in the per-start lines, not as report counters.

## Next step

Hoa runs the checklist; with the lines in hand decide (1) whether to switch `innertube.hedge` on (edit `remote/config.json`
on PixlAudio-iOS `main`, no release), (2) R9 if `n: yes` shows on VISIONOS, (3) the 256 KiB preload cap if cellular costs too
much. Then the integrator merges `port-streaming` into `main`.
