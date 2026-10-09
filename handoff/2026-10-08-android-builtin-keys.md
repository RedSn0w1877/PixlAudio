# Built-in cloud keys on Android (2026-10-08, branch `builtin-cloud-keys`)

Goal: Cloud Studio (instrumentals and word-timed lyrics on RunPod, songs through Cloudflare R2) works on a fresh
install with **nothing to fill in**. The app carries PixlAudio's own keys, encrypted. Anyone can still choose
"Use my own keys" and type their own, as before. This is the Android twin of the iOS change
(`PixlAudio-iOS` `docs/handoff/2026-10-08-baked-keys.md`).

## What it does

- `app/src/main/assets/cloud_defaults.enc` (already on `main`, baked by `tools/cloud/bake-cloud-keys.mjs` in the iOS
  repo) holds the RunPod endpoint ID, a **Restricted** RunPod key (one endpoint only) and an R2 key pair (one bucket
  only). Format `PXCD1`: the five ASCII bytes, a 12-byte nonce, then AES-256-GCM ciphertext + 16-byte tag, no AAD.
  Plaintext is JSON `{"v":1,"runpodEndpointId","runpodKey","r2Endpoint","bucket","r2AccessKeyId","r2SecretAccessKey"}`.
- `data/cloudstudio/CloudDefaults.kt`: the blob layout, the payload (redacted `toString`), `CloudDefaultsCrypto`
  (plain `javax.crypto` AES/GCM/NoPadding), `CloudKeyChoice` (the rules) and `BuiltInCloudConfigProvider` (implements the
  `CloudBuiltInConfig` seam). The provider decrypts **once, on `Dispatchers.IO`**, keeps the result in memory, and gives
  `null` for no key, a wrong key, a tampered/truncated/placeholder blob or an unreadable asset: never a crash. Nothing
  is ever logged or written anywhere.
- The rules (same as iOS): own keys win when "Use my own keys" is on; built-in keys are the default when the build has
  them and they opened; otherwise the old behaviour (fields, off). The consent switch starts **on** with built-in keys
  but nothing is sent until the person picks songs and taps Send on the confirm sheet (it now says the songs go to
  PixlAudio's own RunPod GPU and R2 bucket). Someone who had set up their own keys before (saved keys or an Endpoint ID)
  keeps using them.
- Money with built-in keys: the monthly cap is at most **$3** whatever the field says, the GPU price used for estimates
  and the cap is never below **$0.000192/s**, and jobs removed from the list (Remove, Clear done, retention pruning)
  stay in the month's total (`removed_spend_*` in the `cloud_studio_settings` prefs), so clearing the queue never frees
  cap room.
- Settings › Experimental › Cloud processing: a **Keys** section ("Using PixlAudio's built-in cloud keys" and a
  **Use my own keys** switch). While built-in keys are in use the RunPod/Storage fields, "Forget keys" and the keys-missing
  panel are hidden, and Test connection works with either. The switch is **locked while songs are in flight** (the screen
  disables it and `CloudStudioStateHolder.updateSettings` ignores the change), because their results come back only
  through the keys they went out with.
- With built-in keys the engine never opens the Keystore-backed secret store.

## How the key gets into the build

`CLOUD_DEFAULTS_KEY` (base64 of the 32-byte AES key) is read by `app/build.gradle.kts` from, in this order:

1. the environment variable `CLOUD_DEFAULTS_KEY` (CI: `secrets.CLOUD_DEFAULTS_KEY`, passed to the Gradle steps in
   `.github/workflows/android-ci.yml`),
2. `local.properties` (`CLOUD_DEFAULTS_KEY=...`, git-ignored; the bake script writes it there),
3. empty.

It becomes `BuildConfig.CLOUD_DEFAULTS_KEY` (a plain string; not split into XOR shares, since Gradle can't make
reproducible random shares and the extraction risk is identical). No key (forks, pull requests, local builds without
the property) means no built-in keys: the build still compiles, tests and runs, and Cloud processing looks and works
exactly as before. Other workflows (`phone-release.yml`, `nightly-apk.yml`, `phone-debug.yml`) do **not** pass the
secret yet; add the same `env:` line to their Gradle steps when you want release/nightly builds to carry the keys.

## Limits (honest)

- **Anyone can extract the keys from the APK** (the key is in the dex, the blob in the assets), and CI's debug-APK
  artifact is downloadable by anyone who can see the repository's Actions. That is why the RunPod key is Restricted to
  the one `pixl-cloud-studio` endpoint, the R2 token to the one bucket, the app caps built-in spending at $3 a month per
  phone, and the RunPod balance is the real ceiling. If they are abused: bake new ones (iOS repo script), then delete the
  old ones in RunPod and Cloudflare.
- The $3 cap is enforced by the app only; someone running modified code can ignore it. Keep the RunPod balance small.
- A key that doesn't match the bundled blob counts as "no built-in keys" (the app falls back to the own-keys fields). The
  unit test `the bundled blob fits this build` fails CI if the secret is set but doesn't open `cloud_defaults.enc`, so a
  mismatch can't ship silently (it only checks that the blob opens, never what is inside).

## Tests

`CloudDefaultsTest`: a blob made by Node's crypto (`src/test/resources/cloudstudio/phone/cloud_defaults.test.enc`, a
throwaway key and dummy values, the same layout the bake script writes) opens in the JCE implementation; seal/open round
trip; wrong key, every tampered region, truncation, foreign data, unusable payloads (placeholder, wrong version, missing
field) give null; key parsing; redacted `toString`; the provider (opens once, off the caller's thread, null on any
failure, no key means the blob is never read); the key-choice rules, the $3 clamp and the price floor.
`CloudStudioEngineTest`: built-in keys carry a job without touching secure storage, own keys override, unopened built-in
keys send nothing, cap/price rules in the preview, removed jobs still count against the month.

## Check on the phone

1. Install the APK from the green CI run (artifact `pixlaudio-arm64-debug-apk`).
2. Settings, Experimental, Cloud processing: it should say **Using PixlAudio's built-in cloud keys** with no fields to fill
   in, and the consent switch already on.
3. Send one song (the song's studio card or the Cloud queue, Add, Current song) and confirm. The instrumental/lyrics
   should come back in a few minutes. Optionally flip **Use my own keys** to see the fields appear (only while the queue is
   empty).

## Files

`data/cloudstudio/{CloudDefaults,CloudStudioSettings,CloudStudioSeams,CloudStudioEngine,CloudStudioModule,CloudConfig}.kt`,
`presentation/viewmodel/CloudStudioStateHolder.kt`, `presentation/screens/cloudstudio/*` (settings, confirm sheet,
queue, copy), `presentation/screens/ExperimentalSettingsScreen.kt`, `app/build.gradle.kts`,
`.github/workflows/android-ci.yml`.
