---
id: F16
title: Tokens and Appearance settings for Liquid glass
status: done
created_at: 1792583854
updated_at: 1792583854
---

The app moves to F15's palette and gets Appearance settings. Liquid Glass stays the system material (`.glassEffect`), so what the user can change is what the material allows: tint, regular or clear glass, accent, and the strength of the backdrop wash (creator, 2026-10-01).

Plan:
1. `desktop/macos/design/tokens.json`: the Liquid glass palette for light, dark and their increased-contrast variants (values from F15's variables), regenerated with `just design-tokens`; `DESIGN.md` prose updated; `just design-check` passes (drift, WCAG AA, lint).
2. `Theme.swift` reads `Tokens.swift` instead of literals; `project.yml` compiles the generated file.
3. Settings → Appearance: tint (clear plus presets plus custom), glass style (regular, clear), accent (system or a preset), backdrop wash strength; stored in `AppSettings`, applied through one environment value; Reduce Transparency and Increase Contrast still win over the user's choice.
4. Tests: Swift Testing for the settings model (defaults, persistence, accessibility overrides).

Check: `just design-check`, `xcodebuild test` for the app, a screenshot of both appearances.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/197. To meet WCAG AA the accent deepened to #0873e0 (white on #0a84ff is 3.65:1) and the dark wash colours changed; F15's Figma variables were synced to these values.
