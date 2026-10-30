---
status: in progress
priority: P1
complexity: 4
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T20. Give Linux the Ketch Linux look, from a shared package

On Linux the app still shows plain Material 3 while macOS has glass and Windows has Fluent. The creator's design "Ketch
Linux" is a dark glass window with GNOME's shapes: a header bar with a view switcher, boxed lists, pill buttons, a
popover menu, a toast and round window controls. Its generic part goes into a reusable package, as kotlin-glass and
kotlin-fluent did for the other looks.

Done means: a Linux window opens in this look by default, every screen of the design shows real data, every control does
what it says, and what is generic lives in `packages/kotlin-adwaita` with no ketch word in it.

## Plan

1. `packages/kotlin-adwaita` (`pyrlyn.adwaita:adwaita`): theme, wall, header bar, view switcher, boxed lists, pills,
   chips, linked toggle, switch, entry, status pills, terminal, popover menu, toast, banner, alert dialog; tests.
2. `desktop/src/main/kotlin/ketch/desktop/ui/adwaita/`: the shell and the screens, on the store.
3. `Look.Adwaita`: `lookFor` picks it on Linux, high contrast keeps Material; `KetchApp`, `Dialogs.kt`, `About.kt`, `Main.kt`
   (a window of its own with resize edges, Ctrl keys), `settings.gradle.kts`, `desktop/build.gradle.kts`.
4. Tests on the fake core, docs: `AGENTS.md`, `README.md`, `toolchain.md`, `plan/README.md`, `desktop/design/README.md`.

Check: `./gradlew build`, `./gradlew check` in the package, `KETCH_LOOK=adwaita ./gradlew :desktop:run` on the Mac, and a run
on the Ubuntu 26.04 ARM64 VM.
