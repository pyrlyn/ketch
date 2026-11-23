# AGENTS.md — ketch

If an `AGENTS.md` or `CLAUDE.md` exists higher in the tree, follow it too; on conflict, ask the creator.

## What this project is

A port of [ketch](https://github.com/pyrlyn/ketch) (Rust) to Kotlin: the core and the `ketch`
command line on Kotlin/Native, and one Compose Multiplatform desktop app (JVM) in place of the
SwiftUI and WinUI apps. ketch installs command-line tools and apps straight from GitHub releases,
checked against the published checksum, versioned, and cleanly removable.

The source of truth for behaviour is the Rust tree at `../ketch`. The port keeps the command line,
its output, the config and manifest TOML, `ketch.lock`, `state.json`, the `~/.ketch` layout and
`stats.db` interchangeable with Rust ketch.

## Where things are

| Path | What |
| --- | --- |
| `ketch-core/` | Port of `crates/ketch-core`; package `ketch.core.<rust module>` |
| `ketch/` | The `ketch` executable; port of `src/` (`cli.rs`, `cmd/`, `ui.rs`, …) |
| `ketch-wire/` | Records and JSON-lines protocol between `ketch serve` and the desktop app (port of `crates/ketch-ffi`'s surface); built for native and jvm |
| `desktop/` | Compose desktop app (JVM); runs the native `ketch`. Glass on macOS (`ui/glass/`), Fluent on Windows (`ui/fluent/`), Adwaita on Linux (`ui/adwaita/`), Material 3 elsewhere (`ui/screens/`) |
| `desktop/e2e/` | The end-to-end tests' data, each system's reference images and the Linux and Windows runs; the tests are in `desktop/src/test/kotlin/ketch/desktop/e2e/` |
| `buildSrc/` | Convention plugins `ketch.kmp` and `ketch.compose` |
| `plan/` | Tasks, one folder per task; `plan/README.md` holds the port map and decisions |

## Design

The desktop app's platform looks are designed in Figma.

### Windows (Fluent)

The Windows look of the desktop app is designed in Figma:
[ketch Store — Windows 11](https://www.figma.com/design/gZ6nDsVkPPKjGlZSdbEPdq).

| Page | What |
| --- | --- |
| [Foundations](https://www.figma.com/design/gZ6nDsVkPPKjGlZSdbEPdq?node-id=2-2) | Variables `Color` (modes Light / Dark) and `Size`, text styles, effect styles (Acrylic window, flyout, card glass) |
| [UI Kit](https://www.figma.com/design/gZ6nDsVkPPKjGlZSdbEPdq?node-id=2-3) | The Ketch Windows 11 component library: icons, Button, StatusBadge, TextBox, ToggleSwitch, NavigationItem, TitleBar, AppIcon, ListItem, SettingsCard, AppTile, ContentDialog and more |
| [Screens](https://www.figma.com/design/gZ6nDsVkPPKjGlZSdbEPdq?node-id=2-4) | Every screen of the app, built only from UI Kit instances, plus a dark-mode copy of Discover |

When you work on the Windows look (`desktop/…/ui/fluent/`):

- Read the screen in Figma first (Figma MCP `get_design_context` / `get_screenshot` on the frame)
  and build it from the matching `pyrlyn-fluent` component; the UI Kit component's description
  says what it is for.
- Colours, spacing and radii come from `desktop/design/tokens.json` in code. Each Figma variable
  carries its CSS name as code syntax (`var(--accent)`); when the two disagree, `tokens.json` wins
  and the Figma variable is the one to fix.
- New screens or components in Figma are built from UI Kit instances and bound to its variables
  and styles, never from raw colours or detached copies. Add a missing control to the UI Kit
  first, then use it.
- Figma uses Open Sans because Segoe UI Variable is not available there; the app uses the
  system's Segoe UI Variable.

### Linux (Adwaita)

The Linux look (`desktop/.../ui/adwaita/`, components from pyrlyn-adwaita) follows the
**Ketch Linux** design, a GNOME/libadwaita frosted-glass style.

- Figma: <https://www.figma.com/design/kjFRSE8wBULXeegP5pl7bf>
  - **UI Kit** page: variables (`Ketch Linux · Color`, `Ketch Linux · Size`), text and effect
    styles, 37 icons, and 29 components with variants and properties (Button, StatusPill, Switch,
    ActionRow, HeaderBar, Dialog, FormField, …).
  - **Screens** page: 13 desktop frames built from kit instances. Discover, Installed, Updates,
    Activity, Main menu, Package detail, Doctor, Settings, Uninstall dialog, Sign in, Create
    account, Profile, Account settings.
- Design system with tokens, component specs and usage notes:
  <https://claude.ai/artifact/48tdpYBEYVGL7pYjbGSjrw>
- Interactive canvas of the same screens: <https://claude.ai/artifact/QyfBhRwhfGjJAFFKotsaKw>

Rules for agents:

- Read the Figma file (Figma MCP: `get_design_context`, `get_screenshot`,
  `get_variable_defs`) before you build or change a Linux screen.
- Map Figma variables to theme tokens. Don't hard-code colours, radii or spacing.
- When a screen needs something the kit lacks, add it to the UI Kit page as a component, then
  use an instance of it on the screen.
- Keep the style flat: icons, status badges and dock tiles use a solid colour plus a 1–2 px
  shadow. Only the wallpaper and the window glass have depth.
- Use GNOME patterns: a header bar with a view switcher, a hamburger main menu, boxed lists and
  GNOME switches. Don't make it look like Windows or iOS.

## Build and test

Needs JDK 21 and the `shared-settings`, `pyrlyn-kit`, `pyrlyn-snapshot`, `pyrlyn-glass`, `pyrlyn-fluent` and `pyrlyn-adwaita` builds in
`packages/`, found in any ancestor directory (or `SHARED_SETTINGS_DIR`, `KOTLIN_KIT_DIR`,
`KOTLIN_SNAPSHOT_DIR`, `KOTLIN_GLASS_DIR`, `KOTLIN_FLUENT_DIR`, `KOTLIN_ADWAITA_DIR`).
Files, TOML, YAML, semver, HTTP and SQLite come from pyrlyn-kit: change them there, not here.
Glass components come from pyrlyn-glass, Windows ones from pyrlyn-fluent and Linux ones from
pyrlyn-adwaita; none holds business
logic: what is ketch's stays in
`desktop/`.

```bash
./gradlew build                    # everything: compile, tests, ktlint, detekt
./gradlew macosArm64Test           # native unit tests
./gradlew :desktop:run             # the desktop app; KETCH_LOOK=fluent shows another system's look
./gradlew :desktop:packageDmg      # the app's package, with the release ketch inside
./gradlew ktlintFormat             # fix formatting
./gradlew :desktop:e2e             # the window, the real ketch and a stand-in GitHub, every screen against its reference
desktop/e2e/linux/run.sh           # the same on Linux, in a Docker image on Colima
desktop/e2e/windows/run.sh         # the same in the Parallels Windows VM
```

`-Pe2e.record`, on any of the three, writes the references instead of comparing. A failed comparison leaves
`<screen>_compare.png` and `<screen>_actual.png` in `desktop/build/e2e`. A change that moves a screen records on all three
systems, since each draws its own fonts, and every changed image is looked at before it is committed.

## Rules

- Code, docs, comments, commits and PR text in English.
- Port behaviour, not syntax. Every Rust unit and integration test of a ported file is ported
  to `kotlin.test`; a phase is done only when they pass on the Mac.
- Tests never touch the real `HOME`: the root build points `HOME` and `KETCH_HOME` at
  `build/test-home` for every native test run. No network in tests.
- Everything a release, an archive, a manifest or a registry contains is untrusted input; port
  every guard the Rust code has (zip-slip, size limits, checksum checks) together with its test.
- A third-party library sits behind our own interface, except Kotlin's own (stdlib, kotlinx.*,
  Ktor), so it can be replaced alone.
- What the desktop app says to a person lives in
  `desktop/src/main/composeResources/values/strings.xml`, never as a literal in Kotlin. The file's
  head holds the key convention and the glossary. The command line stays English.
- ktlint and detekt must be clean.
- Commit in small green steps; every change ends in a PR once the repository has a remote.
