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

One Gradle build at a time with `ANDROID_HOME="C:\Users\Hoa\AppData\Local\Android\Sdk"`.

- **Stage 2a baseline first**: `./gradlew :brain:test :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.*"`: BUILD
  SUCCESSFUL (14m 25s), 328 tests, 0 failures, before any change.
- **After the atlas was rebuilt with the full MusicBrainz relationships**, `./gradlew :brain:generateGolden` (23 files; only `atlas`,
  `tags`, `dna`, `tagfeatures`, `MANIFEST.sha256` and the pinned atlas hash changed), then
  `./gradlew --continue :brain:test :app:testDebugUnitTest :app:assembleDebug`: **BUILD SUCCESSFUL in 7m 22s**. `:brain:test`
  **152 tests, 0 failures** (126 from 2a plus the atlas, tags and DNA tests, the tag features, and golden equality); the whole
  `:app:testDebugUnitTest` **2,366 tests, 2 skipped, 0 failures, 0 errors** (2,326 at the end of 2a; the new ones are
  `KnowledgeSourcesTest`, `KnowledgeHttpTest`, `DnaAssemblerTest`, `DnaEnricherTest`, `DnaLanesTest` and three new cases in
  `MusicBrainMigrationsTest`); the existing `data.recommendation.*` tests (`RecommendationGoldenEqualityTest` among them) pass
  untouched; `:app:assembleDebug` built both split APKs (Hilt graph included).
- **After the last two small changes** (timings in `PerformanceMetrics`, the artist keys): `./gradlew --continue :brain:test
  :app:testDebugUnitTest --tests "*Dna*" --tests "*Knowledge*" --tests "*BrainCopyTest" --tests "*MusicBrainMigrationsTest" --tests
  "*BrainRefreshRunnerTest" :app:assembleDebug`: BUILD SUCCESSFUL (14m 50s).
- Failures met on the way, all fixed: `org.json` is a stub in JVM unit tests (the ListenBrainz request body is built with
  kotlinx.serialization now); an apostrophe in a string resource; a source edited while Gradle was compiling (KSP and the compiler
  saw different Room DAOs).
- An independent Python implementation of `Tags.merge` (written from the spec) reproduced all 360 `merge` cases of `tags.jsonl`.
- **Not run**: the instrumented tests (`MusicBrainMigrationInstrumentedTest`, including the new 3-to-4 case): no device.

---

# Milestone 2 of 2b: "it knows your music" (islands, arms, mixes, the new Home)

Read this part after the milestone 1 text above. It continues the numbering at **152**. **Nothing here has run on a device.** "Works"
means unit tests, compiling, and reading the code path end to end.

Milestone 1 was checked first: `./gradlew :brain:test :app:compileDebugKotlin` was BUILD SUCCESSFUL before any change (its `:brain:test`
result was the cached pass of the milestone 1 run). Nothing needed fixing.

## What was built, per task

### 1. The pure core (`:brain`, golden-tested)

