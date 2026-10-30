---
status: in progress
priority: P1
complexity: 5
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T16. The macOS look of the desktop app

The Compose app wears Material 3 everywhere. On macOS it should wear the design in the
`ketch Store — Discover` canvas (https://claude.ai/artifact/5kcXhSbs6P2Mno64pkcxUd): Liquid Glass
over a wallpaper, 3D tiles and app icons, a hero on Discover, sheets inside the window. Windows
and Linux keep Material 3. Done means all eight artboards are in the app on macOS, driven by the
store's real data, and `./gradlew build` is green.

## Execution plan

1. `Look` (Glass on macOS, Material elsewhere) in `ui/Theme.kt`; the macOS tokens in `ui/Tokens.kt`.
2. The `kotlin-glass` package in `packages/` (on the creator's word, during the task): surfaces,
   controls, sheet and layout, without business logic. `ui/glass/`: the palette from the design's
   `bundle.css`, the shell, one file per screen. The Material tree stays as it is; shared logic
   stays shared.
3. `Navigation` gets history (Back and Forward), Sources and the search shortcut.
4. `Prefs` gets the Appearance choices under the macOS app's keys.
5. `Main.kt`: full-size content under the real traffic lights.
6. `GlassAppTest` and rendered PNGs of each screen against the artboards; `./gradlew build`.

## Decisions

- The window draws the wallpaper itself: the real traffic lights stay, and an opaque window keeps
  the native title bar and shadow, which a transparent undecorated one loses.
- Nothing is invented: what the store has no data for is left out, see `done.md`.
