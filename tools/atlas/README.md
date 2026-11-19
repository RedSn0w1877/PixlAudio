# Genre atlas build (docs/brain-spec.md, 3.5)

Builds `app/src/main/assets/genre-atlas-v1.json.gz`, the genre graph both apps bundle (byte-identical; the iOS app copies the
file from this repo's branch `music-brain`). Python 3, standard library only.

```
python tools/atlas/fetch_sources.py genres      # MusicBrainz genre list, 23 requests (CC0)
python tools/atlas/fetch_sources.py wikidata    # labels, aliases, inception, country, parents, influences, 2 SPARQL queries (CC0)
python tools/atlas/fetch_sources.py relations   # MusicBrainz genre pages, ~2,200 requests at 1 per 1.2 s, resumable, ~1 hour (CC0)
python tools/atlas/build_atlas.py               # offline, deterministic: writes the asset and the SHA-256 the tests pin
./gradlew :brain:generateGolden                 # the atlas, tags and dna golden files read the asset: regenerate and commit
```

`sources/` is checked in, so `build_atlas.py` needs no network. Re-run `fetch_sources.py` a few times a year; only CC0 inputs are
bundled (MusicBrainz tag votes are CC BY-NC-SA and are fetched at run time, never here).

| file | what |
|---|---|
| `sources/mb_genres.json` | the MusicBrainz genre list: id, name |
| `sources/mb_relations.json` | "subgenre of", "fusion of", "influenced by", area, per genre (crawled from the genre pages) |
| `sources/wikidata_genres.json` | Wikidata label, aliases, inception year, country, and the parent and influence edges |
| `sources/ids.tsv` | **append-only** key to atlas id. Tag blobs on phones store these ids: never renumber, never delete. A new genre gets the next free id |
| `hand/families.tsv` | the 24 family roots (3.5 step 5); a genre takes the nearest root along its parents |
| `hand/curated.tsv` | nodes no open source has: the Vietnamese and Korean corner, soundtrack, a few classical and electronic nodes |
| `hand/descriptors_*.tsv` | energy, valence, danceability, acousticness, tempo range, up to 4 moods and a paragraph, for about 280 anchor genres (**assistant-drafted, not yet reviewed by a person**); descendants inherit from the nearest described ancestor at build time |
| `hand/moods.txt` | the 40-mood whitelist |
| `hand/source_tables.tsv` | Deezer's 28 names, the iTunes names that matter, the YouTube chip labels, mapped by hand (`-` means "not a genre") |
| `hand/aliases_extra.tsv` | spellings and Spotify-style strings (`k-pop girl group`, `nhac vang`) |

Output: one JSON object, `{"v":1,"fam":[...],"moods":[...],"src":{...},"g":[...records...]}`, keys sorted, gzip with mtime 0.
The gzip bytes depend on the zlib build; if a rebuild on another machine differs, the golden files still hold (they read the
decompressed text) but `AtlasTest`'s hash must be re-pinned from the committed asset.
