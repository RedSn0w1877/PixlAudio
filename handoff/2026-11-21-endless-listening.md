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

---

# Milestone E2 (Android): spoken host notes, host cards

**Nothing here has run on a phone either.** Everything about sound is a trace of the code plus pure tests. Unverified list at the end of this part.

## What was added

### 1. The rules are pure and shared: `:brain` `HostVoice` (golden `hostvoice.jsonl`, NEW)

`HostVoice.decide(Inputs)` answers speak / wait / skip with a stable reason word, in this order (the first that holds ends it): `off`, `not_endless`, `notes_off`, `no_line`, `repeat`, `too_soon` (fewer than 4 songs since the last spoken line), `call`, `paused`, `route_changed` (audio route changed less than 4 s ago), `window_over` (more than 15 s since the song began), then the waits `transition` (a transition runs), `not_current` (the engine still plays the song before), `too_early` (less than 1.5 s into the track, waits the rest), then `unknown_length`, `short_left` (the song must have the line's length plus 15 s left, so the next transition never starts under the voice), `no_voice`, the wait `voice_pending`, `cant_duck`, else `speak`.
`HostVoice.cancelReason(Running)`: while a line runs, the first of `off`, `notes_off`, `not_endless`, `skip`, `pause`, `transition`, `focus`, `call`, `route`, `volume_taken`, `too_long` (8 s) stops it at once. `HostVoice.ramp(from, to, elapsedMs, rampMs)` is the straight line in thousandths used for the duck (down to 350/1000 over 250 ms, up over 400 ms, quick up 120 ms). `HostVoice.line(text)` is the note cleaned for a voice ("&" is said "and"; `* _ ~ | < > [ ] { } \ # ·` become spaces; no space before a full stop) and is "" (the note stays on screen only) when the cleaned text is over 160 characters, has more than 2 sentences, a sentence over 12 words, or a word the app never says. Text is never cut.

### 2. The duck, in the engine: `data/service/player/HostDuck.kt` (+ `DualPlayerEngine.hostDuck`)

The master player's `volume` has many writers (ReplayGain, the Spatial level lift, Sing, every transition with absolute values), `handleAudioFocus` is false and a transient loss pauses, so a system duck would pause the music and a naive write would be overwritten or mistaken for a user gesture. `HostDuck`: remembers the volume it found (`base`) and only moves between `base` and `base * 0.35`, never an absolute number; refuses unless `DualPlayerEngine.canDuckForHost()` (playing, no transition, no focus loss, `alternateRenditionOwnsVolume` false, no other duck); `MusicService.playerListener.onVolumeChanged` asks `engine.hostDuck.onMainVolumeChanged` first, so its own writes never reach ReplayGain (a 400 ms tail also absorbs the echo of the last write); **any other write while it holds the volume ends the duck without writing** (that writer's value stands, reason `volume_taken`, the host stops talking). `performTransition`, `cancelNext`, `rebuildPlayersPreservingMasterState`, a focus LOSS / LOSS_TRANSIENT and `release` call `hostDuck.abort(reason)` first, which puts `base` back at once, so nothing ever reads a ducked value as the level (the overlap reads `playerA.volume` as its starting level).

### 3. The voice: `HostSpeaker` and `HostVoiceStateHolder`

