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

`resolve_tracks`, `recommend`, `build_mix`, `start_station`, `steer_session`, `explain_track`, `explain_taste`, `lookup_genre`, `similar_artists`, `new_releases`. All `additionalProperties: false`, `eager_input_streaming`, five strict (deviation 222), **every parameter required** (so 0 of the 24 optional parameters are spent); only five of them are strict (deviation 222). Sentinels: `[]`, `0`, `""`, `"any"`, and `-1` for `novelty_share_pct` (deviation 212). Descriptions are short (the list is the cached prefix).

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
222. **Only five of the ten tools are strict.** A request may hold at most 20 strict tools (the older test `TaizoTasteToolsTest` already pinned it); 15 of the older 16 are strict, so 5 of the new ones can be. `resolve_tracks`, `recommend`, `build_mix`, `start_station` and `steer_session` are strict; `explain_track`, `explain_taste`, `lookup_genre`, `similar_artists` and `new_releases` (small, read-only, one or two parameters) are not, like `search_library`. Their schemas are still closed (`additionalProperties: false`, every parameter required) and every input is validated locally before it runs. The spec's "strict: true" for all ten cannot hold with the 16 already in place. iOS: same split.
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

Commands (Windows, `ANDROID_HOME` set, one Gradle at a time):

- `./gradlew :brain:generateGolden` then `./gradlew :brain:test`: **273 tests, 0 failed**. `BrainGoldenTest` compares the committed golden folder byte for byte; after regeneration only `assistant.jsonl` (one new case) and the manifest and README changed.
- `./gradlew :app:testDebugUnitTest --tests "com.theveloper.pixelplay.data.brain.*" --tests "com.theveloper.pixelplay.data.recommendation.*" --tests "com.theveloper.pixelplay.data.ai.*"`: **493 tests, 1 skipped (pre-existing), 0 failed**. That includes `RecommendationGoldenEqualityTest` (legacy mixes byte for byte: 5 tests), `MusicBrainMigrationsTest` (15), `ControlSessionTest` (7, one new), `TaizoMusicToolsTest` (22), `TaizoGroundingTest` (8), `TaizoMusicPromptTest` (2), `ClaudeRulesTest`, `TaizoTasteToolsTest` (two assertions updated: the 16 old tools keep their place, and at most 20 are strict).
- `./gradlew :brain:test :app:assembleDebug`: **BUILD SUCCESSFUL** (the debug APK builds with the new Hilt graph, so `BrainAssistantEngine` and its injected dependencies resolve).

Two things went wrong on the way and were fixed: a Windows line break inside a Kotlin char literal (compile error) and `TaizoTasteToolsTest` needing the executor's two new constructor arguments. The earlier first run of `TaizoTasteToolsTest` also caught the 20-strict-tool limit (deviation 222), which the spec's table does not account for.

Not run: `./gradlew :app:testDebugUnitTest` for the whole app (only the three packages above), `connectedDebugAndroidTest` (no device), `:wear:assembleDebug`.

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

---

# Milestone 2 (Android): what the listener sees

Branch `music-brain`, not pushed. Everything below was **traced and unit-tested, none of it ran on a phone**: the cards, the chips, the set notes, the weekly note and the batch jobs are unverified on a device, and there is still no real-data number anywhere (the learner and the gate are verified on synthetic listeners only).

First: milestone 1 compiled and its tests passed on arrival (`:brain:test` and `:app:compileDebugKotlin`, BUILD SUCCESSFUL), so nothing of it needed fixing before this was added.

## What was built (per task)

