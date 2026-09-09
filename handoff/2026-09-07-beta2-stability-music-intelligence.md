# Beta 2 — retention, playback, playlist reliability and music intelligence

**Later update:** Beta 2 has since been installed with user authorization. See
[the NPU follow-up](2026-09-07-tensor-g5-npu-integration.md) for current installation,
source integration and compiler status. The validation details below describe the
original completed Beta 2 build before that installation.

Project: `C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master`

Do not use the older `Downloads\Code Projects\PixelPlayer-master` copy. There is no Git metadata in this checkout. Originals changed during this task are preserved under `backups/beta2-2026-09-07/`, keeping their relative paths.

## Authorization and verification boundary

The user approved computer automated checks. Do not install an APK or run phone tests without their approval. No phone installation, playback, benchmark, or listening test was performed during this task. Final computer validation passed; artifact details are recorded below.

## Implemented changes

- Explicit downloads are retained under internal `filesDir/downloads`, outside Android's disposable cache. Existing retained rows are migrated atomically; failed copies keep the surviving original. Auto-cache writes cannot downgrade permanent downloads. Migration yields the mutation lock between files. Cancellation closes the network call during body reads and cleans partial files. Bulk playlist downloads enqueue distinct raw source IDs, rather than internal library IDs.
- Instrumental playback uses the same retained stem location as render workers. Legacy files survive failed migrations. Complete renders are published atomically; incomplete output does not replace a working render. A Material 3 instrumental action is available in the missing-lyrics sheet, including progress and playback of a saved instrumental.
- Local instrumental reconstruction uses a linked stereo mask, smooth sub-bass crossover, overlapping chunks, and linear peak-safe level matching. Removed nonlinear tanh distortion and two full-song gain buffers. Decoder format/channel handling, cancellation, and stall handling were hardened. The shipped UVR-MDX-NET-Voc_FT model weights are unchanged. These engineering changes still need comparative listening to establish audible quality.
- Playlist lyric sync uses a persistent playlist work chain, bounded retries, individual failure outcomes, progress, retry and cancel controls. One failed song does not invalidate the remaining chain. Saved streaming lyrics override stale imported metadata. Invalid all-zero/failed alignment output is rejected rather than reported as successful sync.
- Playlist and member reorders persist captured ordered IDs, preserve hidden members, and survive metadata updates and Spotify refreshes. Imported playlist membership updates retain surviving manual order and append new tracks.
- Spotify playlist cleanup preserves the synthetic saved/discovery collection. Remote collection snapshots are replaced only after complete pagination; a failed/partial request keeps existing songs. Track replacement is transactional. Database rebuilds replace only local MediaStore rows and retain streaming songs, lyrics and artist links.
- Streaming matching repairs pending imports, progresses past failures, and preserves saved matches. YouTube Music search includes music-video results; muxed video fallback decodes audio only. Matching is stricter about primary artists and recording titles. The proxy reuses healthy signed URLs, handles ranges and expiry, coalesces resolves, and bounds fallback attempts. Legacy `spotify_` IDs remain compatible; new discoveries use canonical library IDs.
- Music intelligence learns locally from completed listening, deliberate plays, favorites and early skips. Ranking includes history decay, recent-repeat penalties, exploration and artist/album diversity. Duplicate recordings retain their combined preference evidence. Delayed events cannot regress recency. This is a new adaptive ranking algorithm, not newly trained foundation-model weights or a claim of Spotify-equivalent quality.
- Daily Mix and Your Mix can discover and import songs outside the existing library through bounded catalog searches. Failed/offline searches retain the local mix. Mix IDs persist, and startup/library sync preserves today's saved discoveries. Background ranking does not require an AI API key. WorkManager schedules quiet periodic refreshes under network/battery constraints.
- Settings → AI integration includes learning/discovery controls, exploration amount, a ranking preview with reasons, refresh/report/reset tools, and explicit instrumental model backend/timing benchmark controls. Added UI follows the app's Material 3 theme with rounded tonal surfaces, accessible controls and the Expressive loading indicator.
- Small libraries retain the discoveries selected for them instead of truncating those additions away. Shared-provider album artwork resolves to stable song artwork, and Android folder navigation paths compare correctly on Windows test hosts.
- Existing conversational AI remains available. Its playlist candidate preparation avoids per-song database lookups and correctly escapes metadata. DJ intent handling distinguishes questions from play requests, preserves exact titles, supports online catalog fallback, and avoids selecting uncached remote tracks while offline.