`HostSpeaker` wraps `TextToSpeech`, lazily (`ensure()` only when a note has a line while the switch is on, or when the person taps Test the voice; `shutdown()` when the host is disarmed). The notes are English text, so it picks an **English** voice (the person's own English variant first, then quality), never one that needs the network or is "notInstalled"; no usable voice means `no_voice` and silence. Plain media speech attributes; it asks for no audio focus. `HostVoiceStateHolder` is a `@Singleton` (reached as `brainSession.endless.voice`; `PlayerViewModel.init` calls `attach(queue, why)`): armed only while Spoken host notes AND Host notes are on and Endless Listening is the queue's station (before that it watches nothing and the voice does not exist). On each new song it builds the very note the player shows (`SetNoteLines.forQueue(...).current`), `HostVoice.line`, then asks `decide` every 300 ms at most until the answer is speak or skip; to speak: `hostDuck.begin()`, wait 250 ms, check `cancelReason`, speak, poll `cancelReason` every 100 ms, `hostDuck.end()`. Cancels: song change (the handler is cancelled), pause, `AudioDeviceCallback` (route changes, registered only while armed), call (`AudioManager.mode`), focus, transition and volume writes (through the duck). Every end writes one entry to the log (20 kept, in memory only).

### 4. Host cards and Settings

`HostNoteCard` (M3, flat, no blur, no shadow, no animation of its own) replaces the plain note text while Endless Listening is the queue: in the player it carries "Ask the host" beside the line (the lone chip when the song has no note), in the queue it is the small card above a row. **No close button: skipping the song dismisses it** (the note is made again for every song). Settings, "Endless Listening": Host notes (on), **Spoken host notes (off by default)**, **Test the voice** (starts the voice because the person asked, ducks like a note when it can, refuses during a call or while a note is being said, says why in a toast, hands the voice back unless the host is armed), How it works; Developer mode adds "Host voice log" (the last 20: spoke / stopped / skipped and why).

## Deviations

1. The voice is the system's own but always **English** (the note text is English); the brief said the language comes from the system TTS. A phone with no offline English voice stays silent and the log says `no_voice`.
2. The rules live in `:brain` (not in the app's engine policies) so the Swift port reproduces them from `hostvoice.jsonl`; the app side only reads the world.
3. Spoken notes need Host notes on (the switch is greyed otherwise).
4. The host does not speak after the app's player side is gone (the process kept alive only by the service): the queue comes from the player view model, so nothing new is seen. Silence is the safe failure.

## Tests

`:brain` `HostVoiceTest` (decisions, cancels, ramp, line templating and the 12-word rule over every note kind), `:brain` golden `hostvoice.jsonl` (`BrainGoldenTest` compares), app `HostDuckTest` (ramp timing in virtual time, exact give-back, abort, own writes absorbed and foreign writes let go), `HostVoiceLogTest`, `EndlessSettingsPreferenceTest` (defaults: host notes on, spoken off, intro unseen; independence), `BrainCopyTest` (reads all of `strings_endless.xml`).

## For the iOS port (E2)

- **Golden files: `hostvoice.jsonl` is NEW; `MANIFEST.sha256` and `README.md` gained its lines. No existing golden file changed.** Re-copy `brain/src/test/resources/brain-golden/` and port `HostVoice` (Swift, no regular expressions, text split on characters) to reproduce every `decide`, `cancel`, `ramp`, `line` and `estimate` line byte for byte.
- Voice: `AVSpeechSynthesizer`, created lazily on first use (switch on, or Test the voice), an English `AVSpeechSynthesisVoice` (the user's own English first, else en-US); `usesApplicationAudioSession = true` with the existing `.playback` session so it needs no `.duckOthers` (a session change would stop the music); skip with `no_voice` when no installed English voice exists. Speech cancelled with `stopSpeaking(at: .immediate)`.
- Duck: add a `duckGain` beside `replayGainVolume` in `TapParameters` (atomic, read in `ProcessingTap`); ramp it with `HostVoice.ramp` (down 250 ms to 0.35, up 400 ms, quick 120 ms), multiply, never replace; abort (give back at once) on a crossfade start, on a rebuilt deck, on interruption begin; refuse (`cant_duck`) while a crossfade runs or the interruption is active. Same rule as Android: any foreign writer of the same value ends the duck.
- Inputs: `inCall` from `AVAudioSession.sharedInstance().isOtherAudioPlaying` is NOT enough; use `CXCallObserver.calls` with a non-ended call; route change from `AVAudioSession.routeChangeNotification`; `playing`, position and remaining from the deck; `transitionRunning` from `DualDeckEngine`.
- UI: the host note card in the queue and under the player title (Liquid Glass card, no blur beyond the system's), "Ask the host" beside it; Settings: "Spoken host notes" (off), "Test the voice"; Developer mode "Host voice log" (last 20, the same words: spoke / stopped: reason / skipped: reason). Copy: the new strings are in `strings_endless.xml` (`endless_settings_spoken*`, `endless_settings_voice_*`, `endless_voice_*`).

## Unverified on a phone

The sound of the system voice and whether an offline English voice is installed by default; the duck against a crossfade, against ReplayGain on and off and with Sing or Spatial audio playing; that speech never starts a transition early or pauses the music (the TTS engine is believed not to take audio focus); route-change and call detection on real devices (an `AudioDeviceCallback` and `AudioManager.mode`, nothing else); the 1.5 s, 4 s and 15 s numbers; the card's look in the queue and the player; Test the voice with the music playing; battery of an armed host (it polls every 300 ms for up to 15 s on a song with a note, and every 100 ms while a line is spoken).

## How to test with a phone

Settings, Endless Listening: switch Spoken host notes on, tap Test the voice (with a song playing: the song should dip, the sample should be heard, the song should come back at the same loudness). Start Endless Listening from the Home card and let songs play; a card appears when a song has a note and the voice says the same words 1.5 s into that song. Skip while it talks (stops at once, music back in about a tenth of a second); pause; unplug headphones; take a call; play with crossfade on. Developer mode, "Host voice log" shows each decision and why (`too_soon`, `transition`, `short_left`, `route_changed`...); use it to calibrate the numbers in `HostVoice`.

## Review fixes (2026-11-21, after the two independent reviews)

**Golden file `endless.jsonl` CHANGED (and `MANIFEST.sha256`, `README.md`). iOS must re-copy `brain/src/test/resources/brain-golden/` and port the same changes. `hostvoice.jsonl` did not change.**

What changed, by finding (A = Android review, I = iOS review):

- **A1 dead Home card after stop/clear**: `StationStateHolder.startEndless` resumes only when `player.queueHasAny(station.seedMediaIds)` and `resume()` returned true (it now prepares an idle queue and returns false on an empty one); otherwise it starts fresh.
- **A2 / I1 one note per five tracks across windows**: pure core, `HostNotes.notes(slots, taken, sinceLast)` and `HostNotes.tracksSinceNote(ids, index, noted)`. The app keeps `HostNoteMemory` (a singleton set of song ids whose note was shown or spoken; `SetNoteLines.forQueue` marks the current track's note) and feeds the distance of the last note before the current track. One memory for the card, the queue rows and the voice (the voice builds its note through the same `forQueue`), so they agree.
- **A3 TTS under package visibility**: `<intent><action android.intent.action.TTS_SERVICE/></intent>` added to `<queries>`.
- **A4 sideways**: the note is now `A step sideways: <genre>.` (only the genre the engine flagged as `adjacent_genre`); the "from A to B" claim and `genreBefore` are gone, and the note can now be on slot 0.
- **I2 "New from X, an artist you play"**: only for a song with no recorded play (`song.id !in heard`).
- **A5 chip replies**: plurals with the real `SteerOutcome.tailChanged` (one / other); no "Five".
- **A12 / I4 chips and the app-wide switch**: `StationStore.setNoveltyForHost` applies the level without saving it, remembers the saved level, and puts it back when any other station starts or Endless Listening ends (`clear`); a switch the listener moves themselves cancels the put-back. "Back to familiar" goes to the level before the chips (Blend when that was Discover or unknown), never to Familiar. Replies say so ("until this station ends").
- **A6 seeds with real plays**: `endlessSeeds` only considers library tracks with a play weight above zero, so the card needs at least two such seeds and "Play a few songs first" can fire.
- **A7 Host notes off**: `SetNoteLines.forQueue(..., notesOff)` returns nothing at all (set notes included) when Endless Listening is the queue and Host notes is off (`rememberHostNotesOff`).
- **A8 island note**: only for a song with a recorded play (same "heard" rule as "your side" in the set notes).
- **A9 forbidden words**: whole-word check (`HostNotes.isClean`), applied to the template and the atlas sentence, not to artist / genre names ("Claude Debussy", "Atmosphere" keep their notes); `HostVoice.line(text, noteChecked = true)` for the app's own notes re-checks only the length.
- **A11 intro seen flag**: marked on Start, Not now and the back gesture (`BackHandler`), no longer on every disposal.
- **A13 HostSpeaker init callback**: bound to its own engine; a late callback of a shut-down engine frees that engine and leaves the current one alone.
- **A14 error guard**: the host voice scope has a `CoroutineExceptionHandler` and the per-track body catches (rethrowing cancellation), logging `error` in the host voice log.

Not changed, and why: **A10** (engine picks have no genre or year, so only "New from X" fires for them) is a data limit, not a defect; judge the notes on a library-heavy queue. **A15** (repeated shortlist and alias reads) is deferred: sharing one `hostFacts` per queue and index is a refactor of three callers, all reads are off the main thread, and it is risky without a phone. **I3** is A5. I5 to I10 are iOS only (CXCallObserver, synthesizer creation, cosmetic).

For iOS to port: `HostNotes.notes(sinceLast)` / `tracksSinceNote` and the new sideways text and whole-word `isClean` / `isShort` / `isSpeakable` split (then re-check `endless.jsonl` byte for byte); a memory of noted song ids feeding `sinceLast` in `SetNoteLines.forQueue`; the heard rule for island and new-from-artist; seeds need weight above zero; host-only novelty with put-back, "Back to familiar" = level before; real counts in chip replies; Host notes off hides the set notes too; `startEndless` resumes only when the queue holds a seed.

Unverified on a phone: everything about sound (system voice, TTS now visible under package visibility, ducking against a crossfade), the card after Stop, the look of the host cards, the chips' real counts on a thin pool.

New tests: `HostNotes` / `EndlessTest` (cadence across windows, sideways text, whole-word words), `SetNoteLinesHostTest` (heard rules, one note per five tracks while advancing one track at a time, sideways, notes off), `StationAndPlayTagsTest` (host novelty and put-back), `BrainMixesTest` (no seed without plays), `BrainCopyTest` (plural replies).
