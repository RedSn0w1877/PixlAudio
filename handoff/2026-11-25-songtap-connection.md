# Song Tap: iPhone sender never reached the Pixel (2026-11-25)

Android only, branch `everything`. Full write-up: "Follow-up 2" in `handoff/2026-11-21-song-drop.md`.

- `BleTransport`: "tap ignored: no yes yet" (once per 2 s) plus `tapNote` / `needsYes` / `radioStarts` in the diagnostics snapshot; the pin follows a same-named phone that changed address (`followSamePhoneLocked`); `anotherReceiverNear` skips same-name twins; sticky prompt across address changes; "No" silences the name; `start(sameRole)` is a no-op while running; 3 s connect timeout.
- `AndroidBleRadio`: Timber lines for advertising start requested / started / stop.
- `SongTapStateHolder.arm`: returns at once when already armed as that role.
- Diagnostics screen (Send): "Say yes first" block with the nearby list, the shared `SongDropAsk` dialog, the armed card and an "Open Song Tap" button.
- Tests: `BleTransportTest` (follow-up 2 section), `TwoPhonesTest` (arm twice does not restart the radio).
- Wire protocol, `SPEC_VERSION`, goldens, TX power and detector numbers unchanged. iOS port pending. Not tested on a phone.
