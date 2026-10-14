# Android: playback controls that did nothing, 2026-10-14

Branch `fix-playback-controls`, one PR into `main`. Report from the newest build on a Pixel 10 Pro: "can't
control the song: no pausing, skipping, rewinding". Nothing here could be run on a device (no Gradle locally;
`android-ci.yml` is the compiler), so each cause below is marked **proven** (the code can only behave that way)
or **suspected** (consistent with the report, needs the phone).

## Root causes

1. **The mini player disabled all three buttons while a song was "preparing" — proven.**
   `UnifiedPlayerSheetShared.kt` (`MiniPlayerContentInternal`): `controlsEnabled = !isCastConnecting && !isPreparingPlayback`.
   `PlaybackDispatchStateHolder.beginPreparingSong` only skipped the "Preparing playback" state for songs whose
   URI scheme is local (`content`/`file`). Until the local-songs PR that was most songs; since then every song is
   `spotify://…`, so every tap sets `preparingSongId` and the buttons stay dead until the player reports READY or
   playing for that exact media id. A song that fails to resolve never reports either, so the buttons stayed dead
   for good (nothing cleared the id on an error or on IDLE). Pause/previous/next were all in that row.

2. **Play/pause decided on `isPlaying`, which is false while a streamed song loads — proven.**
   `PlaybackDispatchStateHolder.playPause` and `PlaybackStateHolder.playPause` did `if (isPlaying) pause() else play()`.
   A Spotify song spends its first seconds (match + YouTube resolve + proxy) in BUFFERING with `playWhenReady = true`
   and `isPlaying = false`: a tap on pause called `play()`, a no-op. The button also showed "play" at that point.
   Wear's toggle had the same shape.

3. **A failed song left an IDLE player that ignored skip, rewind and play — proven for the player, the trigger is
   suspected (a failed match/resolve/403 on his phone).**
   `MusicService.onPlayerError` only showed a toast. ExoPlayer then sits in IDLE with the item still in the queue:
   `seekToNext()` moves the index but never prepares it (silence), a seek inside the song is dropped by the
   `MediaController` because `COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM` is unavailable until the item is prepared, and
   `play()` does nothing because `playWhenReady` is already true. Before the local-songs PR a failed stream was rare
   (local files); now it is the normal failure mode. The same state came from a queue restored paused (large
   queues are deliberately not prepared).

4. **Skip in the first ~0.4 s after tapping a song in a long list had nothing to skip to — proven, minor.**
   Perf round 1 attaches the rest of the queue after the tapped song starts (`attachQueueSegmentsIfCurrent`, waits
   up to 400 ms for READY on queues over 200). The UI already shows the whole queue, the player holds one song,
   so `seekToNext()` was unavailable and silently dropped.

5. **A seek on a song that is still loading was dropped — proven.** Same availability rule as 3: the seek bar
   did nothing until the stream was prepared.

6. **`JsEvaluator` did not handle the WebView renderer dying — proven gap, not proven to be what crashed.**
   Without `WebViewClient.onRenderProcessGone`, Android kills the whole app when Chromium's
   `SandboxedProcessService` dies (the music with it, then the service restarts into a restored paused queue,
   which is cause 3 again). It also shared one 5 s timeout between creating the WebView + loading youtube.com
   (up to 8 s) and the evaluation, so a slow load cancelled creation half way and leaked a WebView per attempt.
   The WebView is created on the main thread (`Dispatchers.Main.immediate`) and reused; it was never recreated.

## Ruled out (read, not suspected)

- Perf round 2 shadow player: built lazily now, only touched by the instrumental toggle. No effect on transport.
- Perf round 1 notification provider (`LocalOnlyDefaultProvider`): sets `setLocalOnly(true)` on Media3's own
  builder; actions come from the session's commands, unchanged.
- `playback_queue` DataStore / distinct preference flows: restore builds items, prepares (or not, see 3).
  The MediaController connects from the injected `SessionToken`; if it were not connected
  `PlaybackStateHolder.activeLocalPlayer()` already falls back to the engine's player.
