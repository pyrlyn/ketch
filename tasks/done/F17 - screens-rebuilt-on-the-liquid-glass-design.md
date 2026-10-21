---
id: F17
title: Screens rebuilt on the Liquid glass design
status: done
created_at: 1792583854
updated_at: 1792583854
---

The SwiftUI views follow F15's screens: the glass sidebar with counts, an Updates screen, Discover as shelves of app cards, the detail page, Activity, Doctor, the binary-choice sheet and the menu-bar extra. Starts after F15's screens and F16's tokens land.

Plan (stacked on F16's branch `f16-liquid-glass-tokens` until #197 merges):
1. Read the Figma screens (`get_design_context` / `get_screenshot` per frame; node ids and the variable mapping in `desktop/macos/design/figma.md`); colours, radii and spacing come only from `Tokens.swift` through `Theme.swift`, never literals.
2. `ContentView.swift`: glass sidebar with counts (Installed, Discover, Updates, Activity, Doctor) and the root and package summary at its foot; `KetchStore` gains an update count if it lacks one.
3. New `UpdatesView.swift`: available updates with per-row Update and Update all, the busy banner (`Error::Busy`) with Retry, pinned packages held by `ketch.lock`.
4. `InstalledView`, `DiscoverView` (hero plus shelves of app cards), `PackageDetailView`, `ActivityView`, `DoctorView`, the uninstall sheet and `MenuBarContent` restyled to the frames; badges use the status pairs, an update badge `Status.update`.
5. Reduce Transparency and Increase Contrast keep working (the `Glass/Reduced` style, `highContrast` tokens).
6. Tests: Swift Testing for new store logic (update count, busy state); the UI test navigates every sidebar item.

Check: `xcodegen` + `xcodebuild test`, `just design-check`, screenshots of each screen in light and dark compared with the Figma frames.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/200. All nine screens in light and dark. Differs from Figma where the core has no data: no package sizes or changelog headlines, Discover without featured picks or categories, Doctor names each fix as text with no Fix buttons, monogram icons. Not verified: the real menu-bar popover's glass, and screenshots under Reduce Transparency and Increase Contrast.
