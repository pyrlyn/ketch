---
id: F15
title: Liquid glass design in Figma
status: done
created_at: 1792583218
updated_at: 1792583218
---

The creator chose variant 6, "Liquid glass", from the design explorations as the app's design (2026-10-01), and Figma as the design tool. The Figma file becomes the source the tokens and the screens follow.

Plan:
1. A Figma design file "ketch for macOS — Liquid glass" in the creator's team: a `Color` variable collection with Light and Dark modes (glass, glass-hi, glass-lo, glass-top, glass-sheet, ink, ink-2, hair, edge, rim-hi, rim-lo, drop, scrim, accent, accent-soft, accent-ink, on-accent, status colours), number variables for radius and spacing, text styles (Large title, Title, Headline, Body, Caption, Badge, Mono), effect styles for the glass elevations (drop shadow plus inner rim light and shade, background blur).
2. Components: window chrome, sidebar navigation, package row, app card, shelf, badge, button (primary, glass), segmented control, progress bar, sheet, menu-bar extra.
3. Screens on a Light page and a Dark page, 1180×760: Installed, Discover, Updates, Package detail, Activity, Doctor, Settings (with Appearance), binary-choice sheet, menu-bar extra; prototype links between them.
4. The file link and the variable-to-token mapping go into `desktop/macos/DESIGN.md` and `desktop/macos/design/README.md`.

Check: every colour, radius and text style on the screens is bound to a variable or style; screenshots of both pages reviewed; the mapping table lists every variable.

Done (Claude Code / opus-5.5): file https://www.figma.com/design/v7OJLmQEyCFbJ63uSYpJ9g (Ivan's Starter team). `Color` collection (32 variables, Light and Dark) and `Dimension` collection (13) synced to F16's `tokens.json`, each with its Swift name as iOS code syntax; 7 text styles; 5 effect styles built on Figma's Glass effect; components (icons, app icon, badge, button, nav item, search field, progress, segment, swatch, package row); nine screens on the Light page and their Dark-mode clones; prototype links from the sidebar, rows and Uninstall. Inter and JetBrains Mono stand in for SF Pro and SF Mono, which Figma's cloud renderer does not have. The variable-to-token mapping is `desktop/macos/design/figma.md`.

Status: done 2026-10-01
