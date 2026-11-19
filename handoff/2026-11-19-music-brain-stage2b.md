# Music brain, stage 2b (Android): running handoff

Branch `music-brain`. The contract is `docs/brain-spec.md` (v1.1), section 14 "Stage 2" half 2b: the atlas, the knowledge clients,
tags, embeddings, islands, the new Home and the island mixes. This file is **milestone 1 of 2b: "it knows what a song is"**: the
coverage probe, the genre atlas, the knowledge clients and enrichment lanes 3 to 5, track DNA and tag vectors, Spotify artist
genres, and the Developer screen "Brain data". Embeddings, islands, the new Home and the island mixes are later milestones.

The earlier handoffs are the map of what exists: `handoff/2026-11-17-music-brain-stage1.md` (deviations 1 to 79) and
`handoff/2026-11-18-music-brain-stage2a.md` (80 to 127). This file continues the numbering at **128**.

**Nothing below has been run on a device.** No phone was available. "Works" means: unit tests, compiling, and reading the code path
end to end. Every section ends with what is still unverified.

---

## Task 1: the coverage probe (done, with numbers)

`docs/brain-coverage-2b.md` has the method and every table. Short version, on a stand-in of **704 real tracks** (the official-audio
rows of the saved YouTube Music responses, 13 seeds across pop, hip hop, K-pop, V-pop, rock, electronic and more), asked of Deezer,
MusicBrainz, ListenBrainz, Wikidata and iTunes by name (no ISRC in the stand-in, so a lower bound for those sources), anonymously, at
or below one request per second per host:

| | all | Latin script | Vietnamese | Korean |
|---|---:|---:|---:|---:|
| tracks | 704 | 527 | 108 | 65 |
| a genre (the 3.4 rule) | 96.6% | 97.9% | 90.7% | 98.5% |
| a **specific** genre (below a family root) | 69.2% | 79.1% | **27.8%** | 60.0% |
| a believable year | 99.9% | 99.8% | 100% | 100% |
| popularity (Deezer rank) | 87.4% | 87.7% | 84.3% | 93.8% |
| tempo (Deezer bpm) | 22.3% | 28.8% | 0.9% | 6.2% |

- **The 60% rule is met by a wide margin; the constrained assistant tagging job is not added to 2b.** What is thin is the
  *specific* genre, above all Vietnamese: if named Vietnamese sub-genres matter, a fallback would have to cover about 72% of
  Vietnamese tracks, over the curated Vietnamese nodes of the atlas (no open source has them).
- The stand-in is more popular and better catalogued than a real long-tail library: read the rows as upper bounds, except
  Vietnamese, which is a fair indicator. Spotify artist genres could not be measured (no account); the Developer screen shows the
  real figure on a phone.
- The Wikidata step was re-run through POST (`tools/brain/coverage_wikidata.py`) because the GET form the probe first used failed on
  every request; MusicBrainz answered 503 to 20% of first tries at 1.1 s spacing; Deezer and iTunes never limited the probe.
- Scripts: `tools/brain/coverage_extract.py`, `coverage_probe.py`, `coverage_wikidata.py`, `coverage_report.py`.

## Task 2: the genre atlas

`app/src/main/assets/genre-atlas-v1.json.gz` (181 KB gzip, 551 KB JSON): **2,249 genres** (every MusicBrainz genre, 22
hand-curated nodes), 24 families, 40 moods, descriptors for the 279 anchor genres plus the ones their descendants inherit, and
the Deezer, iTunes and YouTube-chip name tables. Built by `tools/atlas/` (see its README): CC0 inputs only (the MusicBrainz genre
list and relationships, Wikidata), checked in under `tools/atlas/sources/` so the build is offline and deterministic.

