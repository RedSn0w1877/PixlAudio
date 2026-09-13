# Beta 2 verification checklist

Work through top to bottom. Each item says **where to tap** and **what should happen**.

Legend:
- ✅ = already confirmed working on your Pixel 10 Pro (Sep 10)
- 🔍 = needs your eyes
- ⚠️ = known limitation, not a bug

---

## A. Claude's changes — Sep 10 (offline UX, explanations, storage)

### A1. Offline card in the song menu ✅
Library → Songs → **⋮** on any streaming song → scroll to bottom.
- [ ] An **Offline** card appears below "Remaster Song"
- [ ] Says "Keep this song on your phone so it plays without a connection." + **Download**
- [ ] Local (non-streaming) songs show **no** Offline card — correct, they're already offline

### A2. Download → state flip ✅
Tap **Download** in that card.
- [ ] Icon becomes a check, text becomes "Saved on your phone. It plays with no connection."
- [ ] Button becomes **Remove download**
- [ ] Tap **Remove download** → reverts to "Download"

### A3. Row badge ✅
Close the sheet, look at that song's row in the list.
- [ ] Small check icon appears left of the **⋮**, only on that song
- [ ] Doesn't collide with the ⋮ button
- [ ] Removing the download makes it disappear

### A4. Download progress 🔍 — *not yet seen*
Needs a track that is **not** already cached (one you've never played). Tap Download and watch.
- [ ] Card shows a progress bar and "Saving this song to your phone — NN%"
- [ ] Percentage only moves forward, never backwards, ends at 100%
- [ ] ⚠️ A song you've already played completes instantly with no bar — it's promoted from
      the existing cache instead of re-downloaded. That's correct, not a missing feature.

### A5. Failed download 🔍 — *hard to trigger on purpose*
Best trigger: turn on airplane mode, tap Download, wait for it to give up (4 attempts).
- [ ] Row badge turns into a **red error icon**
- [ ] Offline card shows the real failure text and a **Try again** button
- [ ] Tap **Try again** with connection restored → recovers
- [ ] **This is the headline bug fix.** Before: it silently reported success, did nothing,
      and left no way to retry.

### A6. Unplayable track badge 🔍
Find a Spotify track that won't play (previously: "Feel It", "NIGHTS LIKE THIS").
- [ ] Shows a **red cloud-off icon** in the row *before* you tap it
- [ ] ⚠️ Only for tracks the matcher gave up on (UNMATCHED). Tracks still being matched
      (PENDING) deliberately show nothing — right after an import everything is pending and
      badging it all would be noise.

### A7. Recommendation reasons ✅ (fixed after first look)
Home → scroll to the song shelves.
- [ ] A short line under the artist: "One of your favorites", "You often finish this song",
      "Discover more from an artist you enjoy"
- [ ] 🔍 **Check this specifically:** text should no longer cut off mid-word. First build
      truncated to "Discover more from an a…"; now two lines. Confirm longer reasons fit,
      e.g. "Fits the length of your usual listening sessions" (Plus/Muselle 2 only).
- [ ] Card heights stay even across a row

### A8. Home shelves not starved ✅ — *most important item*
Home → scroll through all shelves.
- [ ] **Always a good choice** (favorites) has songs
- [ ] **Back in rotation** has songs
- [ ] **Waiting to be heard** has songs
- [ ] **Recently added** only contains genuinely recent additions — not your whole library
- [ ] **On repeat** contains things you actually replay *recently*
- [ ] **Artist radio** is an artist with several songs, not a one-song artist
- [ ] These would have been **empty or missing** before the fix. If any look wrong on your
      490-song library, tell me — my fix was tuned against a 3-song test fixture.

### A9. Storage controls ✅
Settings → Music Management → scroll to **Offline storage**.
- [ ] Shows size + song count for downloads, and temp-copy size separately
- [ ] **Free up NN MB** matches only the *temporary* number, never your downloads
- [ ] Tap it → toast "Freed NN MB. Your downloads were kept."
- [ ] 🔍 Confirm your downloaded songs still play offline afterwards
- [ ] Button reads "Nothing to free" and is disabled when temp cache is empty

### A10. Background processing status ✅
Settings → AI features (or wherever the "Ready when you play" card lives).
- [ ] Live status line visible **without** expanding "Queue and diagnostics"
- [ ] Says something real ("Waiting until playback is idle", "Automatic processing is off", etc.)

---

## B. Codex's changes — Sep 7–9

### B1. Plus tier 🔍
Settings → **PixelPlayer Plus**.
- [ ] Screen opens: gradient hero, seven feature cards, custom amount picker
- [ ] Tapping the support CTA opens a **"not charging yet" setup dialog** — ⚠️ correct and
      intentional. There is no checkout URL, backend, or payment verifier. It must stay that
      way until the hosted side exists.

### B2. Plus debug license 🔍
Settings → Experimental → **Plus license debug tools** → password **`1501`**.
- [ ] Can create, activate, and revoke a local test key
- [ ] With Plus active, Home recommendations switch to **Muselle 2** (reasons like "Fits the
      length of your usual listening sessions", "Balanced into the queue for more artist variety")
- [ ] ⚠️ These keys are test fixtures, not proof of payment

### B3. Muselle recommendation tiers 🔍
- [ ] Free tier works with no network and no model download
- [ ] Plus tier visibly reranks (novelty, session length, era, artist/genre variety)

### B4. New Home shelves 🔍
- [ ] **Quick listens** (short tracks), **Settle in** (long tracks) appear and contain
      appropriately-lengthed songs
- [ ] Active/playing card animates to a raised tonal container

### B5. Lyrics — catalog-first resync 🔍 *(biggest Codex area, least verified)*
Per Codex's own checklist:
- [ ] **Notion — The Rare Occasions**: ⋮ → Sync/resync lyrics → expect **word-by-word**
      highlighting; debug info should name **NetEase**
- [ ] **Like That — Doja Cat**: expect word sync, correct spacing, no missing words; compare
      timing against the vocals including intro ad-libs
- [ ] **A COLD PLAY — The Kid LAROI**: line-synced only is expected; first lyric ≈ **13.44s**
- [ ] Resync a word-synced song, force-close the app, reopen → timing and full text survive
- [ ] Lyrics readable in **both light and dark** themes; seeking moves the highlight correctly

### B6. Offline / downloads retention 🔍
- [ ] With a downloaded song + existing instrumental, enable airplane mode → both still play
- [ ] Re-check after a day or two — the original complaint was downloads *disappearing*, which
      an immediate test can't disprove

### B7. Playlists 🔍
- [ ] Reorder a playlist, close/reopen the app → order persists
- [ ] **Sync entire playlist** → finishes, or reports individual failures rather than dying silently

### B8. Streaming playback 🔍
- [ ] Play a YouTube Music track and a regular YouTube video track; seek and skip
- [ ] Note stalls, errors, unusually slow starts

### B9. NPU ⚠️ — do not test, nothing to see
- LiteRT 2.2.0 runtime and the Google Tensor dispatch library are bundled, but **no
  AOT-compiled Tensor G5 model exists** (the vendor compiler download 404s). Stem separation
  runs on CPU/XNNPACK. Any claim of TPU acceleration would be false.

---

## C. Automated verification already done (no action needed)

- ✅ Full unit suite: **584 tests / 103 suites, 0 failures**
- ✅ Plus suite 12/12 — confirms Codex's interrupted `java.net.URI` fix works
- ✅ Cache suite 9/9, including 3 new tests for download progress against a real socket server
- ✅ Installed APK hash byte-matches the tested build

## D. If a build fails

It's **not** Android Studio. `app/build` acquires ReadOnly attributes:

```powershell
attrib -R "app\build\*" /S /D
```

It recurs — re-run whenever a build dies on "Unable to delete directory".
