# Lyrics duet rundown (2026-11-25, branch lyrics-duet)

Owner's report: on songs with two singers (duet mode) the lyrics sometimes switch font, and some
lines are not fully on the left or right. Every bug below has a fix on Android; the "iOS" line
says what to mirror. The look is unchanged otherwise (same dynamic lyrics, same sizes, springs,
blur, glow).

## 1. Short duet lines were not flush right (the main alignment bug)

- **Cause:** `LyricLineNode.measureText` measured text with `Constraints(maxWidth = contentWidth)`.
  Compose's `TextMeasurer` then shrinks the paragraph to its own intrinsic width when
  `minWidth != maxWidth`, so `TextAlign.End` / `Center` did nothing for any line that fits on one
  row. A short singer-2 line sat at the *left* of its content box (15% from the left edge);
  only long, wrapping lines reached the right. Same for translation and romanization rows, and
  for the "center" / "end" lyrics alignment preference outside duets.
- **Fix:** measure at exactly the content width (`Constraints.fixedWidth(contentWidth)`).
- **iOS:** make sure each lyric line's text frame spans the whole content width
  (`.frame(maxWidth: .infinity, alignment: .trailing)` or equivalent) before applying the
  alignment, for the main line, translation and romanization alike. A frame that hugs the text
  reproduces this bug.

## 2. Background vocals ignored their singer's side

- **Cause:** side was decided from `VoiceRole`; a background vocal is role `BACKGROUND`, so it was
  always placed like singer 1 (left), even under a singer-2 line.
- **Fix:** `PreparedLine.duetEnd` (set by `PreparedLyricsBuilder.finish`): true for a duet line,
  for a background vocal grouped under a duet line, and for an orphan background vocal that the
  source nested in a duet line. Alignment, margins, text align and scale pivot all key on it.
  Interlude dots take `duetEnd` of the group they lead into.
- **iOS:** add the same `duetEnd` flag to the prepared line model and drive placement from it,
  never from the voice role directly.

## 3. Background vocal could jump to the other singer's line

- **Cause:** vocals are grouped with a line by time alone ("latest line already playing wins").
  When singer 2's line overlaps singer 1's, singer 1's echo got grouped under singer 2 (and
  drawn on the right).
- **Fix:** in duet songs `AppleTtmlParser` files each background vocal under voice id
  `lead-background` or `duet-background` (both role `background`, constants in
  `LyricsVoiceIds`), from the `<p>` it was nested in. The builder prefers a line of that side and
  falls back to time-only grouping. Solo songs keep plain `background`; old cached docs without
  these ids behave exactly as before.
- **iOS:** same two voice ids in the TTML parser and the same preference in grouping.

## 4. Undeclared TTML agents lost the duet

- **Cause:** voices came only from the `<ttm:agent>` declarations; a file using
  `ttm:agent="v2"` without declaring it put every line on the lead side.
- **Fix:** undeclared agents are resolved in order of appearance: first singer = lead (unless a
  declared person already is), `v1000`+ (Apple's group ids) = lead side, every other = duet.
- **iOS:** mirror in the TTML agent mapping.

## 5. Right-to-left text could flip a singer

- **Cause:** lead lines in duets used `TextAlign.Start` and the pivot flipped for RTL text, so an
  Arabic/Hebrew singer-1 line could move to the right.
- **Fix:** duet sides are physical: singer 1 = left (`TextAlign.Left`, pivot 0), singer 2 =
  right (`TextAlign.Right`, pivot 1). Outside duets the preference still uses start/end. Also the
  scale pivot for an RTL line with the "end" preference is now its real (left) edge.
- **iOS:** use `.leading`/`.trailing` only outside duets; in duets use explicit left/right.

## 6. "The font changes" on some songs (often duets)

- **Cause:** `LyricsSheet` decides once per song whether the lyrics face (Google Sans Flex,
  `gflex_variable.ttf`) can draw all of it, and otherwise uses the system font for the whole
  song. The check (written for the previous face) only accepted ASCII plus a few quotes, so any
  accent ("señorita", "Beyoncé", "café", Vietnamese) switched the whole song to the system font.
  Duets with a Spanish or accented line hit it a lot.
- **Fix:** `LyricsFontCoverage` holds the face's real character map (Latin-1, Latin Extended-A,
  Vietnamese, typographic spaces and marks; read from the font with fontTools). `LyricsSheet` and
  the sync screens (`isCoveredByAppFont`) both use it. Still one decision per song, never per
  line. Every line, singer 1, singer 2, background, uses the same family (background, translation
  and romanization only differ by size and their optical-size cut, as before).
- **iOS:** SF Pro covers these scripts already; if iOS has an equivalent "covered by app font"
  check, make it the same per-song decision and do not fall back for accented Latin.

## Golden alignment table (width 1000 px, margins 24 / 44 px)

| song | line | left pad | right pad | text align | pivot |
|---|---|---|---|---|---|
| duet | singer 1 / its background / group (`v1000`) | 24 | max(44, 15% w) = 150 | Left | 0 |
| duet | singer 2 / its background | max(24, 15% w) = 150 | 24 | Right | 1 |
| solo | pref start | 24 | 44 | Start | 0 (RTL text 1) |
| solo | pref center | 34 | 34 | Center | 0.5 |
| solo | pref end | 44 | 24 | End | 1 (RTL text 0) |

Duets ignore the alignment preference. "Both"/group lines stay on the lead (left) side, as the
existing design had them.

## Tests

- `LyricLineAlignmentTest`: the table above.
- `DuetLyricsTest`: Apple-shaped duet TTML end to end (sides of every line, background vocal
  ownership under overlap, interlude side, undeclared agents, solo songs, LRC duets, old docs).
- `LyricsFontCoverageTest`: accented Latin covered, CJK/Cyrillic/Arabic/Greek not, song-level
  decision includes translations and words.
- `AppleTtmlParserTest`: updated for the new background voice ids in duet songs.

## Checked, no bug found

- Scroll target with overlapping duet lines: lowest-index hot lead line, stable.
- Sync offset: clock time = position + offset, tap-to-seek = line start - offset; consistent.
- Interlude dots: sit at the same 24 px outer margin as the side they lead into.
- Word wipe / emphasis pieces: positioned from the main layout's boxes, so they follow the new
  alignment with no change.

## Left / not verified

- Not tested on the phone; verified by unit tests and code reading only.
- Translations added later by `LyricsTranslationApplier` are not part of the per-song font
  decision; a translation in a script the face lacks falls back per glyph inside that row only.
- Background vocals keep their smaller optical-size cut (spec §0); if the owner still sees "a
  different font" on echo lines, that is the opsz axis, not a family switch.
