---
id: F14
title: "Design system for the macOS app: `DESIGN.md` and tokens"
status: done
created_at: 1792583218
updated_at: 1792583218
---

The creator asked for a new, polished design with tokens (2026-10-01), following the F12 glass requirement: frosted or glossy glass with a 3D effect, macOS 26 Liquid Glass.

Plan:
1. Research the `DESIGN.md` format (a design spec agents read: tokens in front matter plus prose) with primary sources; follow it if it is a maintained spec, else a documented equivalent.
2. `desktop/macos/DESIGN.md`: principles, glass and depth rules, colour (light and dark, accent, semantic status colours for installed / update / error / busy), typography (SF Pro scale), spacing, radii, elevation levels (shadow and highlight per layer for the 3D effect), motion, icons (SF Symbols), components (sidebar, package row, card, progress, sheet, menu-bar extra, buttons), accessibility fallbacks (Reduce Transparency, Increase Contrast, Reduce Motion).
3. Tokens as one source: `desktop/macos/design/tokens.json` in the W3C Design Tokens format, generated into Swift (`Tokens.swift`, header says it is generated) by a maintained generator (e.g. Style Dictionary), run by a `just` recipe, with a drift check.
4. `desktop/macos/design/preview.html`: a static page rendering the tokens and key components in light and dark, for review.
5. Hand-off to F12: the app uses the generated tokens instead of literals.

Check: the generator reproduces `Tokens.swift` byte-for-byte; the preview renders both themes; text colours meet WCAG AA contrast on their glass backgrounds (checked by the script or documented per pair).

Status: done. PR https://github.com/pyrlyn/ketch/pull/188 merged 2026-10-01. Google Labs DESIGN.md spec (alpha, `@google/design.md` 0.4.0) with generated front matter; tokens in W3C DTCG 2025.10; Style Dictionary 5.5.5 with custom formats for Swift (four appearances), CSS and the front matter; `just design-check` covers drift, WCAG AA and lint. Research in `docs/research-design-system.md`. `Theme.swift` reads `Tokens.swift` since F16. An Accessibility Inspector pass on the real Liquid Glass material is a manual check on a Mac and was not run here. See `done.md`.
