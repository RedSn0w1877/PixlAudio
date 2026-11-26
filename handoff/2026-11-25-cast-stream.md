# Cast: streamed songs and autoplay (2026-11-25)

Branch `cast-stream` (from `everything`). **Not tried on a real Chromecast**: nobody had one. Everything
below is built and unit-tested with fakes; the phone checklist at the end is what still has to be done.

## The problem (from handoff/2026-11-25-music-brain-stage5.md)

- `MediaFileHttpServerService` opened songs only through `ContentResolver`/MediaStore. Every song of this fork
  is `spotify://<id>`, so **Cast played nothing** (404), library songs and engine picks alike.
- During Cast the phone's player sits paused, so `onMediaItemTransition` never fires and autoplay never ran.
  Also, a queue started while casting goes to the receiver only (`CastTransferStateHolder.playRemoteQueue`):
  the phone's own queue is stale then, so autoplay cannot read it.

## What changed

### 1. The relay (`data/service/http/CastStreamRelay.kt`)

- `/song/{id}` (GET and HEAD): after the usual token + allowlist check, a song with a registered track id is
  relayed from `SpotifyStreamProxy` (its loopback `http://127.0.0.1:<port>/spotify/<id>`). Range goes in;
  status, Content-Type, Content-Length, Content-Range and Accept-Ranges come back; nothing else either way.
- Security, on top of `CastSessionSecurity`'s per-session token and song allowlist:
  - `CastAccessPolicy.relayTrackIds` (song id → track id) is filled at queue load from the songs'
    `spotify://` addresses and only for allowed songs; the request never names a track itself.
  - The client must be on the LAN (`isLanClientAddress`: private/link-local/ULA/loopback literals).
  - The relay only talks to the proxy's own loopback route (`isAllowedRelayTarget`). The proxy keeps its
    own upstream host allowlist (googlevideo, Piped, SpotiFLAC).
  - A plain OkHttp client (loopback only), not the default one with the User-Agent interceptor.
- HEAD resolves the stream (`CloudStreamProxy.prepareForRelay`) to answer with a type; no length (unknown
  before the first GET). The Cast preflight HEAD therefore also warms the start song.
- `CastPlayer`: streamed songs skip all file probing; their content type comes from what the proxy has
  cached for the track (`CloudStreamProxy.cachedContentType`: the URL's `mime`, else itag, else
  `audio/mp4`, see `StreamContentTypes`). Covers that are `https://` go to the receiver directly
  (the file server's `/art` only knows covers of files on the phone).
- `configureCastSessionAccess(..., relayTrackIds)` at the three queue-load sites in
  `CastTransferStateHolder`; `extendCastSessionAccess` for picks appended later (same token, same addresses).

### 2. Autoplay while casting

- `AutoplayPolicy.remoteRule(CAST)` = `picks = true, onlyWhatTheOutputPlays = true, rescore = false,
  settleOnLocalPlayer = false` (the stage-5 handoff's (e)).
- `AutoplayController.Host.castQueue()` (default null) returns the receiver's queue as an
  `AutoplayController.CastQueue`. While casting the trigger reads that queue (count, current index, repeat
  mode, ended) and appends there; the phone's engine queue is left alone. CAST with no readable queue does
  nothing (same as before). Re-scores stay off on Cast.
- `CastAutoplayBridge` (`data/service/cast/CastAutoplay.kt`) builds that view from
  `RemoteMediaClient.mediaQueue.itemIds` (the whole queue; a status names only a window) plus the status
  window / fetched items for song ids; unknown items get a `cast-item:<id>` placeholder. Engine-appended
  items are remembered so their extras survive.
- `playableOnRemote` on Cast = the pick's stream resolves now (`prepareForRelay`, in parallel). Picks that
  don't are skipped and kept out for a while (the existing 12.3 path). Resolving also warms the URL cache.
- Append: `CastPlayer.appendSongs` → `queueInsertItems` at the end; after an ended queue (inside the 10 s
  backstop window) it then `queueJumpToItem`s to the first pick.
- Trigger: `CastSyncCoordinator` feeds every status/queue update to `CastAutoplayTrigger`: a new current
  item (once it buffers/plays) → `autoplayController.onTransition()`; IDLE + FINISHED → `onQueueEnded()`
  (once, until something plays again). Reset on session change.

## Tests (JVM, fakes)

`CastStreamRelayTest`, `StreamContentTypesTest`, `CastAutoplayTriggerTest`, new cases in
`CastSessionSecurityTest`, `AutoplayPolicyTest` (Cast rule), `AutoplayControllerHarnessTest` (the trigger
reads the device queue not the paused phone's; plenty queued on the device = quiet; the backstop on an
ended device queue; unreadable device queue = quiet).

## Known gaps / risks

- The Cast queue shown in the app is built from library songs (`CastTransferStateHolder` song map): picks
  from outside the library play but may not show in the app's queue sheet while casting.
- Content type is a guess when the proxy has no URL yet (`audio/mp4`). The Default Media Receiver sniffs
  progressive files, so it should not matter; if a receiver refuses WebM/Opus, send `audio/webm` from the
  HEAD result instead (it is resolved there).
- FLAC from SpotiFLAC is relayed as is. Local FLAC is transcoded because the receiver's seek overshoots on
  it; the same may happen for streamed FLAC.
- A buffering-watchdog reload (`reloadCurrentRemoteItemAfterBuffering`) re-configures access from `lastRemoteQueue`, which
  may lack picks outside the library; those would 404 until the next append.
- After a queue finished, whether the Default receiver keeps the old items (needed for insert + jump) is
  untested.

## To check on a Chromecast (phone + Wi-Fi)

1. Cast a library song: it plays, seeks (drag the bar) and skips; cover shows.
2. Start an album while casting: plays; HEAD/GET in logcat tag `CastHttpServer` say `relay`.
3. A song not yet matched (fresh import): first play may take a few seconds (match + resolve), then plays.
4. Queue of 3 with Autoplay on: near the second-to-last song, picks get appended on the receiver
   (log `cast autoplay` / `appendSongs appended=`), and play on.
5. Let a one-song queue end with no picks ready: within ~10 s picks arrive and the receiver plays the first.
6. Pause, let it sit: nothing is appended into a paused, ended queue after the window.
7. Repeat all on: nothing appended.
8. A pick whose stream fails to resolve is not sent (log `picks left out`).
9. From another device on a different network, `/song/<id>?auth=<token>` is refused (403 off-LAN).
10. End the session mid-song: playback returns to the phone as before.