## Pixel 10 Pro TPU: outstanding dependency

Google's Tensor G5 acceleration route requires the Tensor SDK beta compiler and a model compiled ahead of time for the device, plus the LiteRT dispatch runtime. Generic TFLite with NNAPI enabled is not evidence of TPU execution. This build explicitly reports CPU/XNNPACK on detected Google Tensor G5, measures completed inference times, and does not advertise unverified acceleration. On other supported devices NNAPI may be requested, with CPU retry if delegate execution fails.

An asynchronous question asked whether the user already has Tensor SDK beta access. No SDK/compiler package or G5-compiled separation model was available during implementation. Next NPU work requires obtaining authorized SDK access, compiling the graph and checking supported partitions/operators, integrating the compiled model/runtime, validating output parity, then—only with approval—measuring real device delegation, latency, memory and temperature. Do not label a successful NNAPI initialization or CPU benchmark as NPU confirmation.

References checked:

- [LiteRT NPU support](https://developers.google.com/edge/litert/next/npu)
- [Google Tensor SDK](https://developers.google.com/edge/litert/next/tensor-sdk)
- [Tensor SDK beta announcement](https://developers.googleblog.com/google-tensor-sdk-beta-with-litert/)
- [NNAPI documentation/deprecation](https://developer.android.com/ndk/guides/neuralnetworks)
- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Building with Material 3 Expressive](https://m3.material.io/blog/building-with-m3-expressive)

## Build environment

Version: `0.7.6-beta2`, code `11`. The debug package is `com.theveloper.pixelplay.debug`; release is a separate package. No release keystore was present, so do not describe the debug APK as a production-signed store release.

PowerShell build command, from the actual project:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS = '"-Djdk.net.unixdomain.tmpdir=C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master"'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain '-Pksp.incremental=false'
```

Quote the `-Pksp.incremental=false` argument in PowerShell. Nonincremental KSP avoids the encountered Kotlin FIR cache failure. Keep Android Studio closed during command-line builds. Generated resource folders had inherited ReadOnly attributes; these were cleared only inside this checkout's verified `app/build` directory. Do not delete user source or broadly reset folders to fix build locks.

## Later phone checks — not executed

With user approval: update the existing debug installation, verify downloads and instrumentals offline after restart/cache cleanup, retry a partly failing playlist sync, reorder then restart/resync playlists, seek streaming tracks/music videos, inspect saved discoveries, listen to before/after instrumental output, and run the explicit Settings model benchmark. Multi-day retention and real-world stream reliability cannot be established by unit tests alone.


## Final validation and artifact — 2026-09-07

- Final combined `:app:testDebugUnitTest :app:assembleDebug` completed successfully in 5m 23s.
- **468 tests passed across 87 suites; 0 failures, 0 errors, 0 skipped.** This includes retention, cancellation, DSP, streaming identity, complete-snapshot sync, database rebuild, playlist ordering/sync, recommendation ranking/discovery and saved-mix race regressions.
- Existing test defects were repaired as encountered: missing injected dependencies/temporary Context directories, obsolete backup-section and buffer-start expectations, and invalid nested JUnit methods. Assertions for lyric precedence were preserved; production error handling was fixed instead.
- ARM64 APK: `app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`
- Version `0.7.6-beta2` / code `11`; package `com.theveloper.pixelplay.debug`; target SDK 35; native ABI `arm64-v8a`.
- Size: 647,897,290 bytes (about 618 MiB). Packaged at 2026-09-07 10:06:35 local time.
- SHA-256: `9677f8358c5abb7688df3cc2beed014eb80704110144a8bb12e339d87142410d`
- `apksigner verify --verbose --print-certs` passed using APK Signature Scheme v2. Android Debug certificate SHA-256: `3189ce56aeecf435eace45369fe2d9bc1a7420774e9d0a607015105a2d130ce6`.
- Computer network probes also exercised YouTube Music song/video search and consecutive/seek byte ranges on a single signed audio URL. These do not establish Android end-to-end playback reliability.
- Machine-readable verification: `build/beta2-verification.json`; complete build log: `build/beta2-validation.log`; HTML test report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- `build/beta2-backed-up-changes.json` records hashes of 68 modified existing files with preserved originals. New implementation/test files are additional to that manifest.
- No phone installation or testing, no new model weight training, and no verified Pixel 10 Pro TPU execution. Those limitations remain as described above.
