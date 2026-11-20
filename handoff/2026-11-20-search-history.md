# Search history (Android), 2026-11-20

Branch `brain-search-history`, cut from `origin/music-brain` (47c5dcb). The iOS twin is `docs/handoff/2026-11-20-search-history.md`
in the iOS repo; both apps use the same constants and the same key.

## What it is now

The Search screen shows what you searched. Before this the data was written (Room `search_history`) but nothing listed it, the
de-dupe was by exact string, and the schema had no key, kind or target.

| Rule | Value (identical on iOS) |
|---|---|
| Stored query | as typed, trimmed, cut at 100 characters, at least 2 characters |
| Key | `BrainText.fold(query)` (the brain module's fold, pinned to `brain-golden/fold.jsonl`), NOT `FoldedText`. A query that folds to nothing (`!!`) is not stored |
| Upsert | on key: newest text wins, `timestamp` = now, `count + 1`. A result tap sets kind/target, a plain submit keeps the target already there |
| Cap | 50 rows, oldest trimmed after every write |
| Suggestions | key starts with the folded typed prefix, most used first then newest, 5 at most, **in memory** over the 50 rows |
| Strip | 10 tiles at most |
| Kinds | `query | song | album | artist | playlist`, null = typed and submitted |
| Private session | `record()` does nothing (no insert, no count bump); the strip and list are hidden; one-line hint instead |

Backup JSON names (module `SEARCH_HISTORY`, same on both apps): `id, query, timestamp, key, kind, targetId, targetTitle, imageUrl, count`.
Old payloads (only `id, query, timestamp`) restore: the new fields default, the key is always recomputed, rows merge by key (count takes
the larger side so restoring twice changes nothing), the table is never cleared, 50 rows at most.

`docs/test-parity.md` lives in the iOS repo only; the parity row to extend there is "search history" (these constants and the tests below).

## Files

Data: `data/database/SearchHistoryEntity.kt` (key, kind, target_id, target_title, image_url, count; unique index on key),
`SearchHistoryDao.kt` (upsert, writeMerged, trimToCap, deleteById, ...), `PixelPlayDatabase.kt` (version 6, `MIGRATION_5_6`),
`di/AppModule.kt` (migration list). New `data/searchhistory/`: `SearchHistoryRules.kt` (all the rules, pure),
`SearchHistoryRepository.kt` (the one door; the private gate), `SearchHistoryBackupRow.kt`. `data/model/SearchHistoryItem.kt` is the UI row.
`MusicRepository`/`Impl` lost their four history methods (they moved to the new repository); `MusicRepositoryImpl` has no `searchHistoryDao` any more.

Backup: `backup/module/SearchHistoryModuleHandler.kt`, `AppDataBackupManager.kt` (deprecated path, same merge).

State: `SearchStateHolder.kt` (load once, record, remove, clear, undo), `PlayerViewModel.kt` (`searchFieldFocused`, `searchHistory`,
`onSearchResultOpened`, `undoSearchHistoryRemoval`; the start-up load is gone: the screen loads when it appears).

UI: `presentation/screens/search/components/SearchHistoryViews.kt` (strip, panel, suggestions, private hint), `SearchScreen.kt`
(pane switch Browse / History / Results, snackbar with Undo, result taps pass kind/target; the orphaned `SearchHistoryList` is gone),
`SplitPillNavigationBar.kt` + `MainActivity.kt` (field focus reported up), strings in `values/strings_screens.xml` (default language only,
like `strings_brain.xml`).

## Tests

JVM: `SearchHistoryRulesTest` (constants, key, Vietnamese NFC/NFD, upsert, cap, prefix order, migration pass, restore merge),
`SearchHistoryRepositoryTest` (private no-op, cap, undo path), `SearchHistoryModuleHandlerTest` (round trip, old payload, bad rows),
`SearchHistorySchemaTest` (the ALTER statements and the index against the exported `5.json`/`6.json`). Fake: `FakeSearchHistoryDao`.
Device: `SearchHistoryMigrationTest` (5 to 6 on a real file), `SearchHistoryDaoTest` (the SQL). Not run here (no device).

## Verification done

No local Gradle run (other builds were running on the machine). CI ("Android CI", compile, whole unit-test suite, Wear compile, debug APKs) is the check.
`app/schemas/.../PixelPlayDatabase/6.json` is the file Room's KSP exported in CI (a temporary workflow printed it; it is removed again), not hand-written.

## Deviations from the brief

- Tapping a recent row that opened something (artist, album, playlist, song) opens it; tapping a plain query row fills the field and
  searches. In the typing suggestions every row fills its typed query and searches (artwork is not shown there).
- Catalog and YouTube Music result taps record the typed query with no target (they cannot be reopened by id).
- No Settings toggle ("Save search history") yet; the private session is the only switch.
- A query that folds to nothing is dropped (also in migration and restore).
- The fill arrow only fills the field. It does not move the caret to the end: the field is a `BasicTextField(String)`, whose caret on an
  outside change is not verified.

## Unverified (no phone)

Everything visual and every gesture: the swap between the genre grid and the list when the field gains focus, keyboard inset and list
padding, the snackbar position over the nav pill and mini player, long-press removal on the strip, the caret after the fill arrow,
that the focus callback fires `false` when the field leaves composition (a guard sets it when Search is left), the migration on a real
version 5 file.
