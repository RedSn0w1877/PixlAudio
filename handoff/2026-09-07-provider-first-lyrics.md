## FINAL STATE — installed, user taking over phone testing

User's latest instruction: busy; install build, send checklist, they will test manually.
STOP automated phone testing. Installation remains authorized and is COMPLETE.

Build19377 succeeded in5m21s:522 tests/92 suites, no failures/errors/skips.
Log build/lyrics-catalog-regression-retry.log. App and Android test APKs build successfully.
The earlier Android compile failure was an obsolete SyncWorkerTest constructor; removed
three no-longer-existent mock arguments. No production worker change was necessary.
Four LyricsFormatDeviceTest checks compile but were NOT RUN. Test APK was NOT installed.

Final APK build/verified-apks/pixelplay-beta2-word-sync-fix-arm64.apk
SHA256 d07963f04cfa0e792f418e43f5e36b8e16af2f0bc6c85a6aaeecfa43f9a3ed0b,
643186929bytes, version0.7.6-beta2/code11. Signature/16KiB ZIP alignment/model hash checked;
DEX contains featuredCredit/matchesArtists/recordingTitle. APK hash rechecked after build
completion. ADB install-r to59240DLCH004G6 returned Success (session12570).
Proof build/lyrics-catalog-final-verification.json installedtrue.

Manual checklist: handoff/2026-09-07-beta2-manual-checklist.md.
Phone was paused on Notion before installation. No app launch, phone UI tests, playback,
connectivity or settings changes after user's takeover instruction. No background build
or install remains. Existing stay-awake value7 untouched.

This build includes JSON lyric docs/viewer, catalog-first explicit resync AND normal online
loading, LRCLIB lyricsfile support, AMLL and NetEase adapters, CJK wrapping/theme contrast,
and confirmed featured-credit/zero-duration-punctuation fixes. NetEase live desktop probes
found Notion260 raw fragments/253 positive; LikeThat526 raw/507 positive. Phone end-to-end
word-sync verification is still pending the user. ACold prior core resync IS verified on
phone:40 nonblank lines/387 words, first13.44sec, line-only. Musixmatch converter exists,
but no working free Musixmatch provider is claimed; SyncLRC403 not enabled. No new weights
trained and NPU hardware execution remains unverified from earlier work.

## Latest continuation: confirmed catalog bugs (September 7, evening)

User reported line-only results on several songs. Live checks found NetEase YRC for
Notion (1899851212, 260 raw fragments) and Like That (1401435094, 526 raw fragments).
Two concrete integration bugs fixed: featured-title/joined-artist metadata differences;
and zero-duration punctuation/censor fragments causing strict LyricsDoc validation to
reject the entire recording. Same-onset punctuation is folded into its positive-duration
fragment with identical text/onsets; no missing vocal timing is invented. Three JVM
regression tests and two sanitized live-structure fixtures added. Album/duration/version
checks remain. Main edits were complete before starting build29650.

Prior final build12465 succeeded: 519 tests, 92 suites, zero failures/errors/skips,
log build/lyrics-doc-final-validation.log. Not installed because newly reported catalog
bugs required the above fixes. Active build29650: testDebugUnitTest + assembleDebug +
assembleDebugAndroidTest, log build/lyrics-catalog-regression-validation.log, main/test
KSP tasks excluded because signatures/annotations unchanged. Android test source has four
tests: YAML compatibility, JSON/YRC round trip, live Notion/Like That fetch, live LRCLIB
36497565 word data. Android additions completed before Android test compilation.

Phone remains on installed core SHA206459c... (518-test APK). Paused Notion via UI to
hold a stable test target. Latest Lyrics debug dialog accurately says 0 word-synced
lines out of 30. After new build passes, install-r, install test APK, run ONLY
LyricsFormatDeviceTest, then resync Notion in UI and verify debug info/cache/persistence.
No connectivity, volume, or screen timeout settings changed. Stay-awake remains7.

Read-only phone snapshot build/phone-qa/catalog-fix-before includes app DB and WorkDB.
A COLD PLAY core resync 92e3ce32-9ee7-4147-85a1-66717a533657 SUCCEEDED: line-synced LRCLIB;
saved first lyric13.44sec, 40 nonblank lines/387 words; no word tags. This verifies the
core catalog-first resync on phone. User selected other songs during earlier checks;
only touch observed foreground PixelPlay UI and cross-check the selected track.

## Current execution state (latest)

Core build74750 succeeded in17m4s:518tests/92suites, zero failures/errors/skips.
Proof build/lyrics-doc-pre-final-verification.json. ALL new parser/NetEase tests passed,
including7Lyricsfile,10WordSyncTranspilers,2NetEase. Fixed YAML resolver is verified on JVM.
CoreAPK preserved build/verified-apks/pixelplay-beta2-lyrics-core-arm64.apk,
SHA256206459c66f31fbcbc8c856b1070a1595105839861c12760d4ec9525556e64a38,
636948713bytes; signature,16KiBZIPalignment,originalstemmodelhash verified; DEX inspected
for LyricsDocCodec/WordSyncTranspilers/LyricsfileParser/NeteaseLyricsSource/newworkerstrings.
INSTALL-R SUCCEEDED (session89701) on Pixel. build/lyrics-core-apk-verification.json installedtrue.
User has been told clearly the catalog-first core build is NOW INSTALLED; finalpolishfollows.

