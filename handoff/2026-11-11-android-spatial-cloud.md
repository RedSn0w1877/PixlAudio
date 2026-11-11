# Android Spatial audio: CLOUD (handoff, 2026-11-11)

Branch `audio-cloud`, cut from `audio-hq` 50cf187. Contract: `handoff/2026-11-10-plans/cloud-audio-spec.md`
("§" below); manual: `handoff/2026-11-10-plans/android-integration-map.md` ("map"). Nothing here ran on a phone:
every check is a JVM test in GitHub Actions (`android-ci.yml`). The status of the last run is at the end.

## What is in

### The library (`data/spatial/library/`, bound in `SpatialLibraryModule`)

- `CloudSpatialLibrary` implements `SpatialLibrary` over Cloud Studio:
  - `cloudRendersOn` = Cloud Studio's master switch (`CloudStudioSettings.settings.enabled`) and `spatial.cloud`,
    false until the stored settings were read once.
  - `localRender` = the local store, from memory or (before the index is read) one manifest read and a size check.
  - `request` returns at once; in the library's scope it runs §6.2 in order: nothing while either switch is off;
    a render already on the phone needs nothing; on a network the person allows for cloud work (connected, and
    unmetered or "Use mobile data" on) a signed GET of `lib/v1/<videoId>/sp-<profile>.json`; a manifest that is
    the one asked for (§3.1) brings the FLAC down (signed GET of the canonical key) and the store takes it once
    bytes and SHA-256 match; else a library job through Cloud Studio's engine. A want that can't start waits as
    `WAITING_FOR_NETWORK` (resumed when the network changes) or `CAP_REACHED` (re-checked every 15 minutes and
    whenever the cap setting changes). At most 200 waiting wants are kept (oldest dropped).
  - `status(videoId, profile)` = `SpatialStatusRules.status(...)`: a render on the phone is `READY`; else what the
    library is doing (lookup/sending = `QUEUED`, download = `DOWNLOADING`, the two waits); else the song's pending
    library job (preparing/uploading/waiting for a worker = `QUEUED`, or `WAITING_FOR_NETWORK` when its network rule
    doesn't hold; uploaded but over the library cap = `CAP_REACHED`; GPU = `PROCESSING`; results ready or coming down
    = `DOWNLOADING`, or `WAITING_FOR_NETWORK`); else a recorded failure = `FAILED`; else `NONE`. Never `PREVIEW`.
    Runs on `Dispatchers.Default`.
  - Failures: `SpatialFailureLedger` (preference file `spatial_library`, out of backups) keeps per song and profile
    the failures in a row and the last success; a new attempt waits 10 minutes, 1 hour, then 1 day (§6.1). A failed
    cloud job, a render the worker reports `failed`, a failed lookup or a damaged download count; a delivered render
    or a shared-library hit clears it. Idempotent across restarts (a job's record is read again and again).
  - `events` = the store's (`RenderReady` after every verified install, `Removed(videoId)` per evicted song,
    `Removed(null)` after `removeAll`). `stats` = the store's songs and bytes, the library's committed month
    (`spentThisMonthMicroUsd`) and the background queue's day count (`sentToday`). `markPlayed` and `removeAll` go to
    the store; `removeAll` never touches the shared library.
