---
id: F18
title: Figma design for macOS, Windows and Linux
status: in-progress
created_at: 1792583218
updated_at: 1792583218
---

The creator asked (2026-10-01) to update the Figma file "ketch for macOS — Liquid glass"
(`v7OJLmQEyCFbJ63uSYpJ9g`) for the new requirements: the app is coming to Windows and Linux too
(R10, R11), the macOS app as built in F17 differs from the first mock-ups, and the core now
exposes decisions the screens do not show yet (binary choice, stopping running processes, the
ambiguous `bin` glob, the busy lock, pin and rollback).

Done when the file has a shared layer (common screen specs, shared semantic tokens, truly
cross-platform components) and per-platform pages; the macOS screens match the F17 app; the new
feature screens exist for macOS in light and dark; Windows 11 (WinUI 3 / Fluent) and Linux
(GTK 4 + libadwaita, or what R11 recommends) variants exist for Installed, Discover, Updates,
Package detail, Settings, the binary-choice dialog and the tray or its equivalent, in light and
dark; prototype links cover the new frames; and the Figma mapping doc names the pages,
collections, modes and the code syntax of every variable.

Execution plan:
1. Read the F17 SwiftUI views, `tokens.json`, R10 and R11 (if pushed), the `ketch-ffi` surface
   and the F17 screenshots.
2. Figma, shared layer: a `Platform` variable collection (modes macOS / Windows / Linux) for
   material, radius, spacing and type-size differences beside `Color` (Light / Dark), after
   checking the plan's mode limit; a Shared page with wireframe-level screen specs and the
   cross-platform components; per-platform pages.
3. Sync the macOS screens with F17 (bottom progress bar, Discover hero and shelf, Doctor fix
   text, monogram icons, native toolbar controls, busy banner, Settings tabs, orange update
   badges, smoke-tinted terminal).
4. New macOS screens, light and dark: binary choice, stop running processes, ambiguous `bin`
   glob, busy lock, activity detail, pin / unpin and rollback, update notifications; anything
   the core does not expose yet is marked "needs core" in the frame description.
5. Windows and Linux variants in each platform's idiom, light and dark, on the shared tokens.
6. Prototype links; screenshot-verify each step.
7. Update the Figma mapping doc (`desktop/macos/design/figma.md`, or a platform-neutral place
   if the structure warrants it), open a PR, wait for CI.

Status: the Figma work is done — shared pages, macOS synced with F17 plus ten new frames,
Windows (Fluent) and Linux (libadwaita, per R11) pages in light and dark, prototype flows on
every page. `figma.md` lives in `desktop/design/`. Left: the creator's
review of the file and the merge of the PR.