- **For the iOS port**: the bytes are in this repo at `app/src/main/assets/genre-atlas-v1.json.gz`; `git show
  music-brain:app/src/main/assets/genre-atlas-v1.json.gz > genre-atlas-v1.json.gz` (use `MSYS_NO_PATHCONV=1` in Git Bash). Its
  SHA-256 is in `brain/src/test/resources/atlas/genre-atlas-v1.sha256`; `AtlasTest` pins it. The iOS copy must hash the same.
- **Loader**: `GenreAtlas.parse(json)` in `:brain` (pure; the caller gunzips and decodes UTF-8) and `GenreAtlasStore` in the app
  (asset read and parse on `Dispatchers.Default`, once, the first time something needs a genre: the refresh worker or the
  Developer screen; **never at app start**). Lookups: `idForMbid`, `idForName` (fold, then the FNV-1a hash of the folded UTF-8, per
  rule 13 of 10.3), `idsForSource`, `resolve`, `ancestors`, `descriptor`, `depth`.
- **The Vietnamese and K-pop corner is hand-curated** (`tools/atlas/hand/curated.tsv`, `aliases_extra.tsv`, descriptors): Vietnamese
  hip hop, indie pop, rock, R&B, EDM, lo-fi, ballad and folk; Korean hip hop, R&B, indie, rock and OST; aliases such as `nhạc trẻ`,
  `rap việt`, `k-pop girl group`. **The descriptors and descriptions are assistant-drafted and have not been read by a person.**

- **Relationships**: the MusicBrainz genre pages were crawled in full (2,227 pages, one request per 1.2 s, about 75 minutes): 1,598
  genres have a "subgenre of", 966 an "influenced by", 87 a "fusion of". Wikidata's edges fill in where MusicBrainz has none (spec 3.5
  step 3), so 1,763 genres have a parent and **1,784 have a family** (465 reach no family root; mostly regional folk forms), against
  1,358 from Wikidata alone. The "from" area of a page is crawled but not used (it is a name, not a country code; `reg` comes from
  Wikidata). Related edges (`rel`) are the nearest siblings (up to six, described and same-family first); the optional ListenBrainz
  tag-similarity edges of step 4 are not fetched.
- The build is reproducible: sorted keys, gzip with mtime 0, an append-only id registry. A rebuild on another machine can differ in
  the gzip bytes if its zlib differs; the golden files read the decompressed text, only the pinned SHA-256 would need re-pinning.

## Task 3: knowledge clients and lanes 3 to 5

Files (all under `data/brain/`): `source/KnowledgeModels.kt` (the hosts with their spacing, daily ceiling and terms, the row types),
`source/KnowledgeParsers.kt` (response text to rows; never throws), `source/TrackMatch.kt` (title, artist, length and version
checks), `source/KnowledgeHttp.kt` (**the one door** for every open-data request: the off switch, local and remote, asked before
every request; the host's `HostBudget`; `KnowledgeLedger`; spacing; back-off on 429, 502, 503, 504 and iTunes 403 with jitter and
`Retry-After`), `source/KnowledgeLedger.kt` (the day's requests per host, in `brain_state` `request_ledger_<host>`),
`source/{Deezer,MusicBrainz,ListenBrainz,Wikidata,ITunes}Source.kt`, `DnaAssembler.kt` (pure: evidence to rows), `DnaEnricher.kt`
(the fallback order, caches, the negative cache), `DnaTargets.kt` (who lanes 3 to 5 work on), `AtlasProvider.kt`; `BrainRefresh.kt`
gained the lanes; `di/BrainModule.kt` and `AppModule.kt` (`@KnowledgeOkHttpClient`) wire them.

- **Order per track** (3.3 B): Deezer by ISRC, else by name, then the track and its album's genres; MusicBrainz by ISRC (else by
  name) for the recording and its first year, then the artist's genres; iTunes by name; then one ListenBrainz batch (16 recordings:
  recording and artist genre tags with MusicBrainz genre ids, and popularity only for tracks nothing else ranked) and one Wikidata
  query (up to 100 artists) per round. Spotify artist genres and the radio's genre chips need no request.
