# PixelPlayer handoff for Claude — September 9, 2026

## Stop point

The user asked to pause and hand this work to Claude. Do not continue implementation or phone testing until Claude reads this file and the user asks to resume.

Active source tree:

`C:\Users\Hoa Vo\Downloads\Code Projects\PixelPlayer-master\beta2-release`

Do not edit the older copies under `Downloads\PixelPlayer-master\PixelPlayer-master` or the original Downloads checkout. The active tree is the one containing the current Beta 2 work.

The last focused test command was interrupted by the user while it was running. A fix had just changed `CloudStudioConfig` URL validation from Android `Uri` to `java.net.URI`; that fix has not yet been validated. There may be Kotlin daemon Java processes left from the interrupted Gradle process. Do not start another Gradle command until the workspace is confirmed idle.

## User’s product direction

The user wants PixelPlayer to ship Beta 2 later today. Existing playback, downloads, offline behavior, word-synced lyrics, instrumental persistence, playlist ordering, and YouTube Music playback were manually tested by the user and reported working.

They asked whether Plus can have seven real features:

1. Cloud Studio — hosted high-capacity instrumental/lyric processing.
2. Deep Discovery — larger recommendation candidate pools.
3. Smart Playlist Tools — mood, energy, era, and listening-context playlists.
4. Pro Background Studio — larger quiet-processing capacity while idle/charging.
5. Advanced Audio Exports — higher-quality stem bundles and metadata.
6. Insight Lab — richer playback/recommendation/processing diagnostics.
7. Supporter Themes — animated visual themes and supporter accents.

Distribution decision: **Direct/GitHub APK with hosted checkout and custom support amount**, not Google Play. The minimum is a one-time $15 contribution; larger amounts are allowed and grant the same permanent Plus tier. There are no payment credentials, checkout URL, merchant account, or backend in the repository.

The user also explicitly requested a local debug license panel protected by password `1501`, where they can create, activate, and deactivate test keys.

## Work completed since the earlier Beta 2 handoff

### Earlier Beta 2 work already present

- Automatic quiet processing runs outside the visible app using a persistent WorkManager sweep.
- It covers the whole library, prioritizes current/recent/favorite songs over never-played songs, limits unattended work, pauses during playback, and emits no automatic notifications.
- Home has fuller adaptive shelves and outside-library discovery paths.
- Playback prefetches the next URL, shares resolution work, bounds URL refresh, and retains YouTube identities/headers/fallbacks.
- FFT scratch reuse reduces instrumental processing allocations.
- Version-code-13 Beta 2 APKs were built and verified before this Plus work.
- The bundled stem model still runs CPU/XNNPACK. LiteRT Google Tensor dispatch and a guarded private model-pack path exist, but no vendor-compiled Tensor G5 artifact is bundled. TPU acceleration must not be claimed.

### Plus screen and checkout hook

Added/modified:

- `app/src/main/java/com/theveloper/pixelplay/presentation/screens/PlusScreen.kt`
- `app/src/main/java/com/theveloper/pixelplay/presentation/navigation/Screen.kt`
- `app/src/main/java/com/theveloper/pixelplay/presentation/navigation/AppNavigation.kt`
- `app/src/main/java/com/theveloper/pixelplay/presentation/screens/SettingsScreen.kt`
- `app/src/main/java/com/theveloper/pixelplay/MainActivity.kt`

The screen uses Material 3 Expressive styling: a gradient hero, animated drifting circles, pulsing supporter icon, large rounded cards, custom amount picker, and the supplied heartfelt Hoa message verbatim. It has seven roadmap cards and local Insight Lab/Smart Mix entry buttons. The entry buttons currently route Insight Lab to the existing Stats screen and Smart Mix to the Library screen; the pure local engines described below are available for deeper integration.

`PlusCheckoutConfig.kt` reads an optional build-time `PLUS_CHECKOUT_URL` generated from `-PplusCheckoutUrl=https://...`. It validates HTTPS and appends `amount` and `currency=USD`. The default source build has no URL, so the CTA explicitly opens a non-charging setup dialog. No payment secret or fake purchase flow is present.

Deployment documentation:

- `docs/plus-checkout-deployment.md`
- `docs/premium-tier-plan.md`
- `docs/plus-cloud-studio.md`