- The M3E restyle: the full player's transport (`AnimatedPlaybackControls`) calls the real handlers, nothing
  disabled, no scrim over it; the glass branches it removed were off by default. `ForwardingSimpleBasePlayer`
  (`SpotifyConnectSessionPlayer`) only wraps the engine during a Connect session and passes commands through.
- The proxy: `CloudStreamProxy` answers `Accept-Ranges: bytes`, 206 with `Content-Range`, suffix and open ranges
  (`CloudStreamSecurityTest` covers the parsing), so a seek into streamed audio is a ranged request.

## What changed

- `data/service/player/PlayerTransport.kt` (new): the transport commands in one place.
  `togglePlayPause` acts on `playWhenReady` + state (pause while loading, prepare+play after an error, restart
  after the end), `skipNext`/`skipPrevious`/`seekInCurrent` prepare an IDLE player first, a seek the player cannot
  take yet is held in `PendingSeek` and applied on READY / when seeking becomes available.
  `PlaybackErrorSkipPolicy` (retry in place once if the song had played 5 s+, else skip to the next song,
  at most 3 failures in 60 s, then stop with `playWhenReady = false` so play is a real state change).
  `QueueAttachGate` (skips tapped while the queue attaches are replayed once it is there; tokens stop a stale
  attach clearing a newer one).
- `UnifiedPlayerSheetShared.kt`: mini player buttons only lock for the Cast hand-over, never for "preparing".
  `UnifiedPlayerSheetLayers.kt`, `FullPlayerContent.kt`, `PlayerSheetFieldStates.kt`: the transport icon shows
  pause while a requested song loads (`isTransportPlaying` = playing, or `playWhenReady` and buffering). The
  progress clock still follows `isPlaying`.
- `PlaybackDispatchStateHolder.kt`, `PlaybackStateHolder.kt`: use `PlayerTransport`; the queue attach is
  bracketed by the gate; the "Preparing playback" hint gives up after 45 s whatever happens.
- `MediaControllerSyncStateHolder.kt`: on IDLE with a queue the UI state becomes not playing / not buffering and the
  preparing hint is cleared; pending seeks are applied on READY and on `onAvailableCommandsChanged`.
- `MusicService.kt`: error recovery (above) with a visible "Can't play X. Skipping to the next song." toast; any
  surface's skip/seek/play on an IDLE player prepares it (`reviveIdlePlayerAfterNavigation`, `onPlayWhenReadyChanged`).
- `WearCommandReceiver.kt`: play / toggle / next go through `PlayerTransport`.
- `JsEvaluator.kt`: `onRenderProcessGone` handled (WebView dropped, next call builds a new one), separate timeouts,
  half-built WebViews destroyed on cancel.
- Tests: `PlayerTransportTest` (play/pause semantics in every state, idle skip, previous threshold, held seek,
  error policy, queue attach gate).

## Phone checks (about two minutes)

1. Tap a song in a long playlist, immediately tap next twice: it lands on song 3 once the queue is in, no dead taps.
2. Tap a song and press pause in the mini player and the full player while the pill says "Preparing playback":
   it pauses (icon is pause while loading), press again: it resumes.
3. While a song loads, drag the seek bar: the song starts and jumps to that point once it is ready.
4. Turn on airplane mode, tap a song: a "Can't play … Skipping" toast, then it stops after three tries and the play
   button works again once you are back online.
5. Lock screen / notification: next, previous, pause behave the same as in the app, including right after a failure.

## Not verifiable without his account

Whether his phone's failures are a bad match, a 403 from googlevideo or something else (Spotify dashboard ->
Test playback names the failing step); whether the Chromium sandbox crash he saw was the renderer dying (the new
handler logs "Renderizador del WebView de JsEvaluator terminado"); behaviour on a Connect speaker or Wear.
