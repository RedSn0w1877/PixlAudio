# Endless Listening, milestone E1 (Android): the station, the Home card, "Ask the host", the intro

Branch `endless-listening`, cut from `origin/music-brain` (stages 1 to 4 landed: set notes and the ten tools exist). Read `handoff/2026-11-21-music-brain-stage4.md` first. Spec: the design report (a station, not a mix; text host notes first, spoken lines in E2).

**Nothing here has run on a phone.** Every statement about behaviour on a device is a trace of the code. Unverified list at the end.

## What happened to this tree

An earlier attempt of this task was cut off after commit `5fd1a9e` (the pure core) and left about 28 uncommitted files. They were read, kept where right and finished (no file was thrown away): the commits are in `git log` (station, Home card + intro + settings, host notes, Ask the host, docs).

## What was built

### 1. The station (`Station.SOURCE_ENDLESS = 6`)

- `Station.kt`: `SOURCE_ENDLESS = 6`, `isEndless`, `Station.endless(seedMediaIds, title, nowMs)`; `decode` accepts sources 0..6 (strings saved before still decode; 7 is refused). Kind stays `SEED_STATION_SONG`, so the AutoplayController, StationPlanner, reasons, skips, direction changes and control sessions are the code stage 2 to 4 already have: **AutoplayController is unchanged**. A station with several seeds already asks for the radio of each seed; the harness test proves it for six seeds.
- `:brain` `EndlessSeeds` (pure, golden `endless.jsonl`): one seed per taste island, biggest island first, at most 5, a distinct artist each; with fewer than two usable islands, the top artists, one track each. The app reads the numbers and hands them over: `data/brain/EndlessSeedSource.kt` (`BrainMixes.endlessSeeds(now)`) draws from the **Your Mix shortlist** (the pool Your Mix is planned from, cached for the day), so the station is planned on that pool; `endlessIslandNames` names the islands the way Daily Mixes do ("Soft pop & R&B"; an unnamed island is left out).
- `StationStateHolder.startEndless(player)`: picks the station up when it is already the queue's (resume), else picks seeds, starts the first seed and the rest follow through autoplay batches. Nothing is read before the call. It is saved and restored by `StationStore` like any station (title, source, seeds); novelty (Familiar / Blend / Discover) survives with it.
- Exempt from the one-mix-a-day rule: it is not a mix (no `served_list` row, no `MixSpec`).
- The queue header (`AutoplayDivider`) says "Endless Listening" and "Keeps going from what you play, until you stop it".

### 2. The Home card

`HomePlanBuilder.endlessCard`: first card of "Made for you", present when there are at least 2 seeds, `MixKinds.ENDLESS = 7`, `PlanMix.mixId = "endless"`, subtitle = island names (real ones, or none), covers from the seeds' art. A failure leaves the card out and the rest of the plan alone. `HomePlanStateHolder` titles it "Endless Listening" and says "Starts from X and keeps going." (or "Starts from what you play and keeps going." with no island name). The card shows "Keeps going" instead of a song count and has a small menu with "How it works" (replays the intro). A tap: first time opens the intro, afterwards starts or resumes the station. `PlanMix` and `MixCardUi` were already in `app/compose_stability.conf`; nothing new was needed.

### 3. Host notes

`:brain` `HostNotes` (pure, golden `endless.jsonl`): kinds `KIND_NEW_FROM_ARTIST` (10), `KIND_SIDEWAYS` (11), `KIND_ISLAND` (12), `KIND_GENRE_FACT` (13), `KIND_YEAR` (14), templates over real slots only, a slot with nothing to fill drops that kind, at most one per 5 tracks counting the set notes already in the queue (`Taken`), never inside the tracks a set note describes, every sentence at most 12 words, 2 sentences at most, forbidden words dropped (`WeeklyNote.FORBIDDEN`). `factOf` takes the first sentence of an atlas description only if it is short (a long one is not cut).

