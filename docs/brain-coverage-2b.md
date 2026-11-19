# Music brain 2b: knowledge-source coverage probe

Date: 2026-11-19. Spec: `docs/brain-spec.md` section 14, stage 2, "Before it starts" ([S2]: measure real DNA coverage, and if fewer than 60% of plays get tags, add the constrained assistant tagging job). Scripts: `tools/brain/coverage_extract.py`, `coverage_probe.py`, `coverage_wikidata.py`, `coverage_report.py`.

## What this is, and what it is not

The real phone library was not available, so this is measured on a **stand-in**: the 704 distinct tracks (artist, title, length) found in the saved YouTube Music responses of the earlier research (`builds/music-brain-2026-11-17/yt/raw`: song radios of 13 seeds, their tuning-chip radios, searches, related tabs). Only official-audio rows (`MUSIC_VIDEO_TYPE_ATV`) were kept, as the engine does. The review probe folder held scripts and patches but no response files, so there was nothing more to add from it.

The stand-in is not a library. It is what YouTube Music's radio thinks is close to famous tracks, so it is **more popular and better catalogued than a long-tail library will be**. Read the numbers as an upper bound for the English and Korean rows and as a fair indicator for Vietnamese, whose radio seeds were V-pop.

- **Language group** is decided from the characters of the title and artist (Hangul is `ko`, kana `ja`, Han `zh`, Vietnamese letters `vi`), then by the seed's own file (a V-pop radio is `vi`), else `en`. `en` therefore means "Latin script, not Vietnamese by its letters": it includes K-pop and Latin pop written in Latin letters. Japanese and Chinese have too few tracks (1 and 3) to say anything.
- **No account, no listener data.** Famous public tracks, a generic User-Agent (`PixlAudio-coverage-probe/0.1 (anonymous; public tracks only; no account)`), one request per second per host at most: Deezer 1 s, MusicBrainz 1.1 s, ListenBrainz 1 s, Wikidata 6 s, iTunes 3.5 s. Every response is cached, so nothing was asked twice.
- **Without an ISRC.** The stand-in has none, so every lookup is by name, the weaker path. In the app the Spotify sync gives an ISRC, and the probe of 2026-11-17 found 16 of 16 on Deezer and 13 of 16 on MusicBrainz by ISRC. Deezer, MusicBrainz, ListenBrainz and Wikidata coverage below is therefore a **lower bound** for a library that has ISRCs.
- **Spotify artist genres are not measured** (no account, no token), and so are not in any row. Whether Spotify still fills `genres` for this client id stays unverified (see the handoff for what the code can and cannot say).
- A track counts as **having a genre** when some atlas node reaches noisy-OR weight 0.25 before spreading to parents (the rule of 3.4), after every raw string was mapped through the bundled atlas by its MusicBrainz genre id, then by the per-source table (Deezer, iTunes), then by name. A **specific** genre is a node below a family root (`dance-pop`, `shoegaze`, `vinahouse`), not `pop` or `rock` itself, because a generic genre says almost nothing about a track.
- The Python merge here follows `Tags.merge` closely but is a re-implementation; the Kotlin golden files pin the real one.

## Requests made

| host | requests | errors | notes |
|---|---:|---:|---|
| api.deezer.com | 1,844 | 0 | no quota error at 1 request per second |
| musicbrainz.org | 1,872 | 372 | 20% answered 503 at 1.1 s spacing, each retried after a back-off; every track was finished |
| itunes.apple.com | 698 | 0 | no 403 at 3.5 s spacing |
| api.listenbrainz.org | 64 | 0 | 508 recordings in batches of 16, plus popularity |
| query.wikidata.org | 16, then 4 | 16 | the first form (GET with 100 ids in the URL) failed on every request; re-run as POST: 4 queries, 257 of 306 artists on Wikidata (84%) |

## Results

### Combined, per language group

| group | tracks | genre (tag vector) | a specific genre (below a family root) | year | popularity (Deezer) | energy and valence | tempo (Deezer) |
|---|---:|---:|---:|---:|---:|---:|---:|
| en | 527 |  97.9% |  79.1% |  99.8% |  87.7% |  97.9% |  28.8% |
| vi | 108 |  90.7% |  27.8% | 100.0% |  84.3% |  90.7% |   0.9% |
| ko | 65 |  98.5% |  60.0% | 100.0% |  93.8% |  98.5% |   6.2% |
| zh | 3 |  66.7% |  33.3% | 100.0% |  33.3% |  66.7% |   0.0% |
| ja | 1 |   0.0% |   0.0% | 100.0% |   0.0% |   0.0% |   0.0% |
| all | 704 |  96.6% |  69.2% |  99.9% |  87.4% |  96.6% |  22.3% |

### Which source found the track at all (a hit that passes the title, artist and length checks)

| group | tracks | Deezer | MusicBrainz recording | ListenBrainz tags | Wikidata artist | iTunes |
|---|---:|---:|---:|---:|---:|---:|
| en | 527 |  87.7% |  76.7% |  72.3% |  70.0% |  88.8% |
| vi | 108 |  84.3% |  44.4% |  43.5% |  30.6% |  58.3% |
| ko | 65 |  93.8% |  84.6% |  78.5% |  81.5% |  92.3% |
| zh | 3 |  33.3% |  33.3% |   0.0% |   0.0% |  66.7% |
| ja | 1 |   0.0% |   0.0% |   0.0% |   0.0% |   0.0% |
| all | 704 |  87.4% |  72.2% |  68.0% |  64.6% |  84.2% |

