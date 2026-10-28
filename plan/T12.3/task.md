---
status: in progress
priority: P1
complexity: 3
readiness: 10%
agent: Claude Code / claude-opus-5-5
---

# T12.3. Window and screens

The window and its sections on the store: Installed, Discover (search and browse), Updates,
the package detail with its changelog, the uninstall confirmation, Activity, Doctor, and the
question dialogs. Material 3 with the brand tokens of `desktop/design/tokens.json` (accent,
status colours, spacing, radii, type scale) in light, dark and high-contrast themes, as the Rust
Windows and Linux apps take the brand subset. Compose UI tests on the fake core.

## Plan

1. Tokens: copy `tokens.json` unchanged from the Rust tree's `desktop/design/` to
   `desktop/design/`, with a README like `contract/`'s; the build puts it on the classpath.
   `ui/Tokens.kt` reads the brand subset (`color.accent`, `color.status`, `space`, `radius`,
   `typography`, the `BRAND` list of `build.mjs`) in light, dark, high contrast and high
   contrast dark, with the same fallbacks. `ui/Theme.kt` maps it onto a Material 3 colour
   scheme, type scale and shapes, plus a `KetchColors` local for the status colours. High
   contrast follows the Windows setting; other systems have no reading for it from the JVM.
2. `ui/App.kt`: the window with a navigation rail (Installed, Discover, Updates and Doctor
   with counts, Activity), the busy, running and error bars, and the question dialogs.
   Screens in `ui/screens/`: Installed (filter, uninstall), Discover (search after typing,
   install a known package or a repository), Updates (update one, update all with a
   confirmation, held back, check now), the package detail (overview, changelog, files,
   update, install, uninstall with its confirmation), Activity (the log), Doctor (run).
3. Changelog Markdown through `multiplatform-markdown-renderer-m3` behind `ChangelogText`;
   links open only `http` and `https`.
4. `ketch://` links: `KetchStore.open(link)` asks the core (`package_for_link`) and opens the
   package page; nothing a link says installs or removes anything. `Main.kt` takes them from
   the OS (`Desktop.setOpenURIHandler`) and from the command line. Registering the scheme
   with the OS is packaging (T12.5).
5. Tests: Compose UI tests on `FakeCore` (every section opens; the installed list; update
   all through its confirmation; the package page and its uninstall confirmation; every
   clickable node has a label, after the macOS accessibility audit; the questions reach their
   dialogs), token tests (every brand token in every appearance, hex matches components), and
   the store's link tests from `KetchStoreTests.swift`.

Not here: Settings, About, the tray and update notices (T12.4).

Check: `./gradlew build` green, and the app opens on the native `ketch` (`./gradlew :desktop:run`).
