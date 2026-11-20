# Music brain, stage 4, milestone 1 (Android): the tools, the grounding, and two stage 3 gaps

Branch `music-brain`. Read `handoff/2026-11-17-music-brain-stage1.md` to `2026-11-20-music-brain-stage3.md` first; deviations here continue their numbering (the last one there is 206). Spec: `docs/brain-spec.md` sections 0, 1, 2, 9, 14.

**Nothing in this file has run on a phone.** Every statement about behaviour on a device is a trace of the code, not an observation. The learner, the gate and the engine are still verified on synthetic listeners only, and there is no real-data number anywhere.

## What happened to this tree

An earlier attempt of this task was cut off and left about 36 uncommitted files (the served vector, brain.db 7, parts of the pure core, a draft `MusicBackend`). They were read, kept where right and finished: nothing from that attempt was discarded except two things noted below (a draft `BudgetSpent` exception, replaced by `Lookup.skipped`; the one-line `startStation` result shape).

## What was built

### Stage 3 gap A: the served vector

The learner trained and the gate predicted on a REDUCED vector rebuilt from the log (no graph, islands, tags, repeat activation, popularity, freshness). Now:

- `:brain` `ServedVector` (new): `quantize` / `dequantize`, 51 values x 4096 as little-endian int16 (102 bytes), clipped to +-32767, lower-case hex text of 204 characters. `PlayEvent.servedX: ByteArray?` (additive: the JSON line has `"servedX":"<hex>"` only when present, so every existing golden line is byte-identical).
- `StationPlanner.Pick.servedX` carries the vector a pick was scored with (read again from the same state after the list is built; it never changes which picks are made). `ListBuilder.Scored.idx` points back at the candidate.
- `LearnReplay.vectorOf(...)`: the one function live and replay call; it takes the served vector when the event has one, else the reduced rebuild (every older row). The origin one-hot is still switched on in training only.
- App: `play_event.servedX BLOB` (**brain.db version 7**, `MIGRATION_6_7 = ALTER TABLE play_event ADD COLUMN servedX BLOB`, `app/schemas/.../7.json`, `MusicBrainMigrationsTest` holds the statement and the schema diff, `MusicBrainMigrationInstrumentedTest.migrates6To7...` for a device). A row from before keeps NULL. The vector rides with the pick: `BrainItemTag.servedX` (hex), `brain.sx` extra, `PlaybackQueueItemSnapshot.brainServedX`, `StoredMixItem.sx`, `StoredHomeItem.sx`, `PlanItem.sx`, and `BrainEventRecorder` puts it in the event (`ReplayMerger` and `BrainStore` copy it).

### Stage 3 gap B: the gate rule from iOS

- `Gate.MIN_IMPROVEMENT = 0.01`, `Summary.learnedClearlyBetter`; `Gate.decide(prev, summary, now, intervalAboveZero)`: a week counts only when it beats the prior by at least 1% log-loss and, from `LearnReplay.TEST6_MIN_PLAYS` (2,000) graded plays, the bootstrap interval of test 6 is above zero. A week that is better but not clearly better ends a streak that has not switched the learned weights on and leaves one that has alone (no flapping on noise). `BrainEngine.learnerOf` computes the interval (`LearnReplay.test6(...).gainLow > 0`) only from 2,000 plays.
- **Control sessions are drawn only while the gate has the learned weights on** (`BrainEngine.rankingChoice`, `Loaded.newSession`, `BrainEventRecorder`). Test: `ControlSessionTest` "no control session is drawn while the gate has the prior on".
- Golden: `gate2.jsonl` (new), `servedvec.jsonl` (new). **Existing golden files are byte-identical** (checked: the manifest only gained lines).

### The ten tools (`TaizoTools.musicToolNames`, appended after the 16)

`resolve_tracks`, `recommend`, `build_mix`, `start_station`, `steer_session`, `explain_track`, `explain_taste`, `lookup_genre`, `similar_artists`, `new_releases`. All strict, `additionalProperties: false`, `eager_input_streaming`, **every parameter required** (so 0 of the 24 optional parameters are spent). Sentinels: `[]`, `0`, `""`, `"any"`, and `-1` for `novelty_share_pct` (deviation 212). Descriptions are short (the list is the cached prefix).

Pure (`:brain`, `AssistantRules`, golden `assistant.jsonl`): refs, `normalize` (clamps, de-duplicates, orders year bounds), the filters (`passes`, `unsupportedFilters`, `noveltyShare`), the chips (`applyChip`, `chipsFor`), the fixed factor names and `topFactors`.

App (`data/ai/claude/taizo/music/`): `MusicBackend`, `TaizoMusicTools` (executor), `TaizoGrounding`, `BrainAssistantEngine` (real backend), `AssistantMixer`.

