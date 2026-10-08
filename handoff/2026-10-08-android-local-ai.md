# 2026-10-08: local AI on Android (branch `port-local-ai`)

This is the Android port of the 2026-10-07 iOS local-AI work (phase 1 "on-device by default" and
phase 2 "downloadable model"). It follows `handoff/2026-10-07-plans/local-ai.json` and DECISIONS ›
Local AI, adapted to Android: Gemini Nano (AICore) instead of Apple's model, and Gemma 4 E2B on
LiteRT-LM instead of the Core ML Qwen. iOS notes used for behaviour: `docs/handoff/2026-10-07-local-ai.md`
and `2026-10-07-local-model.md` in PixlAudio-iOS.

## What you get

- **On-device AI is the default for every AI feature.** A fresh install, and anyone who had Gemini
  picked without a key, uses the phone's own AI. Nothing is ever sent to a cloud provider unless you
  turn one on, and **nothing falls back to the cloud** when on-device fails.
- **Settings › AI features** (Studio and Music Intelligence cards unchanged on top):
  1. **On-device AI** first.
     - The Gemini Nano row: "In use", "Checking…", "Android needs to download it first" with a
       **Get ready** button, a progress bar while Android downloads it, or why it can't run here.
     - **Use downloaded AI model** (off by default): "Gemma 4 E2B · 2.6 GB · used instead of Gemini
       Nano". Hidden on 32-bit-only phones (LiteRT-LM ships 64-bit libraries only).
     - Turning it on never starts a download by itself. It opens a dialog first: the size, Wi-Fi
       recommended, the Apache 2.0 licence with a "View licence" link, a **Download on mobile data**
       checkbox (off), and a free-space check (2.6 GB plus 10 %).
     - The model row then shows "Not downloaded · 2.6 GB", "Waiting to download", "1.2 GB of 2.6 GB"
       with a bar and **Cancel**, "Checking the download…", and finally "Downloaded · 2.6 GB on this
       phone" with **Delete**. Delete asks first, and it also turns the switch off, so AI goes back
       to Gemini Nano.
     - Until the file is there, Gemini Nano keeps answering. If the downloaded model ever fails to
       load, that request is answered by Gemini Nano and the row says why.
     - **Remove old imported model (N MB)** appears only if the old MediaPipe import is still on the
       phone. The import option itself is gone; LiteRT-LM can't read those files.
  2. **Cloud assistants (optional)**: a **Use a cloud assistant** switch, off by default. When it's on
     you get the provider picker (no more "(Free)" label), Save on usage, the API key, model and base
     URL rows, unchanged. Turning it off and on again brings back the last provider.
  3. Prompt behaviour (persona) is unchanged. **Advanced** shows only Temperature on-device; with a
     cloud assistant it shows everything as before.
