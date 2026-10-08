# 2026-10-08: Cloud Studio on Android (branch `port-cloud-studio`)

A port of the iOS app's Cloud Studio (PixlAudio-iOS `origin/main`, up to d84f8f8 "mark the last job of each submit
burst last_in_batch"). It uses the same RunPod endpoint, the same R2 bucket and the same job schema v1 as iOS. The
worker lives in the iOS repo (`cloud/runpod-worker/`) and serves both apps.

**Not merged.** Another agent is integrating branches into `main`. Android CI (`android-ci.yml`) is green on this
branch: it compiles, runs the unit tests (cloud ones included) and builds the arm64 debug APK.

**Nothing here has run on a phone, or against a real endpoint or bucket, yet.** CI covers it with fakes and the
worker's golden examples only.

## What Hoa gets

Everything below sits under **Settings › Experimental › Cloud processing** (a new row above "Plus license debug
tools").

### The Cloud processing screen

Same order and words as iOS:

- **The consent switch:** "Send songs to my RunPod account".
- **RunPod:**
  - Endpoint ID
  - RunPod key (Restricted)
- **Storage (Cloudflare R2):**
  - R2 endpoint, or the bare 32-character account ID. The account it finds is shown under the field.
  - Bucket (default `pixl-cloud-studio`)
  - Access key ID
  - Secret
- **Test connection:** RunPod (`/health`) and Storage (a tiny file under `probe/`, written, checked and deleted) are
  reported separately.
- **Run selftest (~1¢):** shows the worker's version, GPU and song limits.
- **Outputs:**
  - Instrumental
  - Word-timed lyrics
  - "Write lyrics when none are found (AI transcription)"
  - Standard / Best quality
- **Use mobile data** (off by default).
- **Cost:** GPU price per second and the monthly cap ($3), with "This month: … used or on its way".
- **Cloud queue**
- **Forget keys**

### The Cloud queue

- **Add songs:**
  - Current song
  - Songs without word-timed lyrics
  - Songs without an instrumental (up to 200 songs)
- The list is split into three groups: On its way, Needs you (with Retry), and Done (with Clear done).
- Long-press a finished row to remove it from the list.

### The confirm sheet

Every batch goes through it. It shows:

- songs, minutes, upload MB and estimated cost
- what is left of the month's budget
- how many streamed songs will be downloaded first
- the Wi-Fi rule
- what was skipped, and why

Send is greyed out when the batch is over the cap or the switch is off.

### Other ways in

These appear only once Cloud processing is on:

- A playlist's ⋯ menu: **Process all in the cloud**.
- The Remaster Song card (song info sheet, and Experimental): a Cloud row. It shows the song's latest job, plus a
  **Process in the cloud** button.
- Home's active jobs sheet shows "Cloud processing — Cloud: 3 waiting, 1 processing" while a pass runs.

The automatic studio skips songs that have a cloud job waiting.

## How it works (code)

- **`data/cloudstudio/`** is the port of PixlNet's `Cloud/*` and the iOS orchestrator:
  - `CloudJobSchema`, `CloudJobBuilder`, `CloudQueuePolicy`, `S3Signer`, `RunPodJobsClient`, `CloudObjectClient`,
    `CloudLyrics`, `CloudConfig`, `CloudConnectionTest`
  - `CloudStudioEngine` (the orchestrator), `CloudJobStore` (one JSON file in `noBackupFilesDir`), and
    `CloudStudioSettings`
  - `LiveCloudAudioPreparer`, `FlacEncoder`, `LiveCloudStudioHost`, and `CloudStudioWork` (WorkManager)
- **`presentation/viewmodel/CloudStudioStateHolder`** (a singleton, per CLAUDE.md) and `CloudStudioViewModel`.
- **`presentation/screens/cloudstudio/`** holds the screens.
- **Keys:** the RunPod key and the R2 key pair live only in `EncryptedSharedPreferences` (`cloud_studio_secrets`, AES-256
  under a Keystore key).
  - Unlike the Spotify session, there is no plaintext fallback.
  - Both cloud prefs files are excluded from backups and device transfer.
  - The screen holds keys as a draft in memory only, never in saved state. The draft is saved 0.6 s after typing,
    on leaving the screen, and before a test.
  - Cloud Studio has its own OkHttp client: no logging interceptor, no forced User-Agent, and no redirects.
  - Job bodies never print their presigned URLs.
- **`input.policy.last_in_batch`:** each submit pass is one burst.
  - Each ready job is held until the next one is ready. The last job that actually goes out carries
    `"policy": {"last_in_batch": true}`, and the worker then stops itself instead of idling, billed. That also holds
    when the monthly cap ends the pass early.
  - The jobs before it send no `policy` at all.
  - A single song is a burst of one.
  - Tests: `CloudJobBuilderTest` (the wire shape, the golden `run.request.json`, the burst), `CloudSchemaTest`, and
    four `CloudStudioEngineTest` cases (single, three-song burst, a later job, the cap).
- **The iOS review fixes, kept:**
  - A restart never writes an empty list over the stored jobs: the list is read before any save. Tested.
  - A retried worker error isn't re-read as the next run's answer. The old `manifest.json` and `attempt.json` are
    deleted before the job goes out again. Tested.
  - Streamed songs, and FLAC redos, are always decoded to FLAC. Tested.
  - Worker deadline: `DEADLINE` is retryable and resubmitted with the same upload, and RunPod's own `TIMED_OUT`
    looks for the manifest first. Both match iOS.
  - The WorkManager pass stops before Android's 10-minute limit. Work that a stopped pass interrupted
    (preparing, downloading) is picked up again by the next pass.
- **Built-in keys seam:** `CloudBuiltInConfig`, provided as `NoBuiltInCloudConfig`, which returns null.
  - The engine falls back on it only while the person's own fields are incomplete.
  - The consent switch still gates everything.
  - To ship keys later, provide another implementation in `CloudStudioModule.provideCloudBuiltInConfig`.

## Where Android differs from iOS (on purpose)

| | iOS | Android |
|---|---|---|
| Background | Background URLSession + BGAppRefresh | WorkManager: a pass soon after Send/Retry (any network), a pass when Wi-Fi comes back, and a 15-minute watch while jobs are in flight. While the app is on screen, a light pass every 15 s. A pass promotes itself to a "Cloud processing" notification when Android allows; otherwise it stops after 8 minutes and the next one continues. Force-stopping the app pauses everything until it runs again. |
| Decoded uploads | FLAC at 44.1 kHz stereo (WAV fallback) | FLAC at the song's own rate, stereo 16-bit (own encoder, so no fallback is needed). The worker reads any rate. Hi-res songs ask for FLAC back, because the worker's AAC stops at 96 kHz. |
| AAC-LC passthrough | Frame count from `AVAssetReader` (honours edit lists) | Frame count from the phone's MediaCodec decode, with the edit list's priming and padding removed once. Which way the decoder trims is detected per file. If this ever disagrees with the worker, the job redoes once as FLAC, decoded, which is exact. |
| Upload size on the confirm sheet | Per song (passthrough bitrate, else FLAC) | Always the FLAC estimate, so an upper bound for AAC songs. |
| Importing an AAC result | Decodes it and counts its samples | Checks size + SHA-256, and the worker's own sample count against the phone's count of the upload. A raw MediaCodec count would include the priming that ExoPlayer and ffmpeg both trim. |
| Key storage failure | Keychain | Adds a "Secure storage unavailable" notice (Keystore broken). "Keys missing" appears when keys were saved before but the store is now empty. |
| Strings | English, verbatim | The same English, held in `CloudStudioCopy`, not in `strings.xml`. They aren't translated, like the other Experimental developer screens. |
| Old job records | Dropped when stale | Decoded leniently: every new field has a default. |
| Retry budget | 4 automatic tries over the whole job | Results that arrived reset the count, so a download or lyrics hiccup after several earlier ones can't throw a paid result away. |
| Switching off | Stops new work | Also stops a running pass at its next step and cancels running transfers. A background pass doesn't start a transfer in its last 3 minutes, because a transfer cut off at WorkManager's 10-minute limit restarts from zero. |

## Phone checks for Hoa

You need an endpoint, a bucket and the keys, the same ones as on iOS.

1. **Settings › Experimental › Cloud processing.** Paste the Endpoint ID, the RunPod key, the R2 account ID (or URL),
   the bucket, the access key ID and the secret.
   - The "Account …" line should appear under the R2 field.
   - Turn the switch on.
2. **Tap Test connection.** RunPod and Storage should both show a green tick, each with its own sentence.
3. **Tap Run selftest.** After a minute or so it should say "The worker answered" and show the GPU and limits.
4. **Leave the screen and come back.** The keys should still be there, as dots (the eye icon shows them).
5. **Play a local song, then Cloud queue › Current song.** The confirm sheet shows 1 song, a few MB and about $0.01.
   - Tap Send 1 song.
   - The row should go Preparing → Uploading → Waiting for a GPU → Separating vocals → Downloading → "Done ·
     instrumental + word-timed lyrics", with the cost and the GPU.
   - **Check that the worker shuts down afterwards.** RunPod's console should show the worker stopping within
     seconds of the job, not idling (that's `last_in_batch`).
6. **Open that song's lyrics.** They should be word-timed. Sing mode should use the new instrumental.
7. **Try a playlist's ⋯ › Process all in the cloud with 3 or 4 songs.** In RunPod, only the last job of the batch
   should make the worker stop. The earlier ones keep it warm.
8. **Try a Spotify (streamed) song.** The confirm sheet should say it is downloaded first. The upload should be
   a FLAC: a 4-minute song is about 25–35 MB in the bucket's `in/` while it waits.
9. **Turn Use mobile data off, go to mobile data, and send a song.** The queue should say uploads wait for Wi-Fi.
   Turning Wi-Fi on should carry on by itself.
10. **Close the app (swipe it away, don't force-stop) while a job is "Waiting for a GPU".** Come back after 15–20
    minutes: the result should have arrived, and the queue should still list every job.
11. **Set the monthly cap to $0.01.** The confirm sheet should say "Over this month's cap" with Send greyed out.

If anything fails, the row's text (or Home's active jobs sheet) names the step.

## Open risks

- **Slow uplinks and big FLACs in the background.** A 100 MB upload that takes longer than a background pass, once
  Android refused the foreground notification, never finishes there. It does finish while the app is open, or
  under the notification after tapping Send.

- **AAC passthrough frame count on real phones** (Pixel 10 Pro, Codec2 AAC decoder). If it's wrong, every
  local-AAC song does one extra FLAC run, which costs about twice the GPU time. To check, after step 5 with a local
  `.m4a`, the job's warnings should not include "The AAC instrumental didn't line up". If they do, force
  `Route.DECODE` in `LiveCloudAudioPreparer.route`.
- **The foreground notification.** From the background, Android 12+ refuses to promote a pass to a foreground
  service, so long batches proceed in 8-minute passes. They are slower, but nothing is lost.
- **Previews of large libraries.** "Songs without word-timed lyrics" reads each song's stored lyrics until it has
  200. On a big library this takes a few seconds behind the "Looking through your library" spinner. It runs off
  the main thread.
