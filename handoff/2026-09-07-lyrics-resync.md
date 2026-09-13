# Lyrics resync follow-up

Active checkout: `C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master`.

The user identified **A COLD PLAY — The Kid LAROI** on the open lyrics screen.
ADB media-session metadata confirmed this track, paused at 0 ms. The phone then
disconnected before its current lyric cache could be inspected. The earlier
local database snapshot has no lyric payload in this song row; it is insufficient
to diagnose the actual displayed timestamps. Do not claim its timing defect is
verified fixed just because resync is now available.

## Changes

- Explicit single-song sync/resync requests now bypass the existing-word-sync
  skip. Playlist batches retain their existing skip behavior.
- The Lyrics More sheet now exposes the same tonal sync/resync control and
  WorkManager progress/failure feedback used by the song tools.
- Explicit resync uses existing text (including repeated lines) but discards old
  timestamps before forced alignment. It does not delete cached lyrics first.
- Empty, all-zero, negative, or backward word timings are rejected before saving.
  Coroutine cancellation is checked before persistence.
- Successful completion reloads the displayed lyrics. The progress row remains
  mounted while lyric loading clears the lyric state, and callbacks are delivered
  once per job, avoiding a reload loop.
- Four regression cases cover explicit resync, line anchors, missing text, and
  rejection without calling the lyrics repository's update function.

Originals: `backups/lyrics-resync-2026-09-07/`.
Validation log: `build/lyrics-resync-validation.log`: BUILD SUCCESSFUL; 487 tests, 88 suites, zero failures/errors/skips.
APK signature matches installed debug certificate; ZIP alignment and bundled model integrity passed.
APK SHA-256: `6ea6bc79d49657be92b16a9dd9a579e139cda3378d70cd26dbb648c84e120034`.
Verification: `build/lyrics-resync-verification.json`. The new APK is not installed.
The preceding NPU/playlist candidate passed 483 tests and assembled successfully;
that candidate has not been installed on the phone.

## Pending phone work

Phone tests and installation are authorized. A reconnect request was sent after
ADB reported serial `59240DLCH004G6` not found. Do not ask for approval again.
Install only after current tests/build pass. Verify repeated resync, completion
refresh, failure preserving old lyrics, and the affected track's actual timing.
The earlier playlist drag patch and LiteRT Android runtime still need phone checks.
Restore original `stay_on_while_plugged_in=7`; current test value is 2. Connectivity,
Bluetooth and speaker volume were restored during earlier testing. The initial
playlist order is recorded in prior task notes and the baseline data; an exploratory
drag swapped deep stuff / Goodbye for now :( and needs restoration if still changed.

NPU status remains as documented in `2026-09-07-tensor-g5-npu-integration.md`:
no AOT Tensor G5 model exists, vendor compiler download was unavailable, and actual
TPU execution is not verified. No music-understanding weights were trained; see
`build/phone-qa/recommendation-training-readiness.json` for the evidence-based decision.