- **resolve_tracks**: library first (free, `in_library` true), then the alias table (`track_alias` -> `track_dna` -> `yt:` / `sp:`), then one YouTube Music songs search under `HostBudget` scored by `TrackMatcher.score` and accepted at `TrackMatcher.MIN_ACCEPT_SCORE` (the playback matcher's own threshold). A found engine track is registered in `playable` before its ref is returned. Not found items carry no name (so a name nobody found can never count as returned). A turn starts at most 40 network lookups (`MAX_LOOKUPS_PER_TURN`); past that, and when the day's `HostBudget` is spent, an item is `not_found` with a reason, not retried.
- **recommend / build_mix**: `AssistantMixer.plan` builds the pool a Your Mix uses (library shortlist plus the cached radios of the seeds named, fetched within the budget when not cached; the day's own seeds when none are named), applies the request's filters to what `track_dna` knows (year, genres with ancestors, bpm, energy, moods, artist exclusions), runs `MixPlanner` (`KIND_YOUR`, the prior or the learned weights per the gate, the request's novelty share as `nuOverride`) and returns the picks with the vectors they were scored with. A filter no candidate has data for is NOT applied and is reported (`filters_not_applied`); a candidate with an unknown value never passes a filter on it. `build_mix` keeps the request for the chips (`BrainAssistantEngine.adjustMix`), lists at most 25 tracks and says how many it holds; a mix plays as `play_songs` with `song_ids ["mix:<id>"]`.
- **start_station**: song (a ref a tool returned), artist, genre, album, playlist; sets the Familiar / Blend / Discover switch from `mode` (deviation 217), starts `Station`, plays the seed through the player after the same confirm `play_songs` asks.
- **steer_session**: `scope always` is a taste adjustment (`BrainFeedback`, undoable in Settings) or the novelty switch and asks the user to tap; `session` puts 5 picks built for the change next in the queue (deviation 216).
- **explain_track**: reads only stored data: the arm and the vector from the newest event (or the pick made in this chat), plays and the finished share from `play_event`, genres and year from `track_dna`; every field it lacks is in `unknown`. Factor names come from the fixed list (`AssistantRules.FACTOR_NAMES`); no weight or raw value leaves the device.
- **explain_taste**: Your sound's `State` (summary, islands, genres, dayparts, recent moves, model), with what is not known and why.
- **lookup_genre**: atlas facts (`GenrePage`) or a mood (id = list position + 1), else the five nearest names; never an id of its own.
- **similar_artists**: the graph's edges (`graph_edge`) between the artist's recordings and others, summed by artist; scores are relative to the closest.
- **new_releases**: the saved releases file (`NewReleases.cards`), `my_artists` only.
- `play_songs`, `add_to_queue` and the playlist tools now take `yt:` / `sp:` refs and `mix:<id>`: engine tracks play through `playable` and are never imported; `create_playlist` / `add_to_playlist` save an engine track first (`EngineTrackSaver`), because a playlist holds library rows. The picks' tags (arm, surface assistant, served vector) are announced with `BrainPlayTags.expect`.

### The prompt and the grounding

- `TaizoContext.MUSIC_RULES` (spec 9.4, verbatim, plus one bullet: deviation 208) is appended to `INSTRUCTIONS` after the existing guidance; the taste digest is still after the cache breakpoint.
- `Grounding` (`:brain`, pure, golden-tested): finds `[[Name]]` marks and double-quoted phrases in the final reply, checks each against the names the conversation held, replaces a name no tool returned, drops a sentence that states a listener number (a number followed by plays, times, hours, %, ...) that no tool returned.
- `TaizoGrounding` holds what the conversation held: refs and names from the music tools, every short string in any tool's result (the older 16 too), the profile and now-playing blocks (line chunks), web search and fetch results, the listener's own words, and, for an opened chat, its history. `AskTaizoViewModel.groundedReply` runs the check on the final text (and on a partial one when Claude drops out), shows marks off while streaming, and keeps what the model wrote in the history. The placeholder is the string `taizo_unverified_name`.

## Deviations 207 and on

207. **Control sessions only while the gate is on** (the iOS rule, its deviation 163). Before this a control flag was drawn from the first play, mixing eras in the comparison.
208. **Names are marked `[[...]]` by the model.** Code cannot tell a title from other words in free text, so the prompt asks for marks; the check also reads double-quoted phrases. A name the model forgot to mark and did not quote is not checked: this is the known hole of any text-level check, and the reason the prompt names the rule.
209. **A marked name the listener typed is grounded** (whole words, two letters or more). Pure-core change (golden case added to `assistant.jsonl`): without it "tell me about Radiohead" would be answered with a placeholder.
210. **Served vector storage**: int16 of x * 4096 (a value is wrong by at most 1/8192), 102 bytes, not a Float array. Chosen so a replay and a live update read identical numbers on both platforms.
211. **The gate's 1% and interval rule**: a better-but-not-clearly-better week does not reset a streak that already switched the weights on (iOS does the same; the spec says only "better").
212. **`novelty_share_pct` uses -1 for "not said"**, not 0 as the spec's sentinel list implies, because 0 is a real answer (no new music) and a strict schema cannot be nullable.
213. **Artist refs are track refs**: `exclude_artist_refs` and `steer_session`'s artist target take a ref of one of the artist's tracks (the artist is that track's primary artist); `similar_artists` returns a ref of a representative track. `parseRef` has no artist form. `explain_track` also takes `"now"`.
214. **Arm ASK only on picks from outside the library.** The spec says arm ASK for the mix; library picks keep the arm that found them so the ASK arm's record is about asking for new music, not about the listener's own songs.
215. **The assistant's pool is the shortlist (about 500 library tracks) plus radios**, not the whole library, as a Your Mix; it never reads `served_list` repetition (no `servedTimes`) because the list is not "today's".
216. **`steer_session` with scope `session` adds picks, it does not remove any**: five songs built for the change go next in the queue, the rest of the queue follows unchanged (`tail_changed` says 5). Energy has no lasting setting, so `always` + energy changes nothing and says so. A real tail rewrite needs a player API that does not exist yet.
217. **`start_station`'s `mode` sets the global Familiar / Blend / Discover switch** (the switch is global; there is no per-station novelty).
218. **`new_releases` has only `my_artists`.** Nothing tracks "adjacent" or "country" releases; those scopes return an empty list and say why.
219. **Web search and fetch results count as held** (`noteServerResult`): a name that appears on a page the model read is not an invented name. A recommendation that names something only a web page returned passes the check; the prompt still says picks come from `recommend` / `build_mix`.
220. **A resolved or recommended track needs a ref.** A library song with neither a real Spotify id nor a known YouTube audio id has no ref and is left out of tool results.
221. **`start_station` for album / playlist / genre** picks its seeds by the library's order (`StationSeeds.pick` with no play counts), not by plays, because the backend has no engagement store.

## Cut or deferred

- **The mix card in `AskTaizoSheet`** ("Late drive - 30 songs - Play - Save") and the chips under it (`AssistantRules.chipsFor`, `BrainAssistantEngine.adjustMix`): the data, the deltas and the engine call exist and are tested; the Compose UI is milestone 2. Until then a mix plays through the chat (`play_songs` with `mix:<id>`).
- Set notes, the weekly note, the batch jobs, the one-tap "Less trap": milestones 2 and 3.
- `explain_track` does not give `why_key` for a track that is not a pick of this chat (the log does not store it); the tool lists it as unknown.
- The per-step "learning" copy is untouched (stage 3 only).
- iOS: nothing in this pass.

## For the iOS port

- **The pure core changed (golden files).** New: `servedvec.jsonl`, `gate2.jsonl`, `assistant.jsonl`. Changed nothing before them (every older line is byte-identical; `PlayEvent` JSON gained an optional key). iOS must match `ServedVector`, `LearnReplay.vectorOf` (used by BOTH the live update and the replay), `Gate.decide`'s two new rules, `AssistantRules` and `Grounding` bit for bit, and `StationPlanner.Pick.servedX` (read the vector with the same `Features.compute` call, after the list is built). `README.md` of the golden folder says how each `fn` is replayed.
- The tool schemas, names, order, descriptions' sentinels and the prompt text (`MUSIC_RULES`, including the `[[ ]]` bullet) are to be mirrored word for word (the list is a cached prefix on both).
- `play_event.servedX`: iOS' own store needs the column (nullable) and a migration; the line format is `servedX` hex, 204 lower-case characters, or absent.

## What was run, and what it said

(filled in below at the end of the pass)

## Still unverified (nothing was run on a phone)

- Every tool against the real tables: `BrainAssistantEngine` and `AssistantMixer` compile and are traced, not exercised (they need Room, the network and a library). The fake-backed tests cover the tool layer, the schemas and the grounding, not the engine.
- That the served vector makes the learner better. The test shows the two replays differ (`servedvec.jsonl` `learnedHashWithout`), not that the difference helps. No real export has been scored (`RealExportTest6` still has no export).
- The grounding check on real model output: whether the model marks names as asked, how often an unmarked name slips through, and whether the placeholder reads well.
- `resolve_tracks`' YouTube search: the score threshold is the playback matcher's; nobody has looked at false matches. The budget (40 a turn, 60 a day, shared) is a guess about how often a chat asks.
- Queue tags: that a `play_songs` of engine refs lands in the event log with surface assistant, arm ASK and the served vector (`BrainPlayTags.expect` -> `internalPlaySongs`); traced only.
- brain.db 6 to 7 on a device (`MusicBrainMigrationInstrumentedTest.migrates6To7KeepingEveryPlayAndAddingTheServedVectorColumn` not run).
- A station started from the chat: the confirm, the novelty switch change and the player call.

## How to test (on a phone, for Hoa)

1. Install over a build that has plays. Ask Taizo: "make me something like Espresso but slower for a late drive". Expect: it resolves Espresso, calls `build_mix`, says it made a mix and plays it when asked ("play it"). Every song name it says should appear in the tool results; none should show "(a name I could not check)" unless it named something it did not look up.
2. "Why do I keep getting trap?": expect numbers that match Settings > Your sound, and "I don't know" for anything the page leaves out.
3. "Slow it down": five songs next, and the queue after them unchanged.
4. "Start a station from Radiohead", "less Drake for good" (asks for a tap).
5. After a few days of listening, `Developer > brain` should still show the model state; turn control sessions on only after the gate says the learned weights are on.