The hosted service still needs to perform payment/webhook verification, issue signed permanent entitlements, enforce quotas, obtain upload consent, and publish retention/deletion/refund information. Do not enable a live checkout URL until those pieces exist.

### Entitlements and debug license tools

Added:

- `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumEntitlements.kt`
- `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumFeatureLimits.kt`
- `app/src/main/java/com/theveloper/pixelplay/data/premium/PlusLicenseManager.kt`
- `app/src/main/java/com/theveloper/pixelplay/presentation/screens/PlusLicenseDebugScreen.kt`

`PremiumEntitlements.kt` defines permanent/expiring `PremiumEntitlement`, `PremiumTier`, entitlement sources, and a stable seven-feature registry. The convenience unlock check now respects expiry.

`PremiumFeatureLimits.kt` defines useful free/Plus capacities:

- Free background jobs/window: 8; Plus: 32.
- Free discovery candidates: 48; Plus: 240.
- Free export bitrate limit: 192 kbps; Plus: 320 kbps.

`PlusLicenseManager` stores local fixture keys in app-private SharedPreferences, generates keys like `PP-PLUS-XXXXXXXX-XXXXXXXX`, activates/revokes them, and maps the active key to a `LOCAL_DEBUG` `PremiumEntitlement`. These keys are explicitly not payment proof and are not trusted by a production server.

Experimental Settings now exposes “Plus license debug tools.” The screen asks for password `1501` and lets the developer create/activate/revoke local keys. This is a testing fixture, not a production license verifier.

### Local Plus engines

Added `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumSmartTools.kt`.

`PremiumSmartPlaylistEngine` provides deterministic, explainable local presets:

- Favorites refreshed
- Deep discovery
- Recently added
- Short listening
- Long listening
- Artist radio

It deduplicates recordings and uses the existing `MusicRecommendationEngine` for ranking/exploration. `PremiumInsightEngine` creates privacy-preserving local summaries: song count, played count, listening time, completion rate, discovery rate, top artists, and top genres. The Plus screen preview card exposes navigation to existing Stats/Library surfaces, while deeper preset creation still needs a product-UI integration if desired.

### Cloud Studio and exports

Added:

- `app/src/main/java/com/theveloper/pixelplay/data/premium/CloudStudioClient.kt`
- `app/src/main/java/com/theveloper/pixelplay/data/premium/AdvancedAudioExporter.kt`

`CloudStudioConfig` requires explicit upload consent, validates HTTPS endpoints (private HTTP is allowed only for local development), validates source files, and delegates to the existing Gradio/direct POST stem clients. `ConfiguredCloudStudioClient` returns a safe `Unavailable` result so callers can fall back to on-device processing.

`AdvancedAudioExporter` atomically creates a ZIP containing instrumental WAV, optional vocals WAV, and metadata JSON. Callers are responsible for checking entitlement before invoking it; existing playback and basic instrumentalization remain free.

## Validation already completed

- `:app:compileDebugKotlin` passed after the Plus UI, checkout config, and debug license panel were added.
- The earlier focused `PremiumEntitlementsTest` passed 6/6 before the later feature additions.
- The combined source compile after the local/cloud feature additions passed (`build/plus-features-compile.log`).
- The first combined feature test run hit a test-only deprecated `createTempDir`; it was replaced with `kotlin.io.path.createTempDirectory`.
- The next test run compiled but failed because stale generated `app/build/test-results/testDebugUnitTest/binary` could not be deleted. That directory was removed and a retry was started.
- The retry then ran 12 tests and found one real test failure: `CloudStudioAndExportTest` expected `https://example.com` to validate, but Android `Uri` host parsing returned an empty host in the local JVM test environment. Production code was changed to use `java.net.URI` instead. The next retry was interrupted by the user before a result was available.

Therefore the final focused test status is **not yet known after the `java.net.URI` fix**. Do not report all Plus tests as passing until this command completes successfully:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:JAVA_TOOL_OPTIONS='"-Djdk.net.unixdomain.tmpdir=C:\Users\Hoa Vo\Downloads\Code Projects\PixelPlayer-master"'
.\gradlew.bat :app:testDebugUnitTest `
  --tests 'com.theveloper.pixelplay.data.premium.PremiumEntitlementsTest' `
  --tests 'com.theveloper.pixelplay.data.premium.PremiumSmartToolsTest' `
  --tests 'com.theveloper.pixelplay.data.premium.CloudStudioAndExportTest' `
  --no-daemon --no-parallel --max-workers=2 --console=plain '-Pksp.incremental=false'