1. **A mix from a sentence.** `build_mix` result -> a card in the Ask Taizo sheet (`TaizoChatItem.Mix`, `presentation/components/taizo/TaizoCards.kt`): "Late drive - 30 songs", up to four rows, Play, Save, chips. `MixCard.of(BuiltMix)` (`taizo/music/MixCard.kt`) builds it from the engine's own answer; the model's text is never read. Play = `TaizoToolExecutor.playMix` (queue becomes the mix, the picks' tags announced through `BrainPlayTags.expect`, so surface assistant / arm ASK / served vector reach the event log). Save = a playlist of the listener's (engine tracks are saved first, `isAiGenerated = true`). **Chips are deltas on the stored request applied on the phone, no model call**: the pure part is `AssistantCards` in `:brain` (`allChips` = milestone 1's `chipsFor` + one pace chip, More new, More familiar, at most 6, only chips that can change the request and only for a filter the data can answer; `chipLabel` = "Less Sabrina Carpenter", "More R&B", "Shorter", "Slower", "Calmer", ...; golden `assistantcards.jsonl`), the call is `BrainAssistantEngine.adjustMix` (`AssistantRules.applyChip` on the stored request, the same pool and scorer). A tap replaces the card in place.
2. **Explanations.** `explain_taste` / `explain_track` record the genres and artists they returned; after the answer `AssistantCards.lessTarget(userText, named)` offers "Less trap" only when the name is in a tool result AND is a whole-word run of what the listener wrote (`TaizoChatItem.Less`). The tap writes the existing taste adjustment (`BrainFeedback.adjustGenre(name, more = false)` / `lessFrom`), shows "Done. You will get less trap.", and Undo takes it back. "Why this song" gained the top factors: `ServedFactors` (new, `data/brain`) turns the served vector into the fixed names of `AssistantRules.FACTOR_NAMES` under the weights the pick was ranked with; the sheet shows "What helped: ..." / "What held it back: ..." (`WhySheetState.factors`), from the vector the queue item carries (`QueueWhy.servedX`, `brain.sx`), else from the newest play of that song that stored one, else nothing. `explain_track` uses the same class.
3. **Steering.** `steer_session` was complete in milestone 1; this pass checked the path and fixed one lie: an energy change with no energy data for the pool used to report `applied: true` after adding five songs that were not any calmer. It now answers `applied: false` and says why. The tool description gained "Slow it down is energy_down, pick it up energy_up" (the iOS description must say the same). Scope `always` still goes through the `ConfirmCard` (`TaizoMusicTools.confirmationFor`).
4. **Set notes.** `SetNotes` in `:brain` (golden `setnotes.jsonl`): a note describes a run of 2 to 5 songs the listener has followed by a song they do not have ("Three from your V-pop side, then something new from HIEUTHUHAI.", "Two you know, then something new from X.", "Three from Mono, then ...", "Three from your V-pop side." when no new song follows), at most one per five tracks, none when it would not be true. The queue (`SetNoteRowLabel` above the first row of a group, `QueueBottomSheet`) and the player (`SetNoteLine` under the artist, `FullPlayerContent.PlayerSongInfo`) compute it from the queue itself, again on every queue or current-song change, so it can never describe a queue that is gone. No model, no network, no per-track call.
5. **Weekly note.** `WeeklyNote` in `:brain` (golden `weeklynote.jsonl`): the template (the fallback), the evidence rows, the check on a cloud answer (2 to 3 sentences, at most 420 characters, no forbidden word, no "learn", every name and number in the evidence, `[[ ]]` marks taken off). `data/brain/WeeklyNoteRepository`: made when First Listen is opened (`MixDetailViewModel`), never at app start; **ONE cloud call a week** through `ClaudeService.complete` (only on the automatic route, online, budget left; marked as asked in `weekly_note.json` BEFORE the call, so a crash or a double tap cannot make a second one); offline it is not attempted and not counted; an answer that fails the check is replaced by the template. The note is the same text for the week while the evidence is unchanged.
6. **Batch jobs.** `AssistantHelp` (`data/brain`), called from `AppRefreshWork.assistantHelp()` after `rebuildBrain` inside `BrainRefreshRunner` (one job, never a second). Switch "Let Taizo help the engine" (Settings > AI features, `BrainSwitch.assistHelpAllowed`: local switch OFF by default AND the remote `brain.assist.enabled` not false). Three jobs, each only when something is missing from the cache: (a) island names and one line each, one call; (b) genres for tracks no source knows (tag source 10; 50 tracks a call, 100 a run, each constrained to a shortlist of up to 30 atlas ids), written as provider-10 claims by `DnaAssembler.withAssistantClaims` and **taken out again when the switch is turned off**; (c) artist suggestions, one well-known song each, looked up like `resolve_tracks` (library, alias table, one budgeted YouTube search; 12 a run), drawn by `AssistantMixer` as an extra artist list for requests that want new music (arm ASK). All answers go through `AssistBatch` (golden `assistbatch.jsonl`): an invalid id, a name not made of the island's own genre words, a name that is an artist's, a description with a digit, a tag not on that track's shortlist is dropped, never shown. Cached forever in `assist_cache.json` by (model id, prompt version, input hash) (`AssistCache`); an answer that did not validate is cached as "asked, nothing usable". **An assistant island name is read only while the switch is on and only while the island still holds the genres it was written for** (`YourSoundRepository.nameOf`); without the switch the atlas names are used as before.
7. **No network.** `StationSentence.parse` reads "start a station from X" (and "play some jazz radio", "radio for X") by rules, so it needs no model and no connection: `TaisDjEngine.respond` returns `TaizoTurn.Station(name)` before the media route and `AskTaizoViewModel.startStationFromSentence` tries X as an artist, a genre, a playlist and an album of the listener's, through the same `BrainAssistantEngine.startStation` the chat tool uses. The phone's own model is not asked to name a track; it is not needed to read this shape (deviation 231).
8. **Copy.** New strings are in `strings_brain.xml` (plurals included). `BrainCopyTest` has a new test over every `taizo_*`, `settings_ai_*`, `settings_ask_taizo*`, `settings_claude_*` string and the new `brain_taizo_*`, `brain_assist_*`, `brain_weekly_*`, `brain_why_factors_*` ones: no forbidden word, no "learn", never the vendor or the model. The words written in `:brain` (set notes, the weekly template, chip labels, the factor names) are scanned by `Stage4M2Test`. The system prompts of the weekly note and the batch jobs never name the vendor or a product.

## Deviations 223 and on

223. **Save is a playlist, not a `served_list` row.** `served_list` is the served log, trimmed after 60 days; a saved mix must stay. A card's Play and Save work on the mix this chat built; an opened old chat shows the text only (cards are not persisted, deviation 224).
224. **Cards are not saved with the chat.** `TaizoChatStore` keeps text; the mix lives in memory (8 mixes per executor).
225. **A chip makes a new mix id.** The model's history still holds the old `mix_id`; `play_songs ["mix:<old>"]` still plays the old list (the engine keeps 8). The model is not told about a chip (no message goes to it); the new tracks are noted as held so a reply may name them.
226. **"Less X" is offered for genres of the `genres` scope and the summary's top genres and for the artist of an explained track,** not for islands or dayparts (no name to put in the chip).
227. **`explain_track` now uses the weights the pick was ranked with** (learned only while the gate has them on and the pick was ranked by them); milestone 1 always used the prior's. A pick with no stored vector has no factors.
228. **Set notes read the library's own genre field** (`Song.genre`, the first of the artist's genres) and "new" means "not a library row" (`EngineTrack.isEngineOnly`), not the atlas and not "never played". The note under the player title describes the group that starts at the current song.
229. **Weekly note evidence is what the list itself says**: the number of songs, how many are not in the library, the first three artists, the genre most of the list's adjacent-genre picks carry (blank when none), the share of the last 7 days' plays that were first plays (Your sound's figure). The spec's "the island next to it" is not available for First Listen and is left blank. One call a week, counted before the call.
230. **Tag source 10's shortlist is the genres the listener plays plus genres of the artist's own region (up to 30)**, not a "family prior"; the track's region is its artist's stored country when it is known. A track with fewer than 6 candidates is skipped. The source's trust stays 0.25 and `Tags.merge` lets it speak only when the others are weak.
231. **The offline station is read by rules, not by the phone's model.** The code has no model path that names a seed; reading this one fixed shape needs none. A sentence the rules do not match goes the old way (the model, or the library search).
232. **Energy steering is refused when there is no energy data** (new rule above) and the `steer_session` description changed by one sentence (the cached prefix moves once more; iOS must mirror it).
233. **ASK suggestions feed assistant mixes only** (`AssistantMixer`), not Home, Daily Mix or autoplay: the ASK arm is drawn only where the assistant asked. The suggestions go through the ordinary pool filters (no karaoke / live / ignored three times / blocked).

## Cut or deferred

- The mix card is not shown from the on-device (offline) chat path; offline a station starts by rules and a sentence that is not a station goes the old way.
- No prompt-injection pass on the batch jobs' inputs beyond the validators (the inputs are the app's own genre and artist names).
- The batch jobs' cost is not measured; the budget is the existing monthly one and a call only happens when the cache lacks an answer (islands: when their genres change; tags: 100 tracks a run at most; suggestions: once a week).
- Set notes in the iOS queue, the iOS weekly note and the iOS jobs: next.

## For the iOS port (milestone 2)

- **New golden files** (the Swift pure core must match them bit for bit): `assistantcards.jsonl`, `setnotes.jsonl`, `weeklynote.jsonl`, `assistbatch.jsonl`. No older file changed (the manifest and README gained lines). New pure code: `AssistantCards`, `SetNotes`, `WeeklyNote`, `AssistBatch`. `README.md` of the golden folder says how each `fn` is replayed.
- Mirror word for word: the strings of `strings_brain.xml` added in this pass; `WeeklyNoteRepository.SYSTEM`, the three batch prompts in `AssistantHelp`; the `steer_session` description sentence; chip labels (`AssistantCards.chipLabel`) and the set-note templates (`SetNotes.text`) are in the pure core.
- Stored formats: `weekly_note.json` (`week`, `asked`, `cloudHash`, `cloudText`), `assist_cache.json` (`islands`, `tags`, `ask`, `askLatest`, `tagRids`, keyed by `AssistBatch.cacheKey(model id, kind, input)` in hex); provider 10 in `track_dna` claims; preference `brain_assist_help_enabled` (default false); remote key `brain.assist.enabled`.
- The iOS "Why this song" needs the factors (`ServedFactors`), the card needs Play / Save / chips with no model call, and the offline station reads `StationSentence`'s shape.

## What was run, and what it said (milestone 2)

All on Windows, `ANDROID_HOME` set, one Gradle at a time (the machine was busy: the last run took 43 minutes; `--no-build-cache` because another session held the shared build-cache lock; two Windows file locks under `app/build` were cleared by deleting the named directory, as the notes say).

- `./gradlew :brain:generateGolden` then `:brain:test`: **291 tests, 0 failed, 1 skipped** (`RealExportTest6`, no export). The golden folder gained four files; **no older golden line changed** (`git diff` shows only the manifest and README gaining lines).
- `./gradlew --no-build-cache --continue :brain:test :app:testDebugUnitTest :app:assembleDebug`: **BUILD SUCCESSFUL**. The whole app suite: **2,451 tests, 0 failed, 2 skipped**. `:app:assembleDebug` built both ABI splits (the Hilt graph resolves with `ServedFactors`, `AssistCache`, `AssistantHelp` and `WeeklyNoteRepository`). `RecommendationGoldenEqualityTest` and the legacy mix tests ran untouched and passed.
- New tests: `Stage4M2Test` (18 in `:brain`: chips, labels, "Less X", set notes, the weekly note's check, the batch checks, forbidden words in every template), `TaizoMusicToolsTest` (+3: the card and its chips, a chip as a delta, the names a "Less X" may be about), `StationSentenceTest` (2), `SetNoteLinesTest` (2), `DnaAssemblerTest` (+1: source 10 is the weakest and is taken out again), `BrainCopyTest` (+1: Ask Taizo's words and the new settings row).
- Not run: `:app:connectedDebugAndroidTest` (no device) and `:wear:assembleDebug`.

## Still unverified (nothing ran on a phone)

- Every screen: the mix card (Play, Save, the chips, busy and failed states), the "Less X" chip and Undo, the factors in "Why this song", the set note in the queue and under the player title (and whether a line under the title moves the hero layout), the weekly note on First Listen, the new switch row in Settings.
- That a `build_mix` chip really builds a different list through `BrainAssistantEngine.adjustMix` (it is traced and tested over a fake, not run over the real tables), and that Play from a card reaches the event log with surface assistant / arm ASK / the served vector.
- The weekly note's cloud call: how often the model marks names with `[[ ]]` and how often `WeeklyNote.check` rejects its answer (then the template shows). The one-call-a-week counting is traced.
- The batch jobs: none has run. Not known: how often the model's island names pass `AssistBatch`, how many tracks no source knows on a real library, what a week costs (a call happens only when the cache lacks an answer; no figure is claimed), whether provider-10 claims ever tip a track over `Tags.merge`'s threshold, and that ASK suggestions found by YouTube search are the song asked for.
- The offline station: that `startStation` for an artist not in the library fails fast offline (it tries the network once through `beginArtistStation`, inside a try / catch).
- Set notes use `Song.genre` (Spotify's artist genres): on a library where that column is empty, only the artist and "you know" forms appear.
- There is still no real-data number: the learner, the gate and anything read from them are verified on synthetic listeners only.

## How to test on a phone (for Hoa)

1. Ask Taizo: "make me something like Espresso but slower for a late drive". Expect a card "Late drive - 30 songs", four rows, Play, Save and chips ("Less <artist>", "More <genre>", "Shorter", "Slower" ...). Tap "Shorter": the card changes to 20 songs with no spinner longer than a second and no chat message. Tap Play: the queue becomes the mix and Why-this-song on a pick says it was asked for. Tap Save: a playlist "Late drive" appears.
2. "Why do I keep getting trap?" (use a genre you really play): the answer's numbers match Settings > Your sound, and a chip "Less trap" appears under it; tap it, then Undo.
3. Open "Why this song" on an engine pick in the queue: "What helped: ..." and "What held it back: ..." lines with plain words only.
4. Queue 3 songs of one genre from your library, then an engine pick: a line above the first of them ("Three from your <genre> side, then something new from <artist>.") and the same line under the player title; remove a song and the line changes or goes.
5. Open First Listen on a Monday build with Wi-Fi: a "This week" note of two or three sentences appears under the title; airplane mode: the template text. Open it again: the same text, no new call.
6. Settings > AI features: "Let Taizo help the engine" is OFF. Turn it on, leave the phone charging idle until the refresh worker has run; Your sound may show assistant-written island names; turn it off: the atlas names come back and the genres the job added are taken out at the next run.
7. Airplane mode: "start a station from Radiohead" (an artist in your library) starts a station.

# Review fixes (Android): what an independent reviewer found, and what was done

A reviewer read the whole stage 4 diff and reported 16 findings. Each was checked against the code. All were real in some part; the fixes are below, with what was deliberately not done.

## What changed

1. **Grounding, the second pass (pure core).** `Grounding.check(..., scanUnmarked = true)` now also looks at runs of capitalised words outside every mark and quote (`Grounding.unmarkedNames`): "You might also try Radiohead or OK Computer" is caught. A sentence opener that is one capitalised word is skipped, the first word of a longer run may be grammar ("Try Radiohead"), the app's own words, days and months are never names (`NOT_NAMES`), and a part of a returned name counts ("Good Luck" of "Good Luck, Babe!"). Quotes now include curly and straight single quotes (at a word start) and guillemets. A registered free text matches **whole words** only (`"Sia"` is not inside `"asia"`), and so does the listener's own text. The default of `scanUnmarked` is false (the weekly note's check and every older golden line are unchanged); Ask Taizo turns it on. **Not found:** a name written in lower case, a sentence opener that is a name ("Radiohead are good."), numbers written as words ("twelve plays"), numbers with no unit word.
2. **Where the check runs.** The reply shown while it streams is checked on every publish (an unclosed `[[` and the word being typed are held back), so Stop keeps checked text. A reply whose sentences all drop is replaced by a fixed line ("I cannot say that from what PixlAudio has recorded."), never by the raw text. The phone's model and the plain cloud route (`TaizoTurn.Conversation`) go through the same check, with the library facts the phone's model was shown registered as what it may say; the media intro line is dropped (the plain "Found N songs" stays) when it names anything unverified.
3. **Refs and ids.** `explain_track`, `steer_session` targets and `play_songs` engine refs must be refs a tool returned in this chat (refs inside an older chat's tool results count when it is opened again). Genre and mood ids in `recommend` / `build_mix` must be ids `lookup_genre` returned in this chat (reported as `ids_ignored`). Steering a genre for good stores only a genre of the atlas.
4. **`resolve_tracks`.** With an artist given and no length known, the matcher's bar is 0.62, not 0.55 (a title at least 92 percent the same; "Love" is not "Lose").
5. **Seeds.** A seed whose radio could not be had is reported (`seeds_not_used` in the tool result, a line on the card). The Slower / Calmer chip is offered only when some track of the pool has a tempo / an energy. A chip takes the old record's place (the engine keeps 16 mixes). Play and Save use exactly the songs the card shows.
6. **Factor words.** A negative affinity is said as a skip ("you often skip this artist"), never as "you play this artist" held back; a skip with a weight of the other sign is not reported. `AssistantRules.NEGATIVE_NAMES`.
7. **"Less X".** Offered only when exactly one named genre or artist is a whole-word mention of the question and none has love / like / enjoy / adore / favourite / into / want in the three words before it. The artist of an explained track is its primary credited artist, not a comma split.
8. **Save.** All or nothing: after the import every engine track must have a library row, or no playlist is made and the card shows "That did not work. Try again." (`create_playlist` and `add_to_playlist` skip the unsaved tracks and say so in `not_saved`).
9. **Confirmations.** They name the artist or the genre, never an id. A station started after text from a web page or lyrics asks too.
10. **Set notes.** "Your side" is a song with a recorded play; "something new" is an engine track with none (`SetNoteHistory`, read off the main thread, no note until it is loaded). A library song nobody played is neither. Notes are keyed by queue position.
11. **Weekly note.** One call per calendar week (not per rebuild of the list). The cloud text is kept while the list's own facts are unchanged and the new-music share is within 3 points of what it said.
12. **Tagging job.** A failed call is not repeated until next week; "known artists" are every artist with a recorded play, not three per island; `explain_track` says when the genres were suggested by the assistant (`genres_note`); the Settings copy no longer says the whole job runs once a week.
13. **`similar_artists`** gives a rank, not a score (the best neighbour of a thin graph was always 100).
14. **The offline sentence** tries your own artists, then genres, playlists and albums, and only then searches for an artist; the reply names what was matched.

## Not done (and why)

- **"All ten tools strict"** is not possible: a request may hold 20 strict tools (deviation 222). Unchanged.
- **The Why sheet's weights use the current p0**, not the p0 the pick was served with: p0 is not stored with the event, and storing it is a brain.db change. The factor words are still true of the vector, and approximately of the weights.
- **The weekly note's own check does not scan for unmarked names** (the model is told to mark them; a capitalised "First Listen" would trip a scan and send every note to the template).
- **Model replies are not rewritten for the forbidden words**; only app-written text is checked by `BrainCopyTest`.
- The distinct "this mix expired" message: the failed line is now generic ("That did not work. Try again.").

## Golden files: iOS must re-sync

`assistant.jsonl` (topFactors lines now read negative affinities; four `grounding2` lines are new) and `assistantcards.jsonl` (three new `lessTarget` lines, and the rule changed), plus `MANIFEST.sha256` and `README.md`. Port: `Grounding.unmarkedNames` and the `scanUnmarked` argument, the single-quote and guillemet spans, whole-word `inText` (padded with a space on both sides), `AssistantRules.NEGATIVE_NAMES`, `AssistantCards.lessTarget` (exactly one remaining name; the liking words; a name inside a longer match is the same mention). `Char.isUpperCase` / `isLetterOrDigit` decide a capital and a word character: the Swift port must use the same Unicode properties on UTF-16 units.


## The findings, one by one

| # | Finding | Verdict |
|---|---|---|
| 1 | One-sentence reply with an invented number shown raw | Real. Fixed (`groundedReply`: a blank result becomes a fixed "cannot say that" line, never the raw text). |
| 2 | Validator sees only marked or double-quoted spans | Real. Fixed in part: unmarked capitalised runs, single quotes and guillemets are checked, `inText` is whole-word. Still open: lower-case names, a sentence opener that is a name, numbers written as words (see "Not done"). |
| 3 | A stopped turn keeps unchecked streamed text | Real. Fixed (`streamingText` checks every publish; an unclosed mark and the word being typed are held back). |
| 4 | `resolve_tracks` accepts a near title from the same artist | Real. Fixed (bar 0.62 when an artist is given and no length is known). |
| 5 | A seed with no radio is dropped silently | Real. Fixed (`seeds_not_used`, a card line). |
| 6 | Negative affinities read as the opposite | Real. Fixed (skip wording, `NEGATIVE_NAMES`). The p0 used for the weights is still today's, not the served one (needs a brain.db column; deferred). |
| 7 | "Less X" can target a genre the listener likes | Real. Fixed (`lessTarget`: exactly one named target, liking words block it, primary credited artist). |
| 8 | Save reports success when the import failed | Real. Fixed (all or nothing, `not_saved`). |
| 9 | Confirmation shows ids; station after web text asks nothing | Real. Fixed. |
| 10 | Genre ids and several refs unchecked | Real. Fixed (`ids_ignored`, refs must come from this chat). |
| 11 | Set notes can be untrue, keyed by song id | Real. Fixed (`SetNoteHistory`, keyed by position). |
| 12 | Weekly note keyed to build time; cloud text dropped | Real. Fixed (calendar week; text kept while the facts hold). |
| 13 | Tagging job not weekly, failures retried, guessed genres look known | Real. Fixed (failure gated for a week, real known artists, `genres_note`). |
| 14 | `similar_artists` score is always 100 for the best | Real. Fixed (rank, no score). |
| 15 | Mix records evicted at 8; Slower chip offered with no tempo | Real. Fixed (chip replaces the record, 16 kept, chips from data). The distinct "mix expired" line is not done. |
| 16 | The sentence-to-station path searches the network first | Real. Fixed (own artists, genres, playlists, albums, then the network). |

"All ten tools strict" is not met and cannot be: a request holds at most 20 strict tools (deviation 222); the other five are checked locally by `ToolInputValidator`.

## How to test on a phone, whole stage 3 and 4 (for Hoa)

Stage 3 (the learner), see the two lists in `2026-11-20-music-brain-stage3.md` ("How to test on a phone (stage 3 done means)" and "(this pass)"). In short:
1. Update over the last build; Your sound says "Still getting to know you" under 300 graded plays and nothing says "learning" before the model is in use.
2. After first use the rebuild runs once in the background; app start does no brain work; `dumpsys jobscheduler | grep -c brain_refresh` shows one job.
3. The learned weights turn on only after two weekly rebuilds in which they predicted at least 1 percent better, with the test 6 interval above zero from 2,000 plays; until then the screen says "standard recipe".
4. One session in ten is a control session, and none while the gate is off.
5. Controls > Reset returns to the standard recipe within one rebuild; plays, blocked artists and taste changes stay.
6. Export and restore a backup.

Stage 4 (Taizo): see "How to test on a phone (for Hoa)" at the end of milestone 2 above, and these checks for the review fixes:
7. Ask "something like Espresso but slower" with the YouTube budget spent or offline: the card says which seed was not used, never a plain "Like Espresso".
8. Ask "why do I keep getting pop? I love rock": no "Less rock" chip.
9. Tap Stop in the middle of an answer: what stays on screen has no invented names.
10. Save a mix offline: "That did not work. Try again." and no playlist.
11. Open a queue of tracks you never played: no "your X side" note.

## What nobody has verified on a device

Everything on screen (card, chips, Less X, factors, set notes, weekly note, Settings switch); a chip's rebuild over the real tables; Play from a card reaching the event log with surface assistant and arm ASK; how often the model marks names with `[[ ]]` and how often the grounding check or the weekly check turns a reply down; the batch jobs end to end, their cost, and whether source-10 tags move a track; the set-note line under the player title shifting the hero layout; an offline artist station's start time; the replay time on a real log; the gate on a real listener. There is still no real-data number anywhere. The iOS side was not read or run here.