- **Lanes**: 3 the most played tracks without DNA, 4 the rest of the library, 5 rows whose tags (90 days) or popularity (7 days)
  expired. They run inside the one `BrainRefreshWorker` run, after the YouTube lanes, **never as a second job**; the same switch
  (`brain.refresh.enabled` and the local one), never while music plays, 16 tracks a round, at most 8 minutes (a periodic work gets 10),
  and a round that saved nothing ends the lane. They spend no YouTube request, so a day with no YouTube requests left still enriches
  (the run then reports `BUDGET`).
- **Resumable and idempotent**: a row is saved after each round; ids, year and tempo never expire; tags 90 days; popularity 7 days; a
  source that had nothing is not asked again for 14 days, doubling to 120 (`source_miss`); a source that could not be asked (off,
  budget spent, offline, error, quota) records nothing and is tried again.
- **Budgets** (3.7): Deezer 5 requests a second and 600 a day, MusicBrainz 1 per 1.1 s and 200, ListenBrainz 1 per second and 150,
  Wikidata 1 per 6 s and 3, iTunes 1 per 3.5 s and 60. Each host has its own token bucket (full at the ceiling) and ledger.
- **Fixtures** (`app/src/test/resources/knowledge/`, cut by `tools/brain/cut_knowledge_fixtures.py` from responses the probe cached
  plus a few live requests for one famous track): a Deezer search, track, album and both errors (the quota one is written from the
  documented shape), a MusicBrainz recording search, ISRC lookup, recording genres and artist, ListenBrainz metadata and popularity,
  a Wikidata result, an iTunes search.
- **Terms and off switches** (owner decision, default): every source has its own switch (Developer mode > Brain data, default on) and a
  remote one (`brain.sources.<name>.enabled` in the saved remote config; a missing key means on). Used at run time only; the atlas is the
  only thing bundled, and it is CC0.

