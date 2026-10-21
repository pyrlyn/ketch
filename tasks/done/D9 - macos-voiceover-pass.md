---
id: D9
title: "macOS: VoiceOver pass"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The macOS app's accessibility was only checked on the fake core. F14 keeps its own Accessibility Inspector pass on the real glass; this task covers labels. Localisation is not needed for now (creator, 2026-10-01, open decision 7), so no String Catalog. Research: section 1.

Done when every icon-only control has an accessibility label, and a VoiceOver walk through the nine screens finds no unlabeled control.

Execution plan:

1. Read every view for symbol-only controls and bare text fields; the existing icon-only buttons already name themselves through their title.
2. `KetchUITests`: one test visits the nine screens (Installed, Discover, Updates, Activity, Doctor, General and Appearance settings, package detail, uninstall sheet) and runs `performAccessibilityAudit(for: [.sufficientElementDescription, .elementDetection])`, ignoring layout containers; it also looks up by label the controls a quiet screen does not show (colour wells, the running operation's Cancel button, the menu-bar extra).
3. Fix what it finds: the search field, the colour wells, the wash slider, the menu-bar label.
4. Verify: `just macos-test`.

Status: done. No manual VoiceOver walk was possible, so the automated audit stands in for it; it finds elements with no description but cannot judge whether a label reads well. Fixed: the search field (its prompt was only a placeholder, now labelled, glyph hidden), the two colour wells (labelled "Custom tint" and "Custom accent" instead of both "Custom"), the wash slider (label and percentage value), and the menu-bar extra ("Ketch, 2 updates available"). The symbol-only Clear, Dismiss and Cancel buttons already carry their titles; Cancel is checked in the test, Clear (needs typed text, which XCUITest could not enter here) and Dismiss (needs a held lock) are not. The Touch Bar, layout containers and a slider thumb are excluded from the audit.