- **Each feature has an on-device path sized for a ~4k-token model:**
  - **AI playlists** (the Daily Mix sparkle sheet, the AI Playlist Lab 5-150 songs, Daily Mix
    refine): plan → fill → order. The model reads the request plus the library's top 25 genres and 30
    artists (no song ids) and answers a small JSON plan. The app fills the playlist from the library
    (genre, artist, mood and energy through a genre-family table, era, play counts for "familiar vs
    fresh", favourites, at most 3 songs per artist unless you named them), then the model orders the
    first 40 by number. If the model refuses or the answer is unusable, the request's own keywords
    make the plan. Daily Mix refine now really seeds from the current mix.
  - **Taizo:** answers grounded in facts from your library ("Radiohead: 34 songs, 5 albums (…), 210
    plays"; "what do I listen to most" gets your top artists and genres). It remembers the chat while
    the sheet is open. "Play some chill songs" shows the card at once, and the intro line arrives a
    moment later instead of holding it back. Send is disabled while Taizo answers. That fixes the
    old bug where two quick sends left a "Thinking" bubble spinning forever.
  - **Translate via AI** (lyrics More sheet, long-press): each distinct line is translated once, in
    numbered chunks (fewer lines per chunk for CJK/Vietnamese). The app puts the timestamps back, so
    the stored LRC is the same two-lines-per-timestamp format as the cloud path. "Already in your
    language" still short-circuits. The lyrics UI itself was not touched.
  - **Home greeting and insight:** on-device users now get the AI greeting. It used to return early
    because there was no API key. The insight is capped at 160 tokens, the headline at 40.
- **On-device errors have their own messages**, never "No Internet Connection". Examples: "On-device AI
  is busy. Try again in a few seconds.", "Stay in PixlAudio while on-device AI is working: Android
  pauses it when the app leaves the screen.", "That request is too long for on-device AI…". Every
  error table checks them first. The provider's display name is now "On-device", not "On-Device
  (Offline)".
- **Library "With AI", the playlist sheet and Daily Mix's sparkle** open for on-device users. Before,
  they were locked out because on-device had no key. When AI can't run, the toast or the dialog says
  why ("On-device AI isn't available on this phone. Turn on a cloud assistant…", or "Getting on-device
  AI ready…", which also asks Android to fetch Gemini Nano).
- The models are prewarmed when the Taizo sheet, the AI playlist sheet or the Lab open.

## Backups and the preference keys (the iOS review bug)

| Key | Android | In .pxpl backups |
|---|---|---|
| `ai_provider` | default now `ON_DEVICE` (was `GEMINI`); unknown values read as on-device | yes |
| `ai_cloud_provider` | the provider "Use a cloud assistant" turns back on | yes (iOS skips it as unknown) |
| `ai_provider_migrated_v1` | the one-time move has run | yes |
| `ai_downloaded_model_enabled` | "Use downloaded AI model" | **no**: in `backupExcludedKeyNames` |

- `ai_downloaded_model_enabled` is the same name iOS settled on after its review. It doesn't end in
  `_model` / `_api_key` / `_system_prompt` / `_base_url`, which iOS's catalogue treats as portable
  per-provider keys.
- On Android it is kept out of backups and is never cleared by a restore, because the model file
  lives in `noBackupFilesDir` and doesn't travel. `AiProviderMigrationTest` pins export, clear and
  import, and the suffix rule.
- **The migration** (`AiPreferencesRepository.migrateProviderIfNeeded`, one atomic DataStore edit)
  runs at startup and again at the end of `GlobalSettingsModuleHandler.restore`. An old backup brings
  back GEMINI without the flag, so it's converted once more. A restored provider that has its key
  stays on the cloud.
- The rules: a provider that needs a key but has none → on-device. Ollama/Custom without a base URL →
  on-device. Never picked, but a Gemini key saved → stays Gemini, so the old default keeps working.
  In each case the choice is remembered as `ai_cloud_provider`.

## Code map

| Piece | Where |
|---|---|
| Errors (`OnDeviceFailure`, `OnDeviceAiException`, ML Kit / LiteRT mapping, `findOnDeviceFailure`) | `data/ai/local/OnDeviceFailure.kt` |
| Gemini Nano (status, Get ready, warmup, Mutex-serialized generate) | `data/ai/local/GeminiNanoEngine.kt` |
| Gemma 4 on LiteRT-LM (one engine, GPU then CPU, released after 90 s idle / app hidden / switch off / delete; cancel stops the decode) | `data/ai/local/GemmaLiteRtEngine.kt` |
| Engine choice + routes (`LocalAi`, `AiRoute`, `AiRouteResolver`) | `data/ai/local/LocalAi.kt` |
| Token estimate and budgets / compact instructions | `TokenBudget.kt`, `OnDevicePrompts.kt` |
| Model pin (URL @ revision, size, SHA-256, licence) | `DownloadedModelCatalog.kt` |
| Download (Range resume, 200 restarts, size + SHA-256, free space) | `ModelFileDownloader.kt` (pure) + `data/worker/LocalModelDownloadWorker.kt` (foreground dataSync) |
| Model state / download / cancel / delete | `DownloadedModelManager.kt` |
| Old MediaPipe import (size, remove) | `LegacyImportedModel.kt` |
| Routing in the handler (no provider chain on-device; the cloud chain never contains on-device; cache key includes the engine) | `data/ai/AiHandler.kt` |
| Playlists on-device | `data/ai/curator/` (`OnDevicePlaylistCurator`, `PlaylistPlan` + parser + `OrderResponseParser`, `PlaylistPlanFiller`) and the branch in `AiPlaylistGenerator` |
| Translation on-device | `LyricsAiChunker.kt`, `OnDeviceLyricsTranslator.kt`, used from `AiStateHolder.translateLyrics` |
| Taizo | `data/tais/dj/{TaisDjEngine, TaizoMemory, LibraryLookup}.kt`, `TaisChatViewModel`, `TaisChatSheet` (send disabled + prewarm only) |
| Availability (replaces PlayerViewModel's 12-way key combine) | `presentation/viewmodel/AiAvailabilityStateHolder.kt`; `PlayerViewModel.aiAvailability`, `hasActiveAiProviderApiKey` (now "usable"), `aiUnavailableMessage()`, `prewarmAi()` |
| Error tables | `AiErrorMessages.kt`; first rule in `AiStateHolder.resolveAiErrorMessage` and `LyricsStateHolder.translateLyricsViaAi`'s onFailure (only those lines) |
| Settings UI | `presentation/screens/LocalAiSettingsRows.kt`; the AI_INTEGRATION block in `SettingsCategoryScreen.kt`; `SettingsViewModel` |
| Background guard | `AiWorker` refuses the on-device route (AICore blocks background use) |
| Dependencies | `mlkit-genai-prompt` 1.0.0-beta4, `litertlm-android` 0.18.0 (MediaPipe tasks-genai removed); ProGuard keep for `com.google.ai.edge.litertlm.**`; `uses-native-library` libOpenCL / libvndksupport (optional) |
| Notices | `THIRD_PARTY_NOTICES.md`: LiteRT-LM, Gemma 4 (downloaded, not bundled), ML Kit GenAI terms |
| Strings (English) | `values/strings.xml`, "On-device AI" block |

## Where this differs from the plan (and why)

- **Taizo's memory is text in the prompt for both engines.** The plan suggested a persistent Gemma
  conversation (its KV cache as memory). One code path, the same 700-token budget on Nano and Gemma,
  and no long-lived native session to manage. The library lookup is injected as facts, not a
  LiteRT-LM `@Tool` (tool calls aren't proven with Gemma 4 yet).
- **No `countTokens` round trip.** Prompts are sized with the estimator. A translation chunk the model
  still finds too long is retried once in halves. A plan request that's too long uses the keyword
  plan.
- **System instructions:** `isSystemPromptAvailable()` decides whether the instruction is folded into
  the prompt. The plan suggested a retry on NOT_SUPPORTED instead.
- **Gemma's JSON-schema constrained decoding isn't used for the plan.** Only the order step asks for a
  regex, and if constrained decoding fails it retries once without it. Lenient parsing covers both.
- **The downloaded-model "one-line notice" is the model row in Settings** (the reason it failed to
  load), not a toast during the request.
- **The metered-download choice is not a stored preference.** It's a checkbox in the confirm dialog,
  passed to the worker. That's one less key in backups.
- **AiPlaylistSheet's 5..50 clamp was skipped.** The on-device fill handles any size up to 150, and the
  sheet belongs to the glass item.
- **Cancel deletes the partial file** ("not now" frees the space). A network drop keeps it, and the
  retry resumes with HTTP Range.

## Where Android differs from iOS

- **The downloadable model:** Gemma 4 E2B on LiteRT-LM (2.6 GB, Apache-2.0, Google's
  `litert-community` file at a pinned revision). iOS uses Qwen2.5 1.5B on Core ML (896 MB).
- **When the switch is on but the model isn't downloaded,** Gemini Nano keeps answering. iOS says
  "isn't on this iPhone yet". Android only shows that message when Nano can't run either.
- **Lyrics:** Android has only "Translate via AI" (now on-device, chunked). The iOS system-translator
  "Translate" belongs to the lyrics item.
- **Taizo's library lookup:** facts in the prompt, where iOS uses a `searchLibrary` tool.

## What CI proved

- [Android CI](https://github.com/RedSn0w1877/PixlAudio/actions/runs/37778354402) run 37778354402 on
  `port-local-ai` @ `ea1b8ee` is green:
  - `:app:compileDebugKotlin` (the Hilt graph with the new engines, the worker and the state holder);
  - `:app:testDebugUnitTest`, including the new tests below;
  - `:wear:compileDebugKotlin`;
  - `:app:assembleDebug -Ppixelplay.enableAbiSplits=true`, with the arm64 APK uploaded as
    `pixlaudio-arm64-debug-apk`.
  - The same jobs were green on the two earlier commits too (runs 37775268834 and 37776854827).
- [Build Phone APK (Release)](https://github.com/RedSn0w1877/PixlAudio/actions/runs/37776992451), run
  37776992451, dispatched by hand on `2c23be1`, is green:
  - `:app:assembleRelease -Ppixelplay.enableAbiSplits=true` with R8, so the LiteRT-LM keep rules and
    ML Kit's consumer rules hold;
  - both split APKs were signed and verified. The armeabi-v7a split builds without LiteRT-LM's
    (64-bit-only) libraries.
- New unit tests:
  - `OnDeviceErrorMapperTest`, `OnDeviceErrorMessagesTest` (parses `values/strings.xml` for the
    forbidden words);
  - `TokenBudgetTest`, `AiRouteResolverTest` (routes and availability);
  - `AiProviderMigrationTest` (real DataStore: migration, restore, cloud switch, backup exclusion of
    the switch);
  - `PlaylistPlanParserTest` and `OrderResponseParserTest`, `PlaylistPlanFillerTest` (200-song
    fixture);
  - `LyricsAiChunkerTest` (the rebuilt LRC passes `LyricsImportSecurity`);
  - `TaizoMemoryTest` and `LibraryLookupCoreTest`, `TaisChatViewModelTest`;
  - `ModelFileDownloaderTest` (resume, Range ignored, checksum mismatch, no space, server errors,
    stop, state mapping, the pin).
  - `PlayerViewModelTest` gets the new state holder mock. `AiProviderSupportTest` is unchanged and
    green.
- The arm64 debug APK artifact is about 1.3 MB bigger than main's (zipped): ML Kit GenAI plus
  LiteRT-LM, minus MediaPipe.

## Not verified (needs the phone)

Nothing here has run on a device. Gemini Nano, AICore, LiteRT-LM, the GPU backend and the 2.6 GB
download can't run on CI or in JVM tests. The unit tests cover the pure parts: routing, availability,
migration, backup exclusion, error mapping and messages, budgets, plan parsing, fill and order,
translation chunking, Taizo memory and lookup, the chat ViewModel, and the downloader's resume,
verify and failure paths.

## Hoa's Pixel 10 Pro checklist

Settings › AI features first:
- [ ] The Gemini Nano row says "In use" (or "Android needs to download it first": tap **Get ready**,
      watch the bar, then "In use").
- [ ] "Use a cloud assistant" is off. If you had Gemini picked without a key, it's off too. If you had a
      key, it's on with Gemini.
- [ ] Advanced shows only Temperature.

In airplane mode (on-device only):
- [ ] Home: the greeting turns into an AI line after launch, and expanding the card writes an insight.
- [ ] Daily Mix sparkle: "rainy day indie", 10-15 songs → a playlist, no "No Internet Connection".
- [ ] Library › Create playlist › With AI (the Lab): 24 songs, then 150 songs → playlists of 24 and 150.
- [ ] Daily Mix › refine with a prompt → the mix updates.
- [ ] Taizo: "what are my top artists?" (uses your library), a follow-up that needs the previous
      answer, then "play some chill songs" (the card at once, the intro a moment later). Try sending
      twice quickly: the second send waits.
- [ ] Lyrics › More › long-press "Translate via AI" on a Vietnamese song and on an English one.
- [ ] Start an AI playlist and switch to another app mid-way: you get the "Stay in PixlAudio…" message,
      not a network error.

The downloaded model (Wi-Fi, about 3 GB free):
- [ ] Turn on "Use downloaded AI model": the dialog shows 2.6 GB, the licence link and the mobile-data
      checkbox. Download.
- [ ] Progress moves in the row and in the "Downloading AI model" notification. Cancel works. Download
      again, leave the app, and it keeps going.
- [ ] After "Checking the download…" it says "Downloaded · 2.6 GB", and the Gemini Nano row says "the
      downloaded model answers instead". Repeat a few of the airplane-mode checks. Note how long the
      first request takes (the model load, up to ~10 s), and that no "couldn't load" line appears under
      the model row afterwards.
- [ ] Play music for 10+ minutes after using it. Playback must not stop, since the model unloads when
      the app is hidden.
- [ ] Delete: the confirmation, the space comes back, the switch turns off, and AI works on Gemini Nano
      again.
- [ ] Make a backup and restore it: "Use downloaded AI model" keeps its value.

Both themes:
- [ ] The new Settings rows look right in Material and in Liquid Glass mode.

## Next step

Hoa runs the checklist on a CI APK from this branch (artifact `pixlaudio-arm64-debug-apk`). Then the
integrator merges `port-local-ai` with the rest of the batch. Expect small conflicts:
- `PlayerViewModel` and `PlayerViewModelTest` (other items add constructor args);
- `SettingsCategoryScreen`;
- `LyricsStateHolder` (lyrics item; only the onFailure lines here);
- `TaisChatSheet` (glass item; only `enabled` and the prewarm here).

If Gemini Nano turns out slow or weak for playlists on the phone, the cheapest levers are in
`OnDevicePrompts` (budgets) and `PlaylistPlanFiller` (scoring), not the engines. Known leftovers:
- "Generate with AI" in PlaylistBottomSheet only sets `showAiPlaylistSheet`, and the sheet is
  rendered only on Daily Mix (a latent bug from before, noted in the plan);
- the Gemma engine isn't serialized with TAIS Studio's GPU/NPU work yet.
