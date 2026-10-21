---
id: D20
title: "Windows: XAML `HighContrast` follows the user's contrast theme"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Requested by the creator (2026-10-03). `KetchTokens.xaml`'s `HighContrast` dictionary carries the tokens' `highContrast` hex values, which are macOS Increase Contrast ink tuned for a light background (AccentInk `#003A75`, StatusInstalled `#0B5A31`). Windows applies that one dictionary under all four contrast themes (Aquatic, Desert, Dusk, Night sky), three of them dark, so text becomes unreadable, and an app in a contrast theme is expected to use the user's palette rather than brand colours.

Execution plan: (1) `desktop/design/build.mjs` writes the XAML `HighContrast` dictionary as references to WinUI's `SystemColor*` resources, chosen from Microsoft's contrast-themes pairings (https://learn.microsoft.com/en-us/windows/apps/design/accessibility/high-contrast-themes, checked 2026-10-03): accent fills to Highlight, text on accent to HighlightText, text, status glyphs and focus ring to WindowText, surfaces to Window; brushes use `{ThemeResource SystemColor...Color}` as the page shows. (2) Light, Dark and the GTK output stay as they are. (3) `contrast.mjs` skips these pairs, with a comment. (4) Regenerate, then `just design-check` and `xmllint` on the XAML. (5) The generated header, `DESIGN.md`, `desktop/design/README.md` and `desktop/contract/README.md` state the rule.

Done when the generated `HighContrast` dictionary holds no hex values, `just design-check` passes with its drift check covering the output, and the docs say so.

Status: done.
