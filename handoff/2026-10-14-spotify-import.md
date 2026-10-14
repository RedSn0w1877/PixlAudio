# Spotify import: slow import + "Spotify won't hand playlists over" (2026-10-14)

Branch `fix-spotify-import` (from main 49aa403). Area: `data/spotify`, `data/network/spotify`,
`data/worker/Spotify*Worker`, `data/database/SpotifyDao`, `presentation/spotify/dashboard`.

## Root causes (file:line are on main 49aa403)

1. **The scary banner was a false diagnosis.** `SpotifyRepository.kt:370-379` (`syncPlaylistSongs`)
   set `_playlistAccessDenied = true` on ANY 403 from a playlist, and
   `SpotifyDashboardScreen.kt:532` turned that into "Spotify is refusing to hand over the
   songs inside your playlists ... your login is from before the app asked for that
   permission". But since Spotify's Feb/Mar 2026 Web API migration, `GET /playlists/{id}/items`
   returns 403 for every playlist the user neither owns nor collaborates on (followed
   playlists, Spotify's own Daily Mix etc.), whatever the scopes. Every account that follows
   someone else's playlist got the banner on every sync, and the whole playlist was counted as
   a failure. Source: developer.spotify.com Get Playlist Items ("only accessible for playlists
   the current user owns or collaborates on", 403 otherwise) and the Feb 2026 migration guide.
2. **Page size 100 on playlist items** (`SpotifyApiService.kt:82`, `SpotifyRepository.kt:1080`
   `TRACK_PAGE_SIZE = 100`). Spotify documents max 50 for Get Playlist Items; 100 is answered
   with `400 Invalid limit` (community reports). The old code then retried the same 400 without
   `fields` and gave up, so even owned playlists could import empty. Now 50, with a one-time
   step-down to 20 if Spotify ever rejects the limit.
3. **Playlist song count read from the wrong field** (`SpotifyModels.kt`, `tracks.total`): the
   Feb 2026 migration renamed the playlist's `tracks` object to `items`. Added `items`
   (fallback `tracks`) and `snapshot_id`.
4. **Strictly serial, all-or-nothing import** (`syncAllPlaylistsAndSongs`, old lines 451-515):
   one playlist at a time, a whole playlist (all pages + all artist lookups) held in memory
   and only then written, the whole unified library rebuilt every 4 playlists, Liked Songs
   fully fetched before anything was visible, no way to skip unchanged playlists.
5. **Wasted calls**: after every playlist `fetchArtistGenres` called the batch `GET /artists`,
   which Spotify removed for Development-Mode apps (Feb 2026). Each playlist burned
   `ceil(artists/50)` failing requests, serially, through the same limiter.
6. **Rate limiting** (`apiCall`, old 122-183): `Retry-After` clamped to 60 s and only 3 attempts,
   so a long 429 just returned `null` for every queued call; 403/404/429/5xx/network all
   collapsed to `null`; 401 refresh could run N times with parallel callers (rotating refresh
   token).
7. **Matching ordered by `spotify_id`** (alphabetical, keyset cursor), started only through the
   repository observer, rebuilt the whole per-song match map on every DB write
   (quadratic during import), and wrote results only after each batch of 48 finished.

## What changed

- `data/spotify/SpotifyApiExecutor.kt` (new): one shared limiter (120 ms spacing + penalty window),
  typed `SpotifyResult` (Ok / Forbidden / NotFound / BadRequest / Unauthorized / RateLimited /
  ServerError / NetworkError). 401: refresh once (`SpotifyAuthManager.refreshAfterRejected`, which
  does not spend the rotating refresh token again if another call already replaced the access
  token), then Unauthorized. 429: honour `Retry-After` for ALL callers; waits over 90 s are not
  slept through (RateLimited + fail-fast for everyone else). 5xx/network: 3 attempts, 0.8/1.6 s.
  400/403/404 returned as-is.
- `SpotifyRepository`:
  - Liked Songs first, page by page (each page upserted + published as it lands); unchanged
    likes (total + newest track) cost 1 request.
  - Playlists: owner-id check up front. Playlists you do not own or collaborate on are NOT
    requested; they are listed as "not imported" with the reason. A 403/404 on one playlist skips
    that playlist only. Playlist rows for unreadable playlists left behind by older builds are removed.
  - 3 playlists in flight (semaphore) under the shared limiter, 50 per page, upsert per page,
    rows of songs no longer in the playlist removed only after the last page, publish to the
    unified library throttled to once per 1.5 s (partial path), mirror to app playlists per playlist.
  - Incremental + resumable: `snapshot_id` per playlist remembered in `SpotifyImportStore`
    (SharedPreferences JSON, no Room schema change). An interrupted pass resumes by skipping
    what already finished.
  - `reconnectRequired` replaces `playlistAccessDenied` and is raised ONLY for a dead login
    (401 after refresh) or when the stored token provably lacks `user-library-read`,
    `playlist-read-private` or `playlist-read-collaborative` (`SpotifyAuthManager.IMPORT_SCOPES`,
    `missingScopes`). A plain 403 never raises it.
  - Batch artist-genre lookup is no longer part of import and switches itself off after the
    first refusal.
  - Match-state observer sampled to 1 Hz; matcher kicked at most every 30 s.
- `SpotifyDao`: `upsertImportedSongs` (re-reads match results inside the transaction, because
  matching now runs while the import still writes), `removeSongsNotIn`, `getSongsByPlaylistList`,
  `getPendingSongsByPriority` (browse picks, then Liked newest first, then the rest newest first).
- `SpotifySyncWorker`: progress as `percent` + `detail` (shows in Active jobs: "Name . 4/20
  playlists . 312 songs . 2 skipped"), a 429 reschedules itself after Spotify's Retry-After
  (capped 15 min, max 24 follow-ups), a dead login does not reschedule, `CancellationException`
  no longer swallowed.
- `SpotifyMatchWorker`: priority order, results written as each song completes, shared adaptive
  pacer (60 ms .. 2 s gap, doubles while YouTube fails), in-run cache by ISRC / title+artist+length,
  5 in parallel (2 while playing), "Matching N/M songs" progress with a growing total. The
  InnerTube client profiles were not touched.
- Dashboard: truthful "Reconnect Spotify" card; new "N playlists not imported" card with
  per-playlist reasons; import progress bar; "Matching N/M songs" line; ready/looking counters
  now refresh while matching runs.
- Tests: `SpotifyApiExecutorTest` (429 Retry-After, long penalty fail-fast, exponential backoff,
  401 refresh once, 403/404/400, 5xx/network bounds), `SpotifyImportTest` (403 on one playlist does not
  abort, not-owned never requested, reconnect only with a missing scope, Liked first, paging at 50 +
  progressive publish, limit fallback, snapshot skip, rate-limited abort with retry-after,
  dead login, unchanged likes), `SpotifySnapshotRetentionTest` updated to the new semantics.

## Plain phone checks (Pixel 10 Pro)

1. Update, open Spotify dashboard, tap **Sync**. Liked Songs should appear in the library within a
   few seconds and grow while the progress bar moves; "Matching N/M songs" should start before the
   import ends.
2. The red "hand over" banner should be gone. If you follow other people's playlists you should see a
   blue "N playlists not imported" card saying Spotify only lets this app read playlists you own or
   collaborate on (that is a Spotify rule, not a bug).
3. Tap Sync again: it should finish in seconds (unchanged playlists are skipped).
4. Home > Active jobs shows the Spotify sync/matching rows with a percentage and detail text.
5. Songs from the top of Liked Songs should become playable first.

## Not verifiable without his account / on CI

- Whether Spotify really answers 400 to `limit=100` on `/items` for his app (docs + forum say so;
  50 is safe either way) and whether non-owned playlists are really 403 for his client id (docs say
  yes for all apps). If a non-owned playlist is in fact readable, it would now be skipped by the owner check.
- Real 429 behaviour and Spotify's dev-mode rate budget (3 playlists in flight, ~8 req/s max).
- The `GET /artists` removal for his app (we stop calling it after the first refusal anyway).
- On-device speed numbers and the Active jobs rendering.
- Premium requirement: dev-mode apps need the app OWNER on Premium; if that lapses every call fails
  (surfaces as 403 on `/items` and would show per playlist as "refused", not as reconnect).
