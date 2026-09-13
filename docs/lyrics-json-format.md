# PixelPlay lyrics JSON v1

This UTF-8 format stores absolute millisecond times. Line ends are exclusive; a lyric is inactive before its start, during gaps, and after its end. Syllable text preserves spaces exactly. No word timing is synthesized for line-only lyrics.

```json
{
  "format": "pixelplay-lyrics",
  "version": 1,
  "metadata": {"title": "Example", "artist": "Example artist", "durationMs": 20000},
  "voices": [{"id": "lead", "role": "lead"}, {"id": "echo", "role": "background"}],
  "lines": [
    {"startMs": 12000, "endMs": 14500, "text": "Hello there", "voiceId": "lead", "syllables": [
      {"startMs": 12000, "durationMs": 400, "text": "Hel"},
      {"startMs": 12400, "durationMs": 600, "text": "lo "},
      {"startMs": 13500, "durationMs": 1000, "text": "there"}
    ]},
    {"startMs": 13700, "endMs": 15000, "text": "there", "voiceId": "echo"}
  ]
}
```

Use `LyricsDocCodec.decode` / `encode`; models are Kotlinx Serializable. `currentSyllable` returns null in a syllable gap. `activeLines` retains concurrent voices; `findActiveLine` prefers the active lead for scrolling. Roles are lead, background, and duet. Missing syllables mean line sync. Unknown fields are tolerated for forward-compatible additions; unsupported versions, invalid voices, reversed/out-of-range times, text loss, excessive depth and oversized documents are rejected.

`WordSyncTranspilers.richSync` accepts a raw Musixmatch RichSync array. `ts` and `te` use seconds; fragment `o` is relative to the line. RichSync provides starts, so fragment durations are derived from the next fragment or line end; these are not separately measured vocal offsets. Missing fragment data retains line sync. It does not authenticate to a provider.

`WordSyncTranspilers.yrc` accepts `[lineStart,lineDuration](fragmentStart,fragmentDuration,0)text`. All YRC times are absolute milliseconds. JSON metadata lines are skipped. Neither source encodes reliable vocal roles in these basic structures, so converters default to lead rather than inventing singers.

Import `.json` and `.yrc` through the existing lyric import flow. Raw RichSync arrays can be imported as `.json` too. Documents persist in the database and atomic offline lyric cache alongside legacy LRC fallback fields. Existing LRC/TTML remains supported. The player's viewer uses true fragment ends, joins syllables without artificial spaces, styles background vocals smaller and italic, aligns duet lines to the right, and preserves overlapping highlights. `LyricsDocViewer` also exposes the same Compose engine as a standalone component, using Material 3 theme typography/colors and centered scrolling.

The format supports vocal roles without assuming every source supplies them. TTML remains supported via the existing converter; a lossless TTML-to-JSON role converter is not part of this change.
