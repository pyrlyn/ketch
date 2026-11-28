---
status: in progress
priority: P2
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T23. The macOS window buttons sit inside the Glass sidebar

The sidebar floats 10 dp from the window's edges with a 20 dp corner, and macOS draws the real window buttons where a plain
title bar has them, so the panel's corner runs straight under the red button. The creator chose to move the buttons and keep
the floating panel.

Done means: on macOS the real buttons sit clear inside the sidebar's rounded corner and still work, in a window and through
full screen, and every Glass control can be clicked. Other systems and Material on a Mac do not change.

## Plan

1. AppKit lays out a taller title bar only for a window with a toolbar, and JDK 21 has no property for that, so
   `desktop/src/main/kotlin/ketch/desktop/MacTitleBar.kt` gives the Glass window an empty toolbar through JNA, on the AppKit
   thread. A native failure is warned about and leaves the plain buttons.
2. Measure the unified and the compact style on the real window and take the one that clears the corner.
3. The title bar's strip takes every click, so the Glass pages start under it (`TITLE_BAR` in `ui/glass/Shell.kt`).
4. Check in the running app: position, close, minimize, zoom, full screen and back, dragging by the strip, the page controls.
5. `./gradlew build`; docs: `toolchain.md`, `plan/README.md`.

Check: a screenshot of the running window shows the buttons inside the corner, all three work, full screen leaves no empty
strip, and `./gradlew build` passes.
