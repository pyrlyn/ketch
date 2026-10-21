---
id: D5
title: Design tokens for XAML and GTK
status: done
created_at: 1792583854
updated_at: 1792583854
---

`tokens.json` feeds only Swift today. The Windows and Linux apps should share ketch's brand (accent, status colours, spacing, radii, type scale) without imitating the glass. Style Dictionary has no XAML or GTK format, so it takes two custom ones. Research: section 2.

Done when the token source lives in `desktop/design/`, `just design-tokens` also writes a XAML `ResourceDictionary` (Light, Dark, HighContrast theme dictionaries) and a GTK stylesheet setting libadwaita's CSS variables, glass and elevation tokens stay macOS-only, generated files say so in their first lines, and a drift check covers all outputs. Brand tokens on every platform, with native surfaces, were decided by the creator (2026-10-01, open decision 6).

Execution plan:

1. `git mv desktop/macos/design desktop/design`; fix every path (Justfile, `ci.yml`, `project.yml`, READMEs, DESIGN.md links, AGENTS.md, toolchain.md).
2. `build.mjs`: a `BRAND` path list and two Style Dictionary formats, `ketch/xaml` (`ThemeDictionaries` Light, Dark, HighContrast; plain resources for spacing, radii, type scale) and `ketch/gtk` (libadwaita variables plus `--ketch-*` custom properties, dark and high contrast as media queries), both written to `desktop/design/generated/`.
3. `just design-check` and the CI `design` job compare all five generated files, including one that is missing.
4. Verify: `just design-check`, `xmllint` on the XAML, a deliberate edit of a generated file fails the check, `just macos-test` still builds against the moved `Tokens.swift`.

Status: done. The token source moved to `desktop/design/`; `just design-tokens` now also writes `generated/KetchTokens.xaml` and `generated/ketch-tokens.css`. Brand tokens only (the `BRAND` list in `build.mjs`: accent, status colours, spacing, radii, type scale). Windows has no dark high-contrast theme, so `HighContrast` takes the `highContrast` values. The GTK file uses `@media (prefers-color-scheme)` and `(prefers-contrast)`, which GTK documents in css-properties.md (checked 2026-10-03) and AdwApplication autoloads from libadwaita 1.8. Not run: the XAML was checked for well-formedness only (no WinUI toolchain here), and the GTK CSS was not loaded in a GTK app.