| source | what the brain takes | terms | before charging anyone |
|---|---|---|---|
| Deezer | album genres, popularity rank, release date, tempo, loudness, ids | non-commercial API terms | ask Deezer, or switch off `deezer` |
| MusicBrainz | recording id and first release year, artist genres and country | core data CC0; **tag and genre votes CC BY-NC-SA** | ask MetaBrainz, or switch off `musicbrainz` |
| ListenBrainz | recording and artist genre tags (MusicBrainz votes), listener count | open data derived from MusicBrainz, **CC BY-NC-SA votes** | as MusicBrainz, switch off `listenbrainz` |
| Wikidata | artist genres, Wikidata id | CC0 | none |
| iTunes Search | a song's genre and release date, by name | Apple's search API terms | review Apple's terms, or switch off `itunes` |
| Spotify | what the sync already returns (ids, release date, explicit, the top and followed artists' genres) | Spotify terms: **never used to train a model**; nothing here trains anything | the existing "Use my Spotify music to shape picks" switch |

Without Deezer, MusicBrainz and ListenBrainz the engine keeps iTunes (83% of the stand-in alone), Wikidata, Spotify genres, the radio
chips and the atlas.

The User-Agent is `PixlAudio/<version> ( https://github.com/RedSn0w1877/PixlAudio )` (`KnowledgeUserAgent.PROJECT_URL`): the repo's
origin URL, because the spec asks for "a public project URL". **Owner: confirm that URL is public, or change the constant, before the
refresh worker runs on a friend's phone.** It names the app and never the listener.

## Task 4: track DNA and tag vectors

- **In `:brain`** (pure, golden-tested): `GenreAtlas`, `Tags` (the noisy-OR of 3.4: ten sources with their trust, the late sources only
  when the first are weak, spreading to parents at 0.5 and 0.25 from the weights before any spreading, `min(2.4, 1 + 0.35 * depth)`,
  the 12 largest, L2 normalisation, `q = floor(255 w + 0.5)`, and "unknown" when nothing reaches 0.25; the artist fallback at 0.7;
  `tagdot`; a 3-byte-a-tag codec; `Tags.inputs` places raw strings by genre id, then source table, then name) and `TrackDna` (the
  earliest believable year, never Deezer alone; popularity from Deezer rank, YouTube play count text and ListenBrainz listeners;
  energy and valence as the tag-weighted mean of the descriptors with the loudness nudge; moods; the language of a title).
- **Storage: `brain.db` version 4**, migration `V3_TO_V4` (`MIGRATION_3_4`), exported schema `4.json`, tests in
  `MusicBrainMigrationsTest` (held against Room's own statements) and a new `migrates3To4...` case in the instrumented test (**not
  run: no device**). No destructive fallback; the history tables are untouched. New: `track_dna`, `artist_dna`, `source_miss`.
- **`DnaAssembler`** keeps every source's claims (`tagInputs`, 4 bytes each), so a refresh of one source replaces only its own
  claims, and a second track by the same artist inherits the artist's stored genres with no request. A track with no genre shows
  none: the vector is empty and the UI draws no chip.
- **Features 4 and 24** (5.8) are pure functions (`Features.tagAff`, `Features.sessTagPen`, `Session.applyTags`, `Affinity.applyTags`)
  with a golden file, and `Candidate.tags` feeds them. **No live code path fills `Candidate.tags` or calls the two `applyTags` yet**:
  that is the seam the scorer milestone turns on (the recorder needs the played track's tags at write time and the rebuild needs them
  per event).

## Task 5: Spotify artist genres

The code already syncs the genres of the listener's **top and followed artists** (`ListeningHistoryRepository`, `taste_seeds.genres`),
and `Song.genre` carries a first genre where the old backfill filled one; the batch `GET /artists` that the backfill used is known to
be refused for development-mode apps (the code stops asking after the first refusal). The DNA lanes read those strings
(`DnaTargets`), place them through the atlas (tag source 3, trust 0.7) and add **no Spotify request**. Everything is behind "Use my Spotify
music to shape picks": off, no Spotify genre, release date or explicit flag is used (the ISRC is library metadata and still goes to
Deezer and MusicBrainz as a lookup key).
**What could and could not be verified**: from the code, that the field is parsed (`SpotifyArtistFull.genres`), saved and read; **not**
whether Spotify still fills it for this client id (no token, nothing was called). The sync now writes one log line per range
(`brain: Spotify top artists (...) with genres: n of m`, and for the followed list), and the Developer screen shows "n of m top and
followed artists came with genres" (with a plain sentence when it is none), which is the S1 check of spec 14.6, one tap on a real account.

## Task 6: Developer mode > Brain data

`BrainDataScreen` and `BrainDataViewModel` (route `brain_data`, a row in Settings > Developer mode > Maintenance): the atlas (version,
genres, families, how many describe how they sound); what is stored about the listener's tracks (count and share with a genre, a year,
a popularity, an energy and mood, a tempo, artists known and with genres, lookups waiting out a quiet period); each of the five
sources with its **switch**, its requests today of its ceiling, the last time it was asked, how many tracks it answered, and its terms;
Spotify's artist genres seen and YouTube Music's requests today; the last refresh run in plain words. It reads when it opens
(`BrainDataViewModel.refresh`) and does nothing at app start. Strings are in `strings_brain.xml`; no "learning".

## Deviations 128 and on

128. **The atlas file is one JSON object, not a bare array** (spec 3.5): it also carries the 24 family names, the 40-mood whitelist and the
     per-source name tables, so the loader needs nothing else. Records are as in 3.5 with `i m w n a p inf fus rel fam d reg era x desc`;
     `era` is `[inception year]` (one number), `fam` is left out when no family root is reachable (465 of 2,249 genres, mostly
     regional folk forms), and `x` is materialised for inheriting descendants at build time, so no loader walks ancestors for it.
