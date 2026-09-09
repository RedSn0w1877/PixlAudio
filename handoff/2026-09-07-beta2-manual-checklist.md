# Beta 2 manual check — word-sync build

Installed on the Pixel 10 Pro: 0.7.6-beta2, build SHA256
`d07963f04cfa0e792f418e43f5e36b8e16af2f0bc6c85a6aaeecfa43f9a3ed0b`.
522 computer tests passed. APK signature, 16 KiB ZIP alignment, and unchanged original
stem model verified. Android test APK builds, but its four tests were NOT run: user
requested installation and manual phone testing instead. No new NPU execution or model
training is claimed by this lyrics update.

- [ ] Open **Notion — The Rare Occasions**, then **Lyrics → ⋮ → Sync / resync lyrics**.
      Wait for completion. Expect word-by-word highlighting. **Lyrics debug info** should
      identify NetEase and report word-synced lines. Existing cached line lyrics require
      this resync to upgrade.
- [ ] Repeat for **Like That (feat. Gucci Mane) — Doja Cat, Gucci Mane**. Expect word sync,
      correct spacing, and no missing words. Compare timing to the vocals, including intro ad-libs.
- [ ] Resync **A COLD PLAY — The Kid LAROI**. The available fallback is line-synced;
      the first lyric should appear around **13.44 seconds**, with the complete song text.
      Line-only is expected for this recording in the currently checked catalogs.
- [ ] Resync one of the word-synced songs again, then fully close/reopen the app.
      Timing and the full lyric text should remain; the button should still work.
- [ ] Check lyrics in light and dark themes. Active words should stay readable; seeking
      forward/backward should move the highlighted line correctly.
- [ ] With a downloaded song and an existing instrumental, enable airplane mode and
      play both. Restore airplane mode afterward. Repeat after a day or two to check the
      original disappearance report; an immediate test cannot prove long-term retention.
- [ ] Reorder a playlist, close/reopen the app, and check the order. Run **Sync entire
      playlist** and check that it finishes or identifies individual failures instead of quitting silently.
- [ ] Play a YouTube Music track and a regular YouTube video track; seek and skip between
      tracks. Note stalls, errors, and unusually slow starts.

For any failure, report: song + artist, action taken, expected/actual behavior, approximate
playback timestamp, whether Wi-Fi was on, and the text under **Lyrics debug info**.
