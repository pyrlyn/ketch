---
status: in progress
priority: P1
complexity: 5
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T17. The Windows look of the desktop app

The Compose app wears Material 3 on Windows. It should wear the `ketch Store — Windows 11` canvas
(https://claude.ai/artifact/WY66hSqys76vopeokUvMVK) in the colours of the `Pyrlyn Windows 11` design
system (https://claude.ai/artifact/D19rNmvXEtyDGCtrLJchu6): the window material over a wallpaper, a
title bar of its own, a navigation view, a layered content area, cards with a lit edge and restrained
3D. Reusable components go into a package of their own and hold no business logic. Done means every
artboard is in the app on Windows, driven by the store's real data, and `./gradlew build` is green.

## Execution plan

1. `packages/kotlin-fluent` (module `:fluent`, package `pyrlyn.fluent`), on the `kotlin-fluent` branch
   of pyrlyn/packages: the design system's palette, surfaces, controls, layout and dialog. It reuses
   `Glyph` and `AutoGrid` from kotlin-glass; the new glyphs go there.
2. `Look.Fluent` on Windows in `ui/Theme.kt`, `KETCH_LOOK` to see it elsewhere. High contrast keeps
   Material 3, which the design system has no answer for. Linux keeps Material 3.
3. `ui/fluent/`: the palette mapping, the shell (title bar, navigation, bars), one file per screen,
   the dialogs. Logic the macOS look already has is moved to where both can use it, not copied.
4. `Main.kt`: an undecorated window, dragged by the title bar, that keeps clear of the taskbar when
   maximized. Caption buttons reach the window through a composition local.
5. `Prefs` gets the window material. Tint, accent and wash are the ones the macOS look stores.
6. `FluentAppTest`, the package's control tests, rendered PNGs of each screen against the artboards,
   `./gradlew build` here and `./gradlew check` in the package.

## Decisions

- **compose-fluent-ui is not used.** Its last release (v0.1.0, August 2025) is built on Compose 1.8.2
  and its controls carry Microsoft's stock Fluent tokens, while this design system has its own
  glass surfaces, 3D and palette. Most of it would be overridden, so the components follow T16's
  foundation-based approach instead.
- **The window draws its own wallpaper,** as T16 decided: Compose cannot blur what lies behind a
  surface, and the material is a translucent fill over the wall.