129. **Atlas ids are an append-only registry** (`tools/atlas/sources/ids.tsv`), not an index into a sorted list: stored tag blobs keep their
     ids when MusicBrainz adds genres. `track_dna.atlasVersion` exists for the day an id would ever have to break; a row from another
     version is not merged, it is re-derived.
130. **Curated nodes have no MusicBrainz id** (`m` empty; slugs `x:<name>` in the registry): 22 of them, the Vietnamese and Korean corner,
     soundtrack, and a few classical and electronic nodes MusicBrainz lacks (`adult contemporary`, `bass music`, `chamber music`, `choral`,
     `piano`). MusicBrainz has no `soundtrack`, `film score`, `video game music`, `rhythm and blues` (it says `r&b`) or `regional mexican`.
131. **Name lookups use the FNV-1a hash of the folded UTF-8, not the string** (10.3 rule 13). A hash collision between two names would give
     the smaller atlas id; none exists in the shipped atlas (checked by `AtlasTest`).
132. **`Tags.inputs`** (placing raw strings) and **`Tags.encode`/`decode`** are additions to the spec's function list. A raw string is placed
     by MusicBrainz genre id first, then the source table, then the name; a string a table marks "not a genre" stays unplaced even
     if its words are a genre's name ("Kids"); a string with zero votes is dropped. `conf` is `votes / max votes` over the raws of one
     source that carry votes, 1 for a raw without.
133. **Spreading to parents uses the weights before any spreading** and every parent of a node (the atlas is a DAG), parents at 0.5 and
     grandparents at 0.25, by `max`; the spec's "w(parent) = max(w(parent), 0.5 * w(child))" read as a one-pass rule.
134. **The sources 5, 7, 9 and 10 of 3.4 are not gathered** (Discogs; the related-artists mean; the shared-playlist vote; the assistant). The
     merge function knows all ten trusts and the "late sources only when weak" rule, so they plug in without a change to it.
135. **`track_dna` differs from the 3.2 list**: no `emb` (it arrives with the embeddings, by an `ALTER TABLE` migration), and extra columns
     `tagInputs` (every source's claims, 4 bytes each), `yearClaims`, `popClaims`, `tagsAtMs`, `popAtMs` and `atlasVersion`, because tags
     expire after 90 days, popularity after 7, and a refresh must re-merge the claims of the sources it did not ask again.
     `source_miss` has one more column, `misses`, for the doubling from 14 to 120 days. `artist_dna` has `tagInputs` too.
136. **ListenBrainz gives the recording's genres, not a MusicBrainz `recording?inc=genres` call** (one batch of 16 instead of one request
     each); MusicBrainz is asked for the recording id and year (by ISRC, else by name) and the artist's genres. The recording-genre
     coverage is low either way (17.5% on the stand-in).
137. **Popularity from ListenBrainz is asked only for tracks nothing else ranked**, and a YouTube play count is read only from the cached
     seed row of the track (no request), so it covers tracks that were radio seeds.
138. **Each knowledge host's token bucket starts full at its daily ceiling** and refills at ceiling per day; the ledger is the day's real
     limit (`request_ledger_<host>` in `brain_state`). Spacing between two requests is separate (`KnowledgeHost.minIntervalMs`).
139. **Lanes 3 to 5 run after the YouTube lanes and do not stop when the YouTube budget is spent** (the run reports `BUDGET` then), run
     at most 8 minutes (`Stop.TIME`, a new value) and in rounds of 16. Lane 3 is "most played without DNA" by decayed track weight, lane 4
     "the library without DNA" in library order, lane 5 expired rows, the stalest first. **Graph nodes outside the library are not
     enriched** in this milestone.
140. **A track with no row yet gets one only when something was learned**: a source answered, or the app already knew a genre, a release
     date, a YouTube year or a play count. A track no source could be asked about and nothing is known of gets no row, so lane 3 cannot
     spin through a library creating empty rows while every host is spent or off. A track that has a row is saved again even when
     nothing could be asked, so lane 5 moves on to the next one.
