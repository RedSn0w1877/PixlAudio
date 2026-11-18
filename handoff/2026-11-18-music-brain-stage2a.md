# Music brain, stage 2a (Android): running handoff

Branch `music-brain`. The contract is `docs/brain-spec.md` (v1.1), section 14 "Stage 2" half 2a: everything moved
from stage 1, the brain scorer behind `BrainSwitch`, and Daily Mix / Your Mix with new music. Stage 1's own handoff
(`handoff/2026-11-17-music-brain-stage1.md`) is the map of what exists; its deviations are numbered 1 to 79. This
file continues the numbering at **80**.

**Nothing below has been run on a device.** No phone was available. "Works" means: unit tests, compiling, and
reading the code path end to end. Every section ends with what is still unverified.

---

## Milestone 1 of 2a: "the mixes get new music"

### Task 1: Spotify Connect and Cast with a track that has no Spotify URI (spec 14.6, owner question 7)

**The trace (read from the code, not run).**

*Cast.* `CastPlayer.loadQueue(songs, ...)` builds the whole Cast queue once, as urls of this phone's Ktor file server
(`MediaFileHttpServerService`), one `/song/{songId}` url per song. `CastSessionSecurity.buildAccessPolicy` takes the
list of allowed song ids at that moment and the server refuses every other id (`isAuthorizedSongRequest`). For an
allowed id the server resolves the song with `musicRepository.getSong(id)` and, failing that, a MediaStore query on
a numeric id (`resolveSongForServing`), then serves `contentUriString` / the file path. Nothing appends to a loaded
Cast queue. So a pick from outside the library (`playable` row, synthetic id, no `songs` row, no content uri) is
not in the allowed set, has no row to resolve, has no file to serve, and cannot be added after the load.
**Decision: Cast stays off.** No picks, no station. The queue line says so ("Off while casting. Cast plays only the
songs you queued"), the Autoplay switch and the Familiar / Blend / Discover control are hidden while a Cast session
is up, and "Start station" shows a plain toast instead of starting one song and nothing after it.

*Spotify Connect.* A Connect session plays the phone's own queue entry by entry (`SpotifyConnectController`): each
queue item becomes a `ConnectTrack` (`ConnectTrack.from(item)`), which is a direct Spotify track uri when the item
carries a real Spotify id (`SpotifyConnect.isTrackId`: 22 base62 characters that are not all lower-case hex) and
otherwise is looked up by ISRC, then by "title artist", and accepted only on a strict match (same normalised title
and primary artist, duration within 3 s, no live / remix / cover mismatch: `SpotifyConnectMatcher`). A miss is
cached (`MISS_RETRY_MS`) and the entry is skipped when its window is sent. An engine track has a synthetic id (22
lower-case hex characters, not a Spotify id), so it goes through the lookup. So Connect can play an engine track
**when Spotify has it**, and skips it silently when not.
**Decision: autoplay and stations run on Connect, limited to what Spotify has.** The planner's picks are looked up
*before* they are queued (`SpotifyConnectController.canPlay`, the same lookup and cache the window send uses, at most
24 searches a call), and a pick Spotify does not have never reaches the queue: it leaves the pool and is remembered as
failed for a while (`AutoplayController.keepWhatTheOutputPlays`). Library tracks on the library-only pool path go in
only when they carry a Spotify id (no room for a lookup there). The tail is **not re-scored** on a remote output
(replacing what the device was told to play means sending it the list again, which is audible), and the 1.5 s settle
rule reads no position there (the phone's own player sits paused at 0 while the device plays), so it is a plain delay.
One switch turns Connect autoplay off again: `BrainSwitch.AUTOPLAY_ON_CONNECT`.

**Files.** `AutoplayPolicy.kt` (`RemoteOutput`, `RemoteRule`, `remoteRule`, `isSpotifyTrackId`, `keepPlayable`; all
pure and tested), `AutoplayController.kt` (`Host.remoteOutput()`, `Host.playableOnRemote`, the rule read at every
place that used to ask `isRemoteSession`), `MusicService.kt` (the host), `SpotifyConnectController.kt` (`canPlay`),
`BrainSwitch.kt` (`AUTOPLAY_ON_CONNECT`), `BrainSessionStateHolder.kt` + `AutoplayDivider.kt` (the Cast line),
`StationStateHolder.kt` (no station while casting), `strings_brain.xml`, `strings_player.xml`,
`AutoplayPolicyTest.kt` (six new tests).

**Deviations.**

80. **Connect autoplay is on, Cast is off, by output and not by one flag.** The spec's owner question 7 default was
    "off on both until traced". The trace says Connect can take picks.
81. **A pick Spotify does not have is dropped at plan time**, not skipped by the device later. The cost is up to 24
    Spotify searches per batch (the lookup cache makes the window send free afterwards).

**Not verified on a device.** Everything: that a Connect device actually plays an engine pick; that the 24-search
bound is enough for a station's 20-track first batch on a cold cache (a miss leaves the batch short and the pool
refills); that the Cast line shows the moment a session starts; that a session ending mid-way brings the switch back.
