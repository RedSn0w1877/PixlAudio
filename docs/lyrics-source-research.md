# Synced lyric sources for PixelPlayer

Research date: September 7, 2026. Audience: PixelPlayer Beta 2 maintainers.

Use available catalog timing before acoustic generation. Two useful public integrations
are ready for this approach: LRCLIB's newer Lyricsfile field and AMLL's native TTML API.
Neither establishes universal word-sync coverage. Very large commercial catalogs exist,
but free full-word access was not verified.

| Source | Evidence | Decision |
| --- | --- | --- |
| LRCLIB | A live record for **Diffuse / acloudyskye**, ID 36497565, contains 190 timed fragments across 34 word-timed lines. The legacy LRC field has no word tags. | Parse the `lyricsfile` field before legacy LRC. Preserve its absolute millisecond timestamps and whitespace. |
| AMLL | Official live status reported 3,275 entries. Native API supports platform IDs and TTML; exact Spotify lookup successfully returned ME!. | Use native Spotify lookup; allow only strict title, artist and album fallback. Do not advertise this as a massive catalog. |
| Musixmatch | Official API documents richsync with fragment offsets, recording IDs and duration filters. | A suitable provider agreement/key is required. A free tier granting full word sync could not be verified. |
| LyricFind | Its January 2025 announcement reports over 12 million lyric entries and over 1.3 million word-synced songs. | Strong future commercial candidate; current public material does not supply a verified free word-sync contract. |
| Apple Music | MusicKit documents a lyric-availability Boolean. No public word-lyric retrieval endpoint was found in the published catalog API. | Do not treat Apple's in-app lyrics as an available Android API. |
| Instagram | Public word-lyric retrieval was not verified; official changelog retrieval was rate limited. | Keep this as an unresolved source, not a working integration. |

## What was verified on the affected song

For **A COLD PLAY / The Kid LAROI**, LRCLIB records 26765889 and 26749018
match the approximately 178.626-second recording and start at **13.440 seconds**.
They supply line timings, not word timings. The AMLL exact Spotify lookup returned no
entry. Searches also returned incompatible-duration duplicates, so a title match alone
is insufficient. The app should use this verified line timing rather than manufacture
word timestamps at the start of the instrumental intro.

The phone's saved generated lyric data had all 387 word timestamps at zero. Its parser
paired equal-time lines as translations, causing a resync to keep only half the original
text. The parser fix retains separate word-timed lines. Backups preserve the complete
40-line, 387-word text. A computer whole-song acoustic experiment still placed the first
word too early, despite improving many later boundaries, so that result is rejected by
conservative evidence checks rather than shipped as successful alignment.

## API and format notes

The [Lyricsfile draft specification](https://github.com/tranxuanthang/lyricsfile/blob/main/SPECIFICATION.md)
uses absolute milliseconds and can store actual word timestamps absent from legacy LRC.
The parser must preserve strings such as “No” and “On,” which YAML 1.1 implicit typing
can otherwise convert to booleans. Unknown versions, duplicate keys, unsafe object tags,
invalid timings and unresolved nonzero offsets should not silently replace saved lyrics.
The [live LRCLIB proof record](https://lrclib.net/api/get/36497565) and
[LRCLIB documentation](https://lrclib.net/docs) are the primary access references.

[AMLL native API documentation](https://amll.dev/reference/http-api/native) and its
[OpenAPI specification](https://amll.dev/api/ttml/openapi.yaml) document exact platform
lookup and metadata search. Metadata responses have no recording-duration field; a last
lyric timestamp must not be used as track duration. The
[project README](https://github.com/amll-dev/amll-ttml-db/blob/main/README.md) limits timing
assurance primarily to matching NetEase recordings and notes that external contributions
retain their original source terms; its CC0 notice is not a blanket claim that all
underlying song lyrics are copyright-free. Mirrors are availability fallbacks, not new catalogs.

[Musixmatch richsync documentation](https://docs.musixmatch.com/api-reference/lyrics-catalog/track-richsync-get)
and [implementation guidelines](https://docs.musixmatch.com/implementation-guidelines)
cover identifiers, timing offsets and attribution. Public pricing links were unavailable;
conflicting third-party free-tier claims were excluded. Its
[API terms](https://about.musixmatch.com/apiterms) also impose use-specific restrictions,
so an arbitrary developer key is not evidence of entitlement for all player/AI functions.

[LyricFind's January 2025 catalog announcement](https://www.lyricfind.com/press/jan7-lyricfind-celebrates-stellar-2024)
is the source of the catalog counts; these are dated provider claims, not a current census.
Its [Lyric Display page](https://www.lyricfind.com/products/lyric-display) describes word sync
and partner access. Apple's [hasLyrics documentation](https://developer.apple.com/documentation/musickit/song/haslyrics)
and [Songs API](https://developer.apple.com/documentation/applemusicapi/songs-api) establish
what the published API exposes, without ruling out separate private partnerships.

## Research boundary

Primary provider documentation, source specifications, official repositories and small live
API probes were checked. Discovery covered LRCLIB, AMLL, Musixmatch, LyricFind, Apple,
Instagram, Karalyr and scraper aggregators. The latter candidates did not establish
substantial independent accessible coverage. Research stopped once two usable public
integrations and the large-catalog access dependencies were verified. No accounts were
created, paid access purchased, or audio uploaded. The subsequent client endpoint probe is documented below.


## Follow-up: user-suggested providers and formats

The [SyncLRC repository](https://github.com/TharukRenuja/SyncLRC) describes an on-demand gateway over LRCLIB, LDDC and syncedlyrics, not an independent owned catalog. Its documented public `/lyrics` request returned HTTP 403 during this session; it is not enabled as a verified fallback.

A normal request to the suggested Musixmatch desktop guest-token endpoint succeeded, but the supplied macro request for A COLD PLAY contained no RichSync body. No browser impersonation or WAF workaround was used. A token alone does not establish word-sync catalog access. RichSync conversion is implemented separately from provider entitlement/authentication; no working free Musixmatch integration is claimed.

NetEase `/api/search/get/web` returned an opaque result string in this region. The public `/api/search/get` returned ordinary track metadata without special headers. `/api/song/lyric/v1` returned 458 timed fragments for test recording 1382781549. Three A COLD PLAY candidates had no YRC. The adapter uses exact title/artist/album matching and a 1.5-second duration tolerance, checks up to two candidates, and preserves LRCLIB line-sync fallback. This is an undocumented service endpoint with availability risk, not a promised licensed production catalog.

The [AMLL format documentation](https://amll.dev/en/guides/lyric/formats) confirms YRC syntax `(start,duration,0)text`; all start times are absolute. The user-provided angle-bracket example was incorrect. New format/converter details are in [lyrics-json-format.md](lyrics-json-format.md).


## Reported line-only songs: confirmed integration defects

Live probes on September 7 found YRC for Notion (NetEase 1899851212: 260 source fragments)
and Like That (1401435094: 526 source fragments). The former matched recording metadata;
the latter differed only in featured-credit formatting across Spotify and NetEase.
Both contained zero-duration punctuation alongside a positive-duration fragment at the
same onset. Rejecting any zero duration incorrectly discarded the entire lyric document.
The converter now joins those punctuation/censor markers into that existing timed
fragment, preserving complete text and all positive onsets without creating timestamps.
Tests use sanitized source structures with lyric words replaced by synthetic text.
Metadata matching accepts credited featured artists across fields while retaining
live/remix/acoustic qualifiers, exact album checks and the duration tolerance.