App: `HostNoteFacts` (island name of a library song through `track_alias` and the island fit; the atlas' description of a genre, never loading the atlas), `EndlessStateHolder.hostFacts`, `SetNoteLines.forQueue(..., host)`, `rememberHostFacts`. The two surfaces are the stage 4 ones: the row label in the queue and the line under the player title. Facts are read only while Endless Listening is the queue's station and Host notes is on, off the main thread, for the next 40 songs.

### 4. Ask the host

- Entry: a small "Ask the host" chip under the queue header's count and under the player's note, shown only while Endless Listening is the queue's station (`AskHostEntry.kt`). Both call `EndlessStateHolder.openHostSheet()`; `MainActivity` draws one `AskTaizoSheet(hostMode = true)`.
- Chips (on the phone, **no model call**, so no part of the monthly cap): Something new = novelty Discover; Back to familiar = Familiar; Why this song = the existing `WhyThisSongSheet`; More upbeat / Slower / Surprise me = `BrainAssistantEngine.steer("energy_up" | "energy_down" | "more_new", scope session)`: five songs built for the change go next; what it cannot do it says in plain words.
- Typed text goes through the existing agent and route unchanged (cloud only with the switch on, online and inside the cap; else the phone path) with `TaizoContext.HOST_ADDENDUM` as the volatile system block after the cache breakpoint (cached prefix identical). The grounding rules are unchanged: nothing is added to the checks, no name that no tool returned.

### 5. The intro and settings

`EndlessIntroScreen` (route `endless_intro`): three pages, exact copy, `HorizontalPager`, `MotionScheme.expressive()` dots, the setup flow's own entrance, five bars driven by ONE `Animatable` read in `drawBehind` (no recomposition, no allocation per frame), run only while the screen is STARTED and the system's remove-animations is off (then the bars stand still), static colour from the art of the first seed (one `ColorSchemeProcessor` read, throttled; the theme's primary until it is there). Seen flag `endless_intro_seen` (DataStore). Start listening marks it seen and starts the station; Not now and back both mark it seen. Settings: new category "Endless Listening" (Host notes on by default, How it works). The spoken switch is E2.

## Deviations from the design report

1. The Home card is a `PlanMix` of kind 7 that is **not** in the `served_list` log and not among the day's mixes: the plan builder adds it directly (the report suggested the same card, built beside the mixes).
2. Host-note facts come from `EndlessStateHolder`, not from the AutoplayController: the note text is a template over the queue (as the stage 4 set notes are), so it needs no new state in the service.
3. "Sideways" uses the engine's own `adjacent_genre` reason and the nearest earlier genre in the window; "new from an artist you play" uses `new_release` (the saved releases hold only artists the listener plays).
4. `steer_session` is called directly by the chips (the executor path without a model), scope session only.
5. The new settings category is a separate "Endless Listening" screen (not a row in AI), so it shows without Developer mode.

## Cut or deferred

Spoken host lines (E2). "A step sideways" and island notes are text only. No QR / Bluetooth work here (other milestone).

## Tests

Run locally (all green): EndlessStationTest 3, SetNoteLinesHostTest 5, SetNoteLinesTest 4, AutoplayControllerHarnessTest 7, BrainMixesTest 14 (two new), BrainCopyTest (one new test for strings_endless.xml); `:brain:test` green. The full suite and `:app:assembleDebug` run in the Android CI workflow. New: `EndlessStationTest` (encode/decode, old strings, 7 refused), `AutoplayControllerHarnessTest` (an endless station asks for every seed's radio and stays the queue's station), `SetNoteLinesHostTest` (host notes: real facts only, one per five, never two on a track, a set note keeps the host away), `BrainMixesTest` (seeds from top artists without islands, none for an empty library), `:brain` `EndlessTest` (+ golden `endless.jsonl`).

## For the iOS port

- **Golden files: `endless.jsonl` is NEW and `MANIFEST.sha256` gained its line. No existing golden file changed.** iOS must re-copy `brain/src/test/resources/brain-golden/` (the new file and the manifest) and port `EndlessSeeds` and `HostNotes` (Swift) to reproduce it byte for byte.
- Station: add source 6 (`decode` accepts 0..6), `isEndless`, `Station.endless(...)`; the island seed picker is pure (islands: id, weight, tracks (rid, aid, weight); artists for the fallback). The app side reads the Your Mix shortlist, the island fit and the affinity tables.
- Home: `MixKinds.ENDLESS = 7` card first in "Made for you", only with at least 2 seeds, subtitle "Starts from {island names} and keeps going." or "Starts from what you play and keeps going.", card tag "Keeps going", menu "How it works". Tap: intro the first time, else start or resume.
- Host notes: slots (artist, genre, genre fact, year, island name, new-from-played-artist, sideways), `HostNotes.notes(slots, taken)`, text `HostNotes.text`. Slot facts: `new_release` and `adjacent_genre` queue reasons; set notes (`Taken(index, count + 1 if it ends on a new artist)`).
- Ask the host: chips Something new / Back to familiar / Why this song / More upbeat / Slower / Surprise me, all on the phone; typed text adds the host addendum as a volatile block (copy in `TaizoContext.HOST_ADDENDUM`).
- Copy: the strings are in `app/src/main/res/values/strings_endless.xml` (the intro copy is exact); never "Apple" in iOS strings.
- The intro: three pages, one animated bar group (Reduce Motion honoured), seen flag in UserDefaults; one glass container only around the Start capsule.

## Unverified on a phone

The intro's look and the bars' smoothness; the Home card's look and placement; the "Ask the host" chip placement in the queue header and player; the sheet in host mode over the queue sheet; whether the steer chips find five songs; the island names on the card (they depend on the atlas being loaded); the cadence of host notes in a real queue; first-open versus resume behaviour; Settings category look.