FINAL computer run active: exec12465, build/lyrics-doc-final-validation.log.
Command testDebugUnitTest+assembleDebug with -x app:kspDebugKotlin and -x app:kspDebugUnitTestKotlin,
reusing the successful generated code because only implementation bodies/UI helper/testmethod
changed (no DI/Room/model signatures/annotations). Includesfinalcontrast/CJKwrapping/sharednormal
cataloglookup. Expect519tests if fullrun; check actualXML. No moremaincodeeditsafterstartingthisrun.

Added app/src/androidTest/.../LyricsFormatDeviceTest.kt (two pure parser tests, no userdatasideeffects).
After finalcomputerbuild, assembleDebugAndroidTest and run ONLY this class via instrumentation,
not the unrelated existing database/worker instrumented tests. Verifytargettestpackage; adbinstall-t.
This is to catch Android-specific SnakeYAML/Kotlinx compatibility issues. Then finalAPKsign/hash/
16KiBcheck/install-r/phoneUIverification. No finalAPKcreatedyet.

PHONE COORDINATION PENDING: async question asks user to leave A COLD PLAY open and untouched.
Their selected song/screens keep changing between tools (Notion/LikeThat/etc.), so stop UI input
until readiness reply; priorfullautomationpermissionstillapplies. Lastsuccessfulstartmainhomeshows
471songs/10playlists; unique AColdrowtapped but userswitchedsongsduringpost-snapshot. No core-resync
phoneproof yet. CoreappforegroundlyricsscreenLikeThat at lastshot;do not mistake thisforACold.
AColdrecoveryalreadyverifiedinBOTHphonecacheandDB:40lines/387words/13.44sec, source remote.

Final pass also shares catalog selection with normal lyric loading (not only resync).
Private findCatalogLyrics(syncedOnly) supports plain lyrics as last fallback for normal
loads; resync still requires actual timed content. KSP DI/Room signatures unchanged.
Normal fetchLyricsFromAPI(includeAmll=true) checks disk then delegates to shared catalogs;
that helper calls the LRCLIB-only path with includeAmll=false, so no recursion.

Final UI pass also fixes CJK wrapping: Latin syllables stay joined, CJK fragments may wrap,
and the FlowRow adds spacing only at actual word boundaries. Added one logic regression.
These edits occurred after build74750 main/test compile: rerun compilation/tests/package.
No DI/Room/model signatures or annotations changed in this final UI pass.

IMPORTANT: After build74750 finishes, rerun :app:testDebugUnitTest :app:assembleDebug.
A final UI-only color fix was made after compile started: LyricLineRow now uses the
contrast-checked accentColor in animated mode as well; old hardcoded white was unreadable
on light themes. Current74750 APK cannot be assumed to include that final fix. All prior
source changes were made before its compile. Do not install until final rerun validates it.

# Latest update: lyrics JSON + provider validation

Active build: `build/lyrics-doc-validation-retry.log`, exec session74750, started around20:13.
Prior run `provider-first-validation.log`:505tests,3failures in LyricsfileParser. Cause was
Kotlin non-null `Resolver.resolve(value:String)` rejecting SnakeYAML's null mapping-node
value. Fixed to String?. Subsequent build failed compile on nullable delegated `lyrics`
access in footer; fixed to `lyrics?.document`. Stopped failed build47028, retry74750active.
Do not install until build/tests pass. No new APK installed this phase.

Phone currently connected/authorized. User is using app/other songs intermittently; guard
all touches. User ran another old resync (work06c20271-29bf-43be-865d-b5e961059a1f,SUCCEEDED).
They reported still bad timing, were told clearly new build is not installed; apologized,
reassured them. Latest phone recovery IS COMPLETE via Reset imported lyrics UI: cache now
40nonblanklines/387words/first13.44sec, exact text equals original recoverybackup.
Proof: build/phone-qa/catalog-recovery-verification.json and restored-catalog-cache.json.
Read-only DB+wal snapshot in restored-db. New worker/catalog-first behavior stillneedsphoneQA.
Playback was paused temporarily around1:36 during recovery; user subsequently changed song
and resumed playback themselves (I Want Things to Be Beautiful). No volume/connectivity
changes. Stay-awake7 unchanged/restored. Original full recovery LRC still on Downloads.

Latest user added a JSON-format prompt. Implemented (pending validation):
- Serializable LyricsDoc/Voice/TimedLine/TimedSyllable/LyricsMetadata, exclusive ends,
  source metadata, roles, overlapping activeLines/lead-focused findActiveLine/currentSyllable.