- `SpatialRenderStore`: `filesDir/cloud_audio/<videoId>/sp-<profile>.flac` + `sp-<profile>.json`. A render comes in
  only through `installRender` (manifest = the one asked for, file size and SHA-256 = the manifest's); the FLAC is
  moved in first and the manifest written last. The index is read once at start (manifest checks + file size; the
  SHA-256 was checked on the way in), clearing leftovers (a FLAC without a manifest, `.tmp` files, a manifest whose
  file has another size, another library version's renders, stray folders). The budget (`cloudAudio.storageMB`,
  re-applied when the setting goes down) removes least-recently-played songs first, all of a song's renders
  together, never the song played last nor the one just stored. The played time is the manifest file's
  `lastModified` (no database). `cloud_audio/` and `spatial_library.xml` are excluded from cloud backup and
  device transfer in both XML files.
- `SpatialRenderManifest` / `SpatialManifests`: the §3.1 manifest and the one acceptance rule (`schema`, `v`, `libV`,
  `videoId`, `kind`, `profile`, the audio at its canonical key, a size, a 64-hex SHA-256, a rate and a length), plus
  the `SpatialRender` facts (rate, frames, duration, `headroomDb` with 4.0 as fallback, source codec, stem mode,
  loudness).

### Library jobs in Cloud Studio (`data/cloudstudio/`)

- Schema: task `spatial` (no `output.put` slot), `input.library` (`v`, `videoId`, `source {itag, codec}`, `renders`
  with `audioPut`/`manifestPut`/`manifestGet`), `output.codec`/`kbps` left out when a job has no audio slot (as the
  worker's example), result `library` (`videoId`, `libV`, `renders`, `stemMode`), `models.stems6`,
  `timings.stems6Ms`/`renderMs`, progress labels for `stems6` and `render`.
- Builder: the library block with the canonical keys from `CloudKeys.libraryAudio/libraryManifest` (built from
  `SpatialContract.LIB_V`), 1 to 6 known, unique profiles, `source` with whatever of itag and codec is known (none:
  no `source`), `policy.executionTimeout` 1 800 000 ms and worker URLs signed for `ttl + 1 800 s + 1 h` for any job
  with the `spatial` task. Sing's jobs are byte-for-byte what they were (the existing golden test still passes).
- Record: `CloudJobRecord.library: CloudLibraryJob?` (video, profiles, `ahead`, source itag/codec, the result's
  renders and stem mode, the profiles imported); origin `LIBRARY`. Every new field has a default (old files decode).
- Engine (`CloudStudioEngine`):
  - `sendLibraryJob(songId, videoId, profileId, ahead)` → `Sent` / `CapReached` / `Refused`: refuses with the master
    or `spatial.cloud` off, a bad video id or profile, a missing or over-15-minute song; one pending job per video
    and profile (a play promotes the background queue's job to the person's); checks the library cap with every
    library job not yet at RunPod counted; counts a new background job in the day's ledger.
  - Prepare: the song must still play the job's video; the audio is `CloudStudioHost.libraryAudioSource` (the copy
    on the phone, else a fresh copy into the listening cache, never a permanent download); the same preparer and
    decode-to-FLAC rule as Sing (a streamed song is decoded at its own rate); `library.source.itag` from the stream
    proxy's cached URL (`CloudStreamProxy.cachedStreamItag`), the codec from the source's MIME type
    (`CloudPreparedAudio.sourceCodec`).
  - Lanes: a played song's render goes with the person's batches (lane 1), the background queue's in lane 2; none
    is ever priority. Library jobs waiting in RunPod's queue step aside for a Sing (`pausable`), new ones wait behind
    a Sing still on the phone (`isHeld`), and a Sing interrupts their uploads.
  - Network: a played song's job follows the person's rule ("Use mobile data"); the background queue's need an
    unmetered network, a battery that isn't low and Data Saver off (`libraryAheadAllowed`), no charger. With
    `spatial.cloud` off nothing of a library job moves; a job RunPod already finished is ended without download
    (its renders stay in the shared library for a later lookup).
  - Money: Sing's cap (`committedThisMonthMicroUsd`, the CAP_REACHED notice, the $3 clamp) counts only non-library
    jobs; library jobs have `libraryCommittedThisMonthMicroUsd` (their recorded costs, their estimates at RunPod,
    removed library jobs' spend in its own ledger) under `cloudAudio.monthlyCapUSD` (`null` = no limit). A library
    job over its cap waits uploaded, never as a failure, and the burst carries on. Cost figure:
    `CloudCost.WARM_SECONDS_SPATIAL` = 87 s per warm 4-minute song (the worker example's 73 s for 3:22, scaled; not
    benched).
  - Collect: the result's `library` block; every `ok`/`exists` render not on the phone is fetched with its own
    manifest from its `lib/` key and handed to the store; a job manifest's `sp-<profile>` entry must name the
    canonical key; a render gone from the library before it came down sends the job round again; a damaged one is
    fetched again (retry ladder); `in/` and `out/<jobKey>/` are deleted as for any job, `lib/` never.
  - Sing ignores library jobs (`hasPendingJob`, `jobsFor`, `startSing`'s join, the batch preview and
    `sendAuto`, Sing's auto ranker's pending set).
  - Quiet: library jobs never count as "work for the person" (no foreground notification); the pass worker's
    Active jobs line counts them apart: "Cloud: 1 waiting · Spatial audio: 2 waiting".
  - Shared-library access for the library: `fetchLibraryObject` / `downloadLibraryObject` (only `lib/` keys, signed
    with the current keys, nothing while the master switch is off).
- Settings ledger (`CloudStudioSettings`): removed library jobs' spend and the background queue's day count, in the
  same excluded preference file as Sing's ledger.

### The background queue (`data/spatial/library/SpatialAhead.kt`, §6.4)

- `SpatialAheadScheduler`: a periodic clock (`SpatialAheadTickWorker`, every 2 hours, unmetered, battery and storage
  not low, untagged) that asks for one run (`SpatialAheadWorker`, tagged as a job: Home's Active jobs shows a
  "Spatial audio" row while it works; no notification). On while a profile is selected, prepare ahead is on and
  both cloud switches are; synced at app start (`PixelPlayApplication`) and on every settings change (the library).
- `SpatialAheadRunner`: Sing's prepare-ahead ranking (`CloudAutoPrepare.rank` with the instrumental and Sing-job
  facts taken out, so its other rules stay: 1 to 8 minutes, skip rate, blocked artists), the selected profile only,
  matched songs only. First lookups over the best 40 (at most 12 renders downloaded per run), then jobs for the
  misses in rank order within `cloudAudio.dailyAhead` (at most 8 per run) and the library cap; stops when the
  conditions change mid-run or at its 8-minute budget.

### Smaller pieces

- `CloudStreamProxy.cachedStreamItag(id)` + `StreamItag.fromUrl` (pure). `SpotifyStreamProxy` needed no change.
- `JobTags`: `PixelPlayJobKind.SPATIAL_AUDIO` ("Spatial audio").
- Cloud queue: library rows read "Spatial audio · Studio · Artist", "Done · Spatial audio"; the song's "Process in
  cloud" button and Sing's status line ignore library jobs.

## Tests (JVM)

`CloudLibrarySchemaTest` (builder vs `job.input.spatial.json` and `run.request.spatial.json` URL for URL; results
round-trip; refusals; keys; timing; cost; budget), `CloudLibraryJobsTest` (the engine end to end on fakes: one job
per video/profile and promotion; library cap vs Sing's; uploaded-over-cap waits without Sing's notice; ledgers;
`spatial.cloud` off; partial and `exists`; render gone; damaged download; ahead conditions; "Use mobile data"; Sing
first and never joined), `SpatialRenderStoreTest` (manifest acceptance and rejection; install; index after restart
and leftovers; eviction order and protections; remove all), `SpatialLibraryRulesTest` (status rules, backoff,
ledger, networks, ahead rules), `CloudSpatialLibraryTest` (switches, shared library first, job states, network and
cap waits and resumes, backoff, ledger from job outcomes, promotion, the queue's lookups, stats),
`SpatialAheadRunnerTest`, `StreamItagTest`.

The worker's examples were copied byte for byte (git blob ids equal) from PixlAudio-iOS `origin/audio-worker`
@ b6f8eda into `app/src/test/resources/cloudstudio/worker/` (next to the older examples, where `CloudFixtures`
reads): `job.input.spatial.json`, `job.input.spatial.combined.json`, `job.result.spatial.json`,
`job.result.spatial.exists.json`, `job.result.spatial.partial.json`, `library.render.spatial.json`,
`run.request.spatial.json`. At that commit they carry nothing of Enhanced audio (a test checks it).

## Divergences and decisions (please check)

1. Status `FAILED` stays until a new attempt starts (the next play after the backoff, or the background queue),
   not until the backoff runs out; the library doesn't retry a failure by itself. `WAITING_FOR_NETWORK` and
   `CAP_REACHED` do resume by themselves.
2. The store's index checks size, not SHA-256, at start (the hash was checked on the way in).
3. Eviction removes a whole song (all its profiles), so `Removed(videoId)` is exact; the contract's event can't say
   "one profile of a song".
4. Library jobs never make a song a permanent download (Sing's jobs do): the preparer and the decode rule are
   Sing's, the audio source isn't. A cached file is keyed by Spotify id: after a re-match it can be the old video's
   (the same risk Sing has today).
5. `stats.spentThisMonthMicroUsd` is the library's committed figure (spent + estimates at RunPod + removed jobs),
   the number its cap uses.
6. `library.source.itag` is only known while the proxy holds a URL for the song (the song played lately); without
   it only the codec is sent, and without either no `source`.
7. `spatial.cloud` off cancels library jobs still on the phone and ends finished ones without downloading; the master
   switch alone leaves Cloud Studio's queue as it is (Sing's behaviour).
8. Background queue: 2-hour cadence, 8 jobs and 12 downloads per run, 40 candidates; unmatched songs are skipped (a
   song is matched on its first play).
9. The cost estimate of a render (87 s per 4-minute song) is a guess from the worker's example until the first
   bench (§9).
10. `CloudOutputRequest.codec` became optional (left out for spatial-only jobs, as the worker's example).

## What others need to do

- ENGINE: nothing new beyond the contract: `localRender` on the load path, `request` when a song plays with
  Spatial active and has no render, `markPlayed` when a render starts, and `events` (`RenderReady` for the swap,
  `Removed` to leave a deleted render). `request` is cheap to call again (it dedupes).
- UI: nothing required. `stats` and `cloudRendersOn` behave as the contract says; `stats.sentToday` is the
  background queue's count.
- Lead: compare with iOS on the decisions above (FAILED line, per-song eviction, permanent downloads, lanes,
  cadence, cost figure). Nothing in the frozen contract had to change.