| File | What |
|---|---|
| `Embeddings.kt` (`Embeddings`, `FastRp`) | FastRP over the persisted graph: `R` from `splitmix64(fnv1a64(nodeId) xor j)`, three row-normalised passes, `E = N2 + N3`, Int8[64]; `cos_int8` is an exact integer dot over integer norms. `fnv1a64(nodeId)` is the hash of the id's 8 little-endian bytes. |
| `Ppr.kt` | Personalised PageRank, 4 iterations, truncation to 3,000 (ties by id), `ppr = p / sqrt(weightedDegree)`; every sum in ascending node id order. |
| `Islands.kt` (`Islands`, `TagProjection`, `IslandNames`) | Weighted spherical k-means as 5.4 (K0 from n, the init, exactly 15 rounds, merge above 0.85, dissolve under 8, one island under 60 tracks), the hashed 32-dim tag projection, medoid, top tags, **lift tags** (the island's name), top artists, greedy id matching (`matchIds`, floor 0.5, ids are never reused: `lastId`). `IslandNames.pick` takes the two highest-lift atlas genres and skips an ancestor or descendant of the first. |
| `ArmRules.kt` | BACK (5 completions, a year since, not in the library), SIM sources (artist `aff > 0.5` and weight 5), EDGE `adjacentGenres` (siblings, relatives and fusions of the loved genres that are not an ancestor/descendant, not a family root, `share < 0.005`). |
| `ListMixes.kt` | On Repeat (21-day positive mass, last 30 days), Rediscover (`aff > 0.5`, 90 days, less the 30-day served penalty), Just Dropped (loved artists first), and `fill` (greedy under 6.4's spacing, with a per-artist cap). |
| `Calibration.kt` (`Calibration`, `SimilarityIndex`) | `calibGain` and the KL of 6.5; `sim` of 5.3 with embeddings and tags. |
| `Features.kt` | `Candidate` gained `emb`, `popPermille`, `fresh`, `back`, `edge`, `islandIdx`, `artistNew` (all defaulted, so every older call is unchanged); `IslandContext`; features **5, 6, 19, 20, 39** are filled (4 and 24 were milestone 1). Prior weights are the `M0` of 5.8, untouched. |
| `HomePlanner.kt` | The page rules of 8.1 (a track once, an artist twice a shelf and four a page, two all-new shelves never adjacent, a familiar shelf in the first three, a short shelf hidden, at most seven), `hero`, `daypart`, `planKey`, `distinctContexts`. |
| `StationPlanner`, `MixPlanner`, `ListBuilder` | `MODE_FIRST_LISTEN`; island floor 0.2 and `nuOfIsland` clip(0.15, 0.40) for a Daily Mix of one island; Your Mix across islands with `Calibration` (kappa 0.4, lambda 0.6); the arm flags set the arm; `maxPerArtist`, `minNewArtistShare` for First Listen. |

New golden files: **`fastrp`, `ppr`, `islands`, `arms`, `listmixes`, `islandfeatures`, `islandmixes`, `homeplan`** (and `README.md`,
`MANIFEST.sha256`). **No earlier golden file changed and `SPEC_VERSION` is still 2.** Tests: `Stage2bM2Test` (the planted-cluster
synthetic listeners: islands recover them with purity at least 0.95 for 3, 4, 5 and 6 clusters; ids stay stable when the taste drifts and
a new cluster gets a new id; Daily Mix tracks are all within the island floor and different islands share at most 3 tracks; Your Mix keeps
every island and a calibration KL under 0.15; First Listen is unheard, two an artist at most, 40% new artists; the Home rules on 400
random pages).

### 2. `brain.db` version 5

Tables `node_embedding(nodeId, embVersion, vec)`, `island(...)`, `island_member(rid, islandId)`; real migration `MIGRATION_4_5`, exported
schema `5.json`, `MusicBrainMigrationsTest` cases (held against Room's own statements) and `migrates4To5...` in the instrumented test
(**not run: no device**). No destructive fallback. "Enrichment state" is `brain_state` keys (`island_last_id`, `island_tag_shares`, `emb_count`,
`island_fit_at`), not a table.

### 3. The mixes (8.2)

`MixSpec` (`MixModel.kt`) names a list: Daily Mix of an island (`daily:<islandId>`, or the one `daily` of 2a with fewer than two islands),
`your`, `first`, `dropped`, `repeat`, `rediscover`. `BrainMixes` builds them (`MadeForYouBuild.kt` has the pieces): saved in the same
`served_list` format (new optional fields `mixId`, `islandId`, `number`, `subtitle`; a 2a row still reads), the same all day, back after a
restart without loading the brain, no track in two lists of a day (`Repetition.takenOnDay(exceptId)`), First Listen weekly (Monday 04:00,
`ServedListStore.weekStart`) and held against every other list all week. Names come from the island: "Daily Mix 2" and a subtitle of the two
highest-lift atlas genres and the two biggest artists ("Soft pop & R&B · Sabrina Carpenter, Chappell Roan"); an island with no genre
shows the artists alone. `today(KIND_DAILY)` (the legacy screens, the widgets) returns Daily Mix 1.

### 4. The new Home (8.1)

`HomePlanBuilder` plans (in the background, never at app start): the made-for-you cards, the shelves in the fixed order (Made for you; up to
two Because you listened to; New from artists you love; On repeat; Rediscover; Stations for you; A step sideways; More like <album>),
`HomePlanner` applies the page rules, the hero is chosen, `HomePlanStore` saves `files/brain/home_plan.json` (refuses over 50 KB; a full
plan of 7 shelves of 12 outside tracks is about 25 KB, tested). `HomePlanStateHolder` (a `@Singleton`) reads that file before anything else,
shows it, plans again when the local day (04:00) or the daypart or the taste adjustments changed, and shows the new plan the **next time Home
appears**. `HomeDiscoveryStateHolder.refresh` returns at once when the plan owns Home (the whole-library ranking on every Home start is
gone), and the Muselle 2 gate (`PlusLicenseManager`) is removed from it. UI: date line ("Friday night"), chips For you / Mixes / New (Spatial
opens the Cloud processing screen), hero, Jump back in (from `RecentContexts`, the last 6 distinct contexts), shelves; no spinner and no empty
state: with no plan, Jump back in and the library rows. Mix cards open Daily Mix 1 / Your Mix on their own screens and the rest on the new
`MixDetailScreen`.

### 5. Genre and mood stations, Search tiles (8.3, 8.10)

`GenreStations`: the listener's songs under an atlas node (tags with weight at least 40 of 255, parents included) seed a `Station` with
source `SOURCE_GENRE` or `SOURCE_MOOD`, which is the album/playlist station machinery. A name the atlas lacks has no tile and no id; a node with
none of the listener's songs starts nothing (a Search tile then falls back to its old text search).

### 6. Cold start (8.10)

`ColdStart` fetches the day's first eight seed radios once (the same work as lane 1 of the refresh job, same budget and switches) while Home is on
screen; Home shows "Getting to know your music · 3 of 8". A library under 30 songs with no picks is offered "Pick three artists you love"
(`ArtistPickerScreen`, search-based: the app's YouTube Music layer has no chart call, so the chart grid is not built); `PickedArtists` runs the
artist station start for each pick, keeps it as a seed, and counts as "the brain has state", so Your Mix and one Daily Mix can exist before any
play. Setup has a new page after Spotify, **Familiar / Blend / Discover** with an "Import your listening history" link (Home opens the
existing import once).

### 7. Retire

`MusicDiscoveryRepository` and its test are deleted (nothing used it but two switched-off paths). `HomeCatalogRepository` **stays**: the legacy
Home (engine `legacy`, or no brain state) still uses its releases and `playableSongs`; the "Beyond your library" search stays off. The legacy
engine, `Muselle` and the golden file of the legacy engine are untouched until stage 3.

### 8. Eval

The synthetic planted-cluster listeners (above). `BrainEvalTest` (the real-export command) compiles unchanged: the scorer's new features are
zero when no `IslandContext` is passed, which `Eval` does not pass, so test 1 on a real export is the same measurement as in 2a. Islands are not
in `Eval` yet (see "Cut").

## Deviations 152 and on

152. **Embeddings live in `node_embedding`, not in `track_dna.emb`** (spec 3.2): a node can be an artist or a track with no DNA row, and the table is
     rewritten whole at each fit (`FastRp.VERSION` 1).
153. **`w_t` of 5.4** is the track's decayed finished-play mass (the affinity's 120-day half-life) plus its pseudo-count from a like or a top
     list, not `sum c * r * 2^(-age/180)`; the "21-day weight" is the 21-day positive mass. The spec's per-play 180-day form needs the whole
     log at fit time; the counters already hold the same quantity. Swap it when the replay path feeds the fit.
154. **Daily Mix 1..K** are the biggest six islands, numbered by ascending stable id (so a mix keeps its number while its island lives; if an
     island dissolves the numbers above it move down by one). With fewer than two islands there is the one `daily` list of 2a.
155. **The diversity term uses `sim` with embeddings and tags only in the island-aware mixes** (`SimilarityIndex`); autoplay, stations and every
     2a golden case keep the `adj` branch, so no earlier output moved.
156. **Cold start runs 2 radios in parallel, not 6**: the stage 1 `HostBudget` allows 2 at a time.
157. **Page rules**: a card shelf (made for you, stations) needs 1 and 4 cards, not 6 (`HomePlanner.MIN_MIX_CARDS`, `MIN_STATION_CARDS`); each
     card is itself a list. Track shelves keep 6.
158. **The hero's "release from a top-5 artist"** is a card by one of the five artists with the most decayed play weight.
159. **"More like <album>"** is built from the cached radios of up to three songs of an album finished (3 songs) in the last 14 days, merged by
     `cooc`; nothing is fetched for it, so an album whose radios are not cached has no shelf.
160. **EDGE** takes the listener's loved genres from the tag shares of the last island fit and offers only **library** songs with that strongest
     genre (DNA for tracks outside the library is cut, so a radio row has no tags). **SIM and BACK are in the pure core and the planner (the arm
     flags set the arm, the golden files cover the rules) but no app code sets them yet**: SIM needs the similar-artist lists cached for the mixes'
     pool, BACK needs imported-only tracks matched to a YouTube video. The arms are not "live" until a later milestone feeds them.
161. **The "Spatial" chip opens the Cloud processing screen** (the app has no spatial shelf for Home; the spec says "unchanged").
162. **A song's overflow on the new mix screen starts a station** from it; the Daily Mix screens keep the full song sheet.
163. **Jump back in is read live** from `recent_contexts.json` (written by `PlaybackDispatchStateHolder` when a queue starts), not stored in the
     plan (the spec's `tiles[6]`): its refresh is "live, local".
164. **The Muselle 2 gate**: `HomeDiscoveryStateHolder` always asks the legacy planner for the full variant. `PremiumSmartPlaylistEngine`,
     `PremiumInsightEngine` and `PremiumFeatureLimits` are left for stage 3's deletion (Plus keeps stems and spatial only).
165. **Features 7, 9, 21, 27, 28 stay 0**: nothing feeds the decade counters, playlist counts, mainstreamness, tempo or loudness yet. 5, 6, 19, 20,
     39 are live where the pool has the data.
166. **`ServedListDao` got `latestById` / `countSinceById`, `SeedDao` got `qualifiedPlaysSince`, `healthyListCount`, `recentCompleted`,
     `GraphEdgeDao` got `all`**: queries only, no schema change.
167. **First Listen's library part is the unplayed library songs of adjacent genres**; its novel part is the radios of the day's seeds; "never
     played here" is `Affinity.isHeard(TRACK)`, so a song the listener played from the library in the app counts as played.
168. **The island refit also runs from the Home plan** (when due and the brain has 10 heard tracks), not only at the end of the refresh job:
     a new listener should not wait a day for islands. It runs off the main thread, after the first seconds.

## Cut or deferred

- **"Popular where you are"** and the **chart grid** of the picker: the YouTube Music layer has no chart call. The picker is search-based.
- **Assistant-written island names**, the Ask Taizo prompt pill built from the listener's data (the existing quick action stays), the daypart
  mix (stage 3), the shelf bandit (stage 3), the learner (stage 3).
- **Seeding radios from each island's medoid and top tracks** (3.6, "stage 2 with islands"): the day's seeds are the 2a four groups.
- **Co-play edges and same-playlist edges** in the graph (3.6): the embeddings are over the radio edges, the related edges and the
  track-artist edges only.
- **Islands in `Eval`** and tests 2 to 4 of section 11: `Eval` still runs tests 1 and 5; the synthetic listeners check the islands themselves.
- **A Developer screen section for islands** (count, fit age): not added; `brain_island_fit` and `brain_home_plan` are in `PerformanceMetrics`.
- **SIM and BACK arms are not fed** (160). **`HomeCatalogRepository` is not deleted** (7).
- **iOS** is not touched (this is the Android milestone).

## For the iOS port (milestone 2)

- Copy the golden folder as it is (`brain/src/test/resources/brain-golden`): **new files `fastrp`, `ppr`, `islands`, `arms`, `listmixes`,
  `islandfeatures`, `islandmixes`, `homeplan` (+ `README.md`, `MANIFEST.sha256`); no older file changed, `SPEC_VERSION` is 2.** The case formats
  are in the README. Port, file for file: `Embeddings.kt` (`FastRp`: `fnv1a64` of the 8 little-endian bytes of the id, `UInt64` remainders for
  `mod 6`), `Ppr.kt` (ordered maps), `Islands.kt` (`TagProjection` uses `splitmix64(splitmix64(id) xor (j + 1000))` and its low bit), `ArmRules.kt`,
  `ListMixes.kt`, `Calibration.kt` (`SimilarityIndex`), `HomePlanner.kt`, and the changes to `Features.kt`, `StationPlanner.kt`, `MixPlanner.kt`,
  `ListBuilder.kt`, `Repetition.kt` (`takenOnDay` with `exceptId`).
- Stores: `node_embedding(nodeId, embVersion, vec[64])`, `island(islandId, centroid 96 doubles big endian, medoid, topTags/liftTags as 4-byte ints,
  topArtists as 8-byte longs, weightQ, recentWeightQ, size, fitAtMs)`, `island_member(rid, islandId)`. Keys in `brain_state`: `island_last_id`,
  `island_tag_shares` (`id:share;...`), `emb_count`, `island_fit_at`, `picked_artists`.
- `served_list.surfaceId` names the list: `daily`, `daily:<islandId>`, `your`, `first`, `dropped`, `repeat`, `rediscover`; `surface` is 1 for the
  dailies, 2 for your, 3 for the named ones. First Listen is looked up from Monday 04:00 local.
- `home_plan.json` (`HomePlan` in `HomePlan.kt`): `planVersion`, `generatedAtMs`, `planKey` (`HomePlanner.planKey`), `taste`, `hero`, `mixes[]`,
  `shelves[]` with `titleKey` / `titleArg` / `subtitleKey` and `items[]` (`rid`, `aid` as a name hash for the page rules, `songId` or the parsed
  `row`, `arm`, `whyKey`, `whyName`, `slot`). The strings behind the keys are in `strings_brain.xml` (`brain_home_*`, `brain_mix_*`, `home_*`).
- Page order and rules: `HomePlanBuilder.plan` (the shelf order is the table of 8.1), then `HomePlanner.plan`. The hero, date line and chips follow
  `HomePlanStateHolder` / `HomeScreen`. Jump back in reads the last 6 distinct queue contexts.
- Genre and mood stations: songs under an atlas node (tag weight at least 40 of 255) seed an album-style station (source 4 genre, 5 mood).
  Search tiles: a station when the atlas has the name and the library has songs under it, else the old search; a name the atlas lacks has no tile.
- Copy: no string says "learning" or any banned word; `BrainCopyTest` covers `strings_brain.xml`.

## Unverified on a device (new in this milestone)

Everything. In particular: how long `IslandBuilder.fit` takes on a phone with a 3,000-song library (`brain_island_fit`), the graph's size after a
week of radios, whether the islands on a real library are the sides of someone's taste (the synthetic listeners only prove the code recovers
planted ones); that the Daily Mixes differ and read right; that the home plan is under 50 KB for a real listener with outside tracks; the look of
the new Home on a phone (hero, chips, tiles, shelves) and that nothing jumps; that a plan built while Home is open waits for the next
appearance; the 04:00 and daypart rollover; the first-minute strip and the picker (they call YouTube Music); the picker's search results;
the genre station start and its first batch; the Search tiles; the new setup page and the history-import link; that `MusicBrainMigrationInstrumentedTest.migrates4To5...`
passes; that the refresh job still shows ONE entry in `dumpsys jobscheduler`.

## How to test on a phone

1. Update the app over a build with brain.db 4: it opens (migration 4 to 5). Developer mode > Brain data is unchanged.
2. Play 10 different songs and leave Home open for a few seconds. Home should show the plan the next time it appears (leave and return): date
   line, chips, hero, Jump back in, "Made for you". Kill and reopen: it paints from `home_plan.json` with no wait (`adb shell run-as <pkg> ls files/brain`).
3. After the first refresh (or `adb shell cmd jobscheduler run -f <pkg> <id of brain_refresh>`), the islands are fitted: Daily Mix 1..K appear with
   names such as "Soft pop & R&B · Artist, Artist".
4. Tap a genre card under "Stations for you": a station starts and the queue header says "Station · <genre>". On Search, tap Pop: same.
5. A fresh install with Spotify connected: the strip "Getting to know your music" counts to 8 and goes. With a library under 30 songs:
   "Pick three artists you love" appears; pick three; Your Mix exists.
6. Setup: the new page after Spotify. "Import your listening history" opens the import on first Home.

## What was run, and what it said (milestone 2)

(see the end of this file for the final numbers)