- Bounded JSON codec/version/voice/timestamp/text-integrity checks; RichSync and YRC converters.
- Real YRC fixture458fragments with lyric text replaced; synthetic RichSync/unit cases.
- Existing Lyrics/SyncedLine/SyncedWord extended with optional document/endTime/voiceRole.
- Native JSON/YRC import detection before old Kugou parser, picker/import security extended.
- Raw JSON retained in database + lyricsDocument field in atomic lyrics cache (legacyfieldskept).
- Existing M3 viewer uses true ends, overlapping roles, smaller italic background, rightduet,
  adjacent syllables joined viaRow. Removed fake synthesized timing for line-only lyrics.
- Fixed non-animated word-highlight clock; added public LyricsDocViewer wrapper.
- NetEase public search/get returns usable JSON; search/get/web has opaque regionalstring.
  New provider exacttitle/artist/album +1.5secduration, up to2candidateYRCfetches,10sectotal.
- Catalogs queried concurrently with per-provider deadlines; pick truewordtiming beforeline.
  Emptylyrics nowcheckscatalogbeforeSKIP; partialwordcoverage reportedaccurately.
- Shared bounded cancellable OkHttp callback reading; AMLL updated to use it.
- Debug UI no longer falsely claims LRCLIB never has words / is the only remote source.
  Generic Online lyrics footer avoids misattributing all providers to LRCLIB.
- New docs/lyrics-json-format.md; source reportupdated; SnakeYAMLthirdpartynoticeadded.

Latest provider probes: SyncLRC403 (notenabled). Musixmatch guesttoken200 withoutspoofing;
suppliedmacro pathwrongactualmessage.body.macro_calls. No richsync_body in inspectedmacro;
no verifiedworkingfreeMusixmatchcatalogintegration. RichSyncformatparser implementedonly.
NetEase1382781549 has458YRCfragments. AColdthreecandidatesnoneYRC. LRCLIBrecord36497565
190fragments/34wordlines. RichSyncdurations derivedfromnextoffset/lineend, notseparatelymeasured.
Do notclaimnewweightsorverifiedNPU. Existing originals/old handoff below forcontext.

---

# Provider-first lyric repair (active)

Actual repo: `C:\Users\Hoa Vo\Downloads\PixelPlayer-master\PixelPlayer-master`.
All tests/installation/phone automation authorized. Permissions now unrestricted; do not
pass sandbox_permissions. No Git metadata. Latest user wants good intro-aware timing,
large pre-synced word lyric catalogs and explicit verification LRCLIB really has words.

## Immediate state

Installed APK SHA256 `6ea6bc79d49657be92b16a9dd9a579e139cda3378d70cd26dbb648c84e120034`
on Pixel10Pro serial59240DLCH004G6; install-r succeeded, package update19:21:57 device time.
A COLD PLAY resync ran successfully, BUT verification found it retained only20/40nonblank
lines (189/387words), caused by old all-zero word timings being paired as translations.
User observed generated firstword0.2sec despiteintro. Do not claim this installed build
fixes lyric quality. Complete original preserved at build/phone-qa/resync-before includingDB.
Recovery LRCLIB line timings start13.44sec; they have full lyrics and correct duration.
Backup LRC was pushed to /sdcard/Download/PixelPlayer-restored-lyrics.lrc but NOT imported.
Original stay-awake7 restored and verified. No new audio/connectivity changes this turn.

## Current code/build

- Parser retains equal-time word-timed lines; three parser-to-resync regression cases.
- Whole-song CTC over overlapped16sec core+2seccontext audio windows, bounded64MiB backtrace.
- Whole-song real-audio computer test:387words, first0.6sec, mean evidence0.193; NOT accepted
  quality. New conservative confidence gate rejects this result. Do not promise new weights/NPU.
- New native AMLL public API exact Spotify lookup; strict title/artist/album fallback.
- LRCLIB DTO/format support for newer lyricsfile YAML; safe parser preserving No/On as text.
- Catalog-first resync bypasses stale JSON and persists verified online line/word lyrics;
  OUTPUT_LYRICS_UPDATED refreshes UI even if provider offers only line timing.
- Automatic LRCLIB duration tolerance tightened to2–3sec; variants still checked.
- Source research in docs/lyrics-source-research.md; live LRCLIB proof confirms190fragments,
  34wordtimed lines for Diffuse record36497565; legacyLRC haszero wordtags. Wordcoverageunknown.
- AMLL live3275entries, affectedtrackmissing. No verifiedfreeMusixmatchrichsync entitlement.
- Build currently running: build/provider-first-validation.log, exec session84822.
  Current sources NOT yet compiled/tested/installed. Finish checks and actual phone repair.

## Next

Check build for errors. Test live Lyricsfile parsing and provider-first behavior; ensure
full A COLD PLAY lines restored at13.44seconds and cache survives reload. InstallnewAPK
onlyaftertests/signaturechecks; recordSHA. Then resyncviaUIand verifydata+screen. Useruses
phoneintermittently; guardalltouches againstforegroundstateandavoidotherapps. Olderphone
QA stillpending playlistorder restoration, YouTube, instrumental/NPU runtime diagnostics.

Research agents finished read-only; noapp edits. No need morebroadsearch. No public plan
tool was available; phases tracked in the research report/handoff. No training performed.
