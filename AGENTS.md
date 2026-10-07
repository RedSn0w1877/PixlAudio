# Agent instructions

New to this repo? Read **[`handoff/2026-10-04-START-HERE.md`](handoff/2026-10-04-START-HERE.md)**
before doing anything. It covers the project, the user, the two unrelated git histories (pick the
right branch), build/test commands, device-testing rules, the architecture map, conventions and
open threads.

The short version:

- Android music player, Kotlin + Jetpack Compose. The phone app is `:app`, plus `:wear`,
  `:shared` and `:baselineprofile`.
- `origin/main` and the `main-*` cloud branches don't share history. Ask which branch is
  canonical before real work (likely `origin/android-int-oct3`).
- Many environments can't build this (no Android SDK). Check `$ANDROID_HOME` and say so if you
  can't compile.
- Ask before installing on, or driving, the user's phone.
- Add player state to a `*StateHolder`, not `PlayerViewModel`. Never collect the whole
  `playerUiState` in a screen; slice it.
- End your work with a dated note in `handoff/`.