141. **With "Use my Spotify music to shape picks" off, no Spotify genre, release date or explicit flag is read**; the ISRC from the library
     stays a lookup key. `Song.genre` and `Song.year` count as Spotify's where they are set.
142. **The User-Agent's project URL is the repo's origin URL** (`KnowledgeUserAgent.PROJECT_URL`); the owner decides.
143. **Features 4 and 24 are pure and golden-tested but not yet fed** (see Task 4); `SessionState.tagPen` is left out of `encode()` so the
     stage 2a golden files stay byte for byte.
144. **A new `Stop.TIME`**, `RefreshWork.dnaTargets`/`enrich` and `BrainRefreshRunner.Report.dnaLane3/4/5`; the note in `brain_state`
     `refresh_last` gained `d<3>+<4>+<5>`; `BrainRefresh.parseNote` reads both forms.
145. **`SpotifyDao.getIsrcs`** is a new read-only query on the main database (no entity or version change) to look an ISRC up for tracks the
     2a facts table does not have yet.
146. **The Wikidata step of the coverage probe was re-run with POST** (the GET form failed on all 16 requests); the atlas build uses POST too.
147. **Golden files**: three new files (`atlas`, `tags`, `dna`) and a fourth (`tagfeatures`); `README.md` and `MANIFEST.sha256` changed;
     no existing golden file changed and `Brain.SPEC_VERSION` stays 2. See "For the iOS port".
148. **`scriptLang` codes** (the spec leaves them open): 0 unknown, 1 Vietnamese, 2 Korean, 3 Japanese, 4 Chinese, 5 Latin (other), 6 Cyrillic,
     7 Thai, 8 Arabic, 9 Devanagari; a plain-Latin title takes its artist's country (VN, KR, JP, CN, TW, HK) when the country is known.
149. **Artist keys**: once a source tells the enricher an artist's Spotify, Deezer or MusicBrainz id it is stored beside the name key
     (`ArtistKeySink`, `artist_alias`), so the artist joins the same `aid` when met under another spelling (3.1 "stage 2 brings ids").
     Nothing reads the new keys yet and no artist is merged by them.
150. **Lane 5** lists rows whose tags (90 days) or popularity (7 days) expired and rows not yet answered by all three of Deezer, MusicBrainz
     and iTunes that were last looked at more than a day ago; a row with nothing new is saved again so it moves to the back of the order.
151. **`.gitattributes`** gained LF rules for the atlas sources, hand tables, fixtures and the pinned hash, and `binary` for the asset.

## Cut or deferred (and why)

- **`DeezerRadioSource`** (the fallback graph source when the YouTube source is off): not built; it is graph work, not DNA, and belongs
  with the island and graph milestone.
- **Discogs, the related-artists vote, the shared-playlist vote, the assistant tagging** (tag sources 5, 7, 9, 10): not gathered (see 134).
- **`emb`** and everything about embeddings, islands, the SIM, BACK and EDGE arms, First Listen, Just Dropped, On Repeat, Rediscover,
  genre and mood stations, Search tiles, cold-start flows, the new Home and the removal of the Muselle 2 gate: later milestones of 2b.