```

If generated-output deletion fails again, run `gradlew.bat --stop`, ensure no Gradle/Kotlin process is writing the tree, remove only the relevant generated directories under `app/build`, and rerun once. Do not run concurrent Gradle commands.

## Release state and important limitations

The verified version-code-13 APKs in `beta2-release/releases/0.7.6-beta2` predate all Plus source changes. They do **not** contain the Plus screen, checkout hook, debug license panel, or new local/cloud feature utilities. A new release build is required before shipping these changes.

Do not build a live-payment APK until the hosted checkout URL and server verifier exist. A build with no `plusCheckoutUrl` remains safe preview mode. Do not claim Cloud Studio is available unless an endpoint is configured and the user has opted in. Do not claim TPU acceleration; the Tensor G5 AOT compiler artifact is still missing.

## Files to review first

1. `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumEntitlements.kt`
2. `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumFeatureLimits.kt`
3. `app/src/main/java/com/theveloper/pixelplay/data/premium/PlusLicenseManager.kt`
4. `app/src/main/java/com/theveloper/pixelplay/data/premium/CloudStudioClient.kt`
5. `app/src/main/java/com/theveloper/pixelplay/data/premium/PremiumSmartTools.kt`
6. `app/src/main/java/com/theveloper/pixelplay/data/premium/AdvancedAudioExporter.kt`
7. `app/src/main/java/com/theveloper/pixelplay/presentation/screens/PlusScreen.kt`
8. `app/src/main/java/com/theveloper/pixelplay/presentation/screens/PlusLicenseDebugScreen.kt`
9. `app/src/main/java/com/theveloper/pixelplay/presentation/navigation/AppNavigation.kt`
10. `docs/plus-checkout-deployment.md`

## Immediate next step for Claude

Resume only after the user asks. First run the focused test command above sequentially, inspect any source failure, then decide whether to integrate the local smart tools more deeply into playlist creation/Stats. Keep all existing playback, lyrics, instrumental, offline, download, and library behavior free and intact. Do not invent payment credentials, a hosted URL, a server entitlement, or TPU activation.

## Muselle recommendation tiers (September 9 follow-up)

Added `data/recommendation/Muselle.kt` with two named tiers. `Muselle` is the default free tier and delegates to the existing explainable on-device learner. `Muselle2` is the Plus tier: it reranks the same private signals with novelty, listening-session duration fit, era affinity, and artist/genre diversity. It remains deterministic and explainable and does not require a model download or network service. `HomeRecommendationPlanner` now accepts a tier, and `HomeDiscoveryStateHolder` selects Muselle 2 for an active Plus entitlement; free users continue using Muselle. Plus smart tools use the Muselle 2 reranker.

Validation: `:app:compileDebugKotlin` passed after this change.

Home planning now adds local Quick listens and Settle in shelves based on track duration, filling Home with context-aware sections even when remote discovery is unavailable. `:app:compileDebugKotlin` passes after this update.

Home now also includes an `On repeat` shelf ranked from plays, completions, and voluntary-play signals, giving the page a familiar Spotify-like return path.

The Home planner now adds Recently added and an Artist radio shelf. AutomaticStudioManager also applies the existing free/Plus background job budget dynamically: free users retain the paced limit, while active Plus entitlements receive the larger configured quiet-processing budget. Debug compilation passes.

Home shelf cards now animate their selected state with Material motion: active tracks transition to a raised tonal container, making the current song obvious without adding visual noise. Offline empty-state copy was corrected and now explains that downloaded songs remain playable without a connection. Debug compilation passes after the animation change.

Additional Home polish: Recently added and Artist radio shelves, On repeat shelf, Quick listens and Settle in context mixes, and animated active-track card color/elevation. Offline empty-state copy now clearly explains downloaded playback. AutomaticStudioManager uses the Plus-aware background job budget.
