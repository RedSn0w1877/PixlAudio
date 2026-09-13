# PixelPlayer Beta 2 release continuation

Release candidate completed: both APKs built and verified. All 569 app tests across 99 suites passed, with zero failures, errors, or skips. No phone installation or testing occurred in this turn.

## Source and scope

Active source: `C:\Users\Hoa Vo\Downloads\Code Projects\PixelPlayer-master\beta2-release`.

The user confirmed previous word-sync, stem persistence, downloads, reordering, and YouTube playback fixes work. Their latest request adds quiet automatic processing, fuller adaptive Home, playback startup improvements, an honest TPU status, and a release APK. They will install/test/release it themselves. No phone automation or installation was performed in this turn.

## Implemented

- AutomaticStudioManager schedules one quiet job at a time through a persistent 15-minute WorkManager sweep, including after the app process is recreated. It covers the whole library, ranks current/recent/favorite songs above never-played songs, uses an eight-job six-hour limit, battery/thermal/storage checks, six-minute track limit, eight-minute job budget, persistent retry cooldowns, playback protection, and manual-work priority.
- Automatic lyric jobs require validated connectivity. Connectivity loss defers rather than recording a day-long catalog miss, including fast failures before the watchdog tick. Local instrumentals remain eligible offline. Existing partial word timings are preserved. Manual buttons and notifications retain their existing behavior; automatic jobs bypass foreground notifications.
- Settings has automatic switches and queue diagnostics. Completed automatic lyrics refresh the current screen with canonical song-ID matching.
- Home has adaptive mix cards and up to six populated shelves, including outside-library discoveries and real recent releases from Spotify top artists when connected. It uses the existing listening/likes/skips algorithm, durable catalog metadata caching, and established import/playback paths. No followed-artist API or newly trained weights are claimed.
- Playback prefetches one next-track URL, shares URL resolution, bounds/refreshes cached URLs, and wakes immediately on proxy readiness. MusicService retargets this on crossfade player swaps. YouTube identities/headers/cookies/fallbacks are retained.
- FFT scratch workspace reuse reduces instrumental allocation while preserving the arithmetic and bundled model.
- Release keep rules cover persisted lyric/catalog fields and native LiteRT constructed classes. This follow-up candidate uses version code 13; version name remains 0.7.6-beta2 so it upgrades the earlier Beta 2 install.

## TPU status

The active bundled stem model still uses CPU/XNNPACK. The LiteRT Google Tensor runner exists, but the required validated Tensor G5 compiled model and manifest are absent. No TPU execution or device speed improvement was measured. See `docs/2026-09-08-tpu-status-and-fft-reuse.md`.

## Final validation

The initial full build compiled both app variants and Hilt successfully, then found two old LyricsStateHolderTest constructor calls missing the new dependency. Their fixtures now supply a mock with a real empty update flow.

The final sequential run passed `:app:testDebugUnitTest` (569 tests, zero failures), then `:app:assembleRelease` (version code 13), with normal KSP, `--no-daemon --no-parallel --max-workers=2 -Pksp.incremental=false`. Logs: `build/beta3-background-tests.log` and `build/beta3-background-release.log`. Official SDK apksigner/aapt/zipalign verification and the read-only packaging verifier both passed.

Build environment: Android Studio JBR and installed Android SDK. JAVA_TOOL_OPTIONS sets `jdk.net.unixdomain.tmpdir` to the shorter outer workspace path. SDK/Gradle execution used authorized elevation; no automatic approval rejection occurred. The first cold build was slowed by low available system RAM.

All ten merged arm64 native libraries passed ELF 16 KiB PT_LOAD alignment. The verifier script `tools/verify_beta2_release.py` has eight passing fixture tests and checks final APK native alignment, assets/model hash, JUnit totals, and version metadata. Official apksigner/aapt checks are separate.

The old release signature was fully verified: SHA-256 certificate `3189ce56aeecf435eace45369fe2d9bc1a7420774e9d0a607015105a2d130ce6`. Release retains this signing identity through the existing build configuration. The release package is `com.theveloper.pixelplay`; the tested debug app is `.debug`, so these are separate apps. Backup transfers selected metadata/lyrics, not downloaded audio or generated stems.

User-facing notes and manual checklist: `docs/beta2-release-notes.md`.

## Verified artifacts

Directory: `releases/0.7.6-beta2` within the active source root.

- `PixelPlayer-0.7.6-beta2-arm64-v8a.apk`: 507,347,684 bytes; SHA-256 `ca8351ac87555bcd9392b5de9bcb3390ed4471cdaa9338876aea7786e9201a29`.
- `PixelPlayer-0.7.6-beta2-armeabi-v7a.apk`: 505,034,009 bytes; SHA-256 `8e3290cd8e3421c45e344317a49f41979a4db799c278dd53bfcba0a22352252f`.

The earlier artifacts above are superseded by the version-code-13 build after validation. The new candidate keeps the same package/signing identity so it upgrades Beta 2. Native ELF checks and required uncompressed model assets remain required; the stem model hash is unchanged.

## Background processing and TPU status

Quiet processing now has a persistent 15-minute WorkManager sweep and does not depend on the app being visible. It enumerates the whole library, ranks the current/recent/favorite songs above never-played songs, limits unattended work to eight jobs per six-hour window, pauses during playback, and lets manual processing cancel queued automatic DSP. It produces no automatic notifications. Instrumental processing remains local-only for unattended work; remote/cloud-only audio is left for an explicit manual action.

The Android LiteRT Google Tensor dispatch runtime and a guarded private model-pack delivery path are present. No compiler-produced Tensor G5 stem model is bundled yet. The app therefore stays on CPU/XNNPACK until a Google Tensor AOT artifact with a validated manifest and CPU numerical parity is delivered; synthetic dispatch or the presence of the runtime library alone is not reported as TPU acceleration. The reproducible Linux/WSL workflow is in `tools/tensor-g5/compile_model.py` and `tools/tensor-g5/RESEARCH.md`.

The first signature-script check expected the older `Signer #1` label; SDK 37 emits `V2 Signer`. The parser was corrected and verification passed with the expected certificate. No APK re-signing or certificate change occurred.

The release folder includes `SHA256SUMS.txt`, `RELEASE_NOTES.md`, `release-manifest.json`, packaging and Android-tool verification reports, and the release `mapping.txt` for later crash deobfuscation. Raw tool reports remain under `build/`. `build/beta2-source-changes.json` records 33 app/config/test changes; no app source files or assets were deleted.

## Plus supporter tier follow-up

The current source also contains a Material 3 Expressive Plus upgrade experience with animated hero shapes, the exact developer story supplied by Hoa, a $15 minimum one-time support amount, and a custom amount picker. Direct/GitHub checkout is injected only at build time with `-PplusCheckoutUrl=...`; the default build opens a non-charging setup dialog. Hosted checkout, payment webhooks, signed entitlements, upload consent, quotas, and deletion policy remain deployment work described in `docs/plus-checkout-deployment.md`.

The seven-feature `data.premium` registry is additive and leaves playback, offline use, lyrics, instrumentals, downloads, and library management free. Experimental settings includes a local password-gated (`1501`) license fixture for creating, activating, and revoking test keys. It is intentionally not a production payment verifier. After these source changes, `:app:compileDebugKotlin` passed and the focused premium suite passed 6/6. The verified version-code-13 APKs in `releases/0.7.6-beta2` do not contain this Plus follow-up and need a new release build before shipping.