- **DNA for graph nodes outside the library** (lane 4's second half): only library tracks are enriched.
- **The YouTube row's year and play count for a track that was never a radio seed**: no request is made for it; the cached seed row is read
  when there is one.
- **Live use of tags**: `Candidate.tags`, `Session.applyTags` and `Affinity.applyTags` exist and are tested but no recorder, rebuild or
  mix builder fills or calls them (the seam for the scorer milestone).
- **A human review of the atlas descriptors and descriptions** (the spec says "assistant-drafted, human-reviewed"): not done, said in the file.
- **The real-library coverage run** the spec asks for before stage 2 ([S2]): done on a stand-in, as the task said; the Developer screen
  is the tool to repeat it on a phone.

## For the iOS port

- Copy the **golden folder** `brain/src/test/resources/brain-golden/` as it is now: **new files `atlas.jsonl`, `tags.jsonl`, `dna.jsonl`,
  `tagfeatures.jsonl`; `README.md` and `MANIFEST.sha256` changed; every older file is byte-identical and `SPEC_VERSION` is still 2.**
  `GenreAtlas`, `Tags`, `TrackDna`, `Features.tagAff`, `Features.sessTagPen`, `Session.applyTags` and `Affinity.applyTags` must match
  them bit for bit.
- Copy the **atlas asset** byte for byte: `git show music-brain:app/src/main/assets/genre-atlas-v1.json.gz` (SHA-256 in
  `brain/src/test/resources/atlas/genre-atlas-v1.sha256`). The golden `atlas`, `tags`, `dna` and `tagfeatures` cases read it (gunzipped,
  UTF-8, `GenreAtlas.parse`), so the Swift test needs it next to the fixtures.
- `brain.db` 4 has three tables (`track_dna`, `artist_dna`, `source_miss`): on iOS they are keyed record files like the others. Claims
  are 4 bytes (`provider, atlas id high, atlas id low, confidence`), providers 1 LB recording, 2 MB artist, 3 Spotify, 4 chip, 6 iTunes, 8
  Deezer album, 12 LB artist, 13 Wikidata artist.
- Mirror: `KnowledgeHttp`'s rules (the switch asked before every request, a budget and ledger per host, spacing, back-off), the per-host
  numbers in `KnowledgeHost`, `TrackMatch`, `KnowledgeParsers` (same fixtures from `app/src/test/resources/knowledge/`),
  `DnaAssembler.assemble`, and the lanes (3 most played, 4 the rest, 5 expired; inside the one background task; never while music plays).
- The remote switch: `brain.sources.<name>.enabled` next to `brain.refresh.enabled`.

## Unverified on a device

Everything above. In particular: that the asset loads and parses on a phone and how long it takes (181 KB of gzip, 551 KB of JSON,
2,249 genres, 181 KB of gzip; never measured on a phone); the whole enrichment run on a real library (the budgets, the 503s from MusicBrainz at
1.1 s, the 8-minute limit against WorkManager's 10, a cancelled round losing its up to 16 unsaved tracks); that Spotify still fills artist
genres for this client id; the `brain.db` 3 to 4 migration on real data and `MigrateInstrumented...3To4` (not run); that the User-Agent
is accepted by every host; real coverage on a real library (the Developer screen shows it); the Developer screen's look and its
switches on a phone; and whether the hand-written Vietnamese and Korean corner reads right to someone who knows the music.

## How to test

Developer mode on, Spotify connected, ten songs played, Wi-Fi, music paused.
1. Settings > Developer mode > Maintenance > **Brain data**: the atlas reads "Version 1, 2,249 genres in 24 families". Nothing else yet.
2. Let the daily refresh run (or trigger it: `adb shell cmd jobscheduler run -f com.theveloper.pixelplay <job id of brain_refresh>`),
   not while music plays. Open Brain data again: "What is known about your tracks" fills in; each source shows requests today and how
   many tracks it answered. Switch Deezer off: its next request never goes out (the count stops). The remote switch is the saved
   remote config's `brain.sources.deezer.enabled: false`.
3. Spotify section: "n of m top and followed artists came with genres". `adb logcat | grep "brain: Spotify"` after a sync gives the
   same line (spec 14.6, S1).
4. Genres stay absent: a track nothing could place shows no genre (nothing shows genres yet in this milestone; `tags` in `track_dna` is
   empty for it).
5. `adb shell dumpsys jobscheduler | grep brain_refresh`: still ONE job.

## What was run, and what it said

RUN_PLACEHOLDER