### A genre from each source alone (maps to an atlas node and reaches the 0.25 floor)

| group | tracks | recording genres (MB and LB) | artist genres (MB, LB, Wikidata) | iTunes genre | Deezer album genre |
|---|---:|---:|---:|---:|---:|
| en | 527 |  22.0% |  72.7% |  87.7% |  83.3% |
| vi | 108 |   0.9% |  28.7% |  57.4% |  82.4% |
| ko | 65 |   9.2% |  83.1% |  92.3% |  32.3% |
| zh | 3 |   0.0% |   0.0% |  66.7% |  33.3% |
| ja | 1 |   0.0% |   0.0% |   0.0% |   0.0% |
| all | 704 |  17.5% |  66.5% |  83.2% |  78.1% |

### Year, by source (a year from that source exists; the first row never counts Deezer alone)

| group | tracks | any believable year | MusicBrainz | iTunes | YouTube row | Deezer |
|---|---:|---:|---:|---:|---:|---:|
| en | 527 |  99.8% |  72.5% |  88.8% |  99.2% |  87.7% |
| vi | 108 | 100.0% |  43.5% |  58.3% | 100.0% |  84.3% |
| ko | 65 | 100.0% |  81.5% |  92.3% | 100.0% |  93.8% |
| zh | 3 | 100.0% |  33.3% |  66.7% | 100.0% |  33.3% |
| ja | 1 | 100.0% |   0.0% |   0.0% | 100.0% |   0.0% |
| all | 704 |  99.9% |  68.6% |  84.2% |  99.4% |  87.4% |

### Tag coverage by the radio the track came from (what kind of music the stand-in holds)

| source file group | tracks | genre |
|---|---:|---:|
| radio | 381 |  97.9% |
| next | 189 |  92.6% |
| chip | 133 |  98.5% |
| get | 1 | 100.0% |

Vietnamese tracks with a Vietnamese-specific tag (v-pop, vinahouse, nhạc vàng, vietnamese ...): 29 of 108 (26.9%).

Tracks with no genre from any source: 24 of 704 (3.4%).

## What the numbers say

1. **The 60% rule is met with room to spare.** 96.6% of the 704 tracks get some genre, and every group with enough tracks to say so is above 90% (Latin script 97.9%, Korean 98.5%, Vietnamese 90.7%). The constrained assistant tagging job of stage 4 is **not needed to reach 60%** on this stand-in, and 2b does not add it.
2. **But "a genre" is mostly a generic one.** Only 69.2% of tracks have a specific genre, and only **27.8% of the Vietnamese tracks**; 26.9% carry a Vietnamese-specific node (v-pop, vinahouse, nhạc vàng, vietnamese ...). The rest got "pop" or "rap" from Deezer's album genre or iTunes. That is where Vietnamese mixes would sound alike. If the owner wants named Vietnamese sub-genres, the fallback would have to cover roughly **72% of Vietnamese tracks** (the specific-genre gap), and it would have to be a constrained model run (spec 9.3) over a shortlist of the curated Vietnamese nodes in the atlas, because no open source has them: MusicBrainz knows 44% of the Vietnamese recordings by name and only 29% of the artists have genres; the YouTube chips ("Vietnamese Hip Hop", "V-pop") exist only for the radio's seed track.
3. **Year and popularity are nearly free.** A believable year for 99.9% (the YouTube byline alone gives 99.4%, which is inflated here because every stand-in row came from YouTube; in the app only a track that was a radio seed has a cached row). MusicBrainz (68.6%) and iTunes (84.2%) back it up. Deezer's rank gives popularity for 87.4% (Vietnamese 84.3%).
4. **Tempo is thin and getting thinner**: Deezer's `bpm` exists for 22.3% (English 28.8%, Vietnamese 0.9%, Korean 6.2%), as the research said (near zero for editions from 2021 on). Energy and valence therefore come from the atlas descriptors, not from measured tempo.
5. **Source by source, alone:** iTunes places a genre for 83.2% of tracks, Deezer's album genre for 78.1%, artist genres (MusicBrainz, ListenBrainz, Wikidata together) for 66.5% (Vietnamese 28.7%), recording genres for 17.5% (Vietnamese 0.9%). Recording-level votes are rare; the artist is where MusicBrainz knowledge lives.
6. **Terms.** Three of the five sources are non-commercial or share-alike (Deezer; MusicBrainz tag votes; ListenBrainz, derived from them). Without Deezer, MusicBrainz and ListenBrainz the engine would run on iTunes, Wikidata, Spotify genres and the chips; on this stand-in iTunes alone reaches 83.2% of tracks and Wikidata knows 64.6% of the artists. The owner can switch each source off (Developer mode > Brain data, and the remote config key `brain.sources.<name>.enabled`); the handoff lists the terms.

## How to repeat

```
python tools/brain/coverage_extract.py <builds/music-brain-2026-11-17/yt/raw> tracks.jsonl
python tools/brain/coverage_probe.py tracks.jsonl cache
python tools/brain/coverage_wikidata.py cache
python tools/brain/coverage_report.py tracks.jsonl cache/results.json app/src/main/assets/genre-atlas-v1.json.gz
```
The probe takes about two hours (MusicBrainz is the slow host). On a real phone the same measurement is the Developer screen's "What is known about your tracks" after a few daily refreshes.
