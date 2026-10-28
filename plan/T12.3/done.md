# T12.3. Done

`desktop/src/main/kotlin/ketch/desktop/ui/`: the window and its screens on `KetchStore`.

- Tokens: `desktop/design/tokens.json` is copied unchanged from the Rust tree and put on the
  classpath. `Tokens` reads the brand subset (`BRAND` of `build.mjs`) in light, dark, high
  contrast and high contrast dark, with the same fallbacks and aliases. `KetchTheme` maps it
  onto the Material 3 scheme, type scale and shapes, plus `LocalStatusColors` (named
  `StatusColors`, not the planned `KetchColors`), `LocalSpacing` and `LocalMonoStyle`.
  High contrast is read only on Windows (`win.highContrast.on`); the JVM has no reading for
  it on macOS or Linux, so there it follows light or dark only.
- `KetchApp`: a sidebar with the update and problem counts (spoken as "Updates, 1 update"),
  the busy bar with Retry, the activity bar with Cancel and progress, the error bar, and the
  binary-choice, stop-processes and leftover-folder dialogs. Operations run on the window's
  scope, so leaving a screen does not cancel them.
- Screens: Installed (filter, update chip, uninstall with its confirmation), Discover (search
  after typing, install a known package or a repository), Updates (update one, update all
  after a confirmation, held back, check now), the package page (overview, changelog, files,
  update, install, open on GitHub, uninstall; it closes once the uninstall is confirmed),
  Activity (the log, following the newest line) and Doctor (summary, checks, fixes).
- Changelogs render through `multiplatform-markdown-renderer-m3` behind `ChangelogText`;
  `WebOnly` opens only `http` and `https` links.
- `ketch://` links: `KetchStore.open` asks the core for the package and shows its page only
  when it is installed or known to the registry; a link never installs anything. `Main.kt`
  takes links from the command line and `Desktop.setOpenURIHandler`, finds the native ketch
  (`KETCH_EXE` or the app resources), starts `ketch serve` and closes it with the window.
- ktlint and detekt now allow type-style names on `@Composable` functions only.

Commits: `1980cb6 T12.3: claim and plan`, `5a5da35 T12.3: brand tokens and the Material
theme`, `0868434 T12.3: window, screens, links and UI tests`.

Checked: `./gradlew build` green. 20 new desktop tests (65 in all): `AppTest` 11 Compose UI
tests on `FakeCore` (every section opens; sidebar counts; the installed list; update all
through its confirmation; the package page, its changelog and its uninstall; cancelling an
uninstall; the binary choice reaches the core; the error and busy bars; Doctor's summary;
every clickable node has a name), `TokensTest` 5 (every brand colour in every appearance
against its hex, the tokens the theme reads, the brand subset, a broken alias, web-only
links) and `LinkTest` 4. `./gradlew :desktop:run` with `KETCH_EXE` on the debug ketch and
`HOME`/`KETCH_HOME` in a scratch folder: the app starts `ketch serve`, which lays out
`~/.ketch` there. That run found a bug (the window closed the core as soon as it arrived),
fixed before the commit. The window itself was not looked at: screen access was declined.
