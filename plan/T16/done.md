# T16. Done

On macOS the desktop app wears the `ketch Store — Discover` design: all eight artboards (Discover,
Installed, Updates, Activity, Doctor, Package, Settings with Appearance, the sheets), driven by the
store's data. Windows and Linux keep Material 3; the Material tree is unchanged apart from the test
pinning it. Commits on branch `T16-glass-design`, and `feat(kotlin-glass)` on branch `kotlin-glass`
of `pyrlyn/packages`, local and not pushed yet: the app's build needs that package.

## What was done

- The `kotlin-glass` package (`packages/kotlin-glass`): panes, controls, sheet, status bar, tiles,
  sidebar and toolbar for jvm and wasmJs. No business logic: every word, colour and action comes
  from the caller.
- `desktop/src/main/kotlin/ketch/desktop/ui/glass/`: the palette from the macOS tokens, the shell,
  one file per screen.
- `Look` picks Glass on macOS; `Navigation` gained history, Sources and ⌘K; `Prefs` gained the
  Appearance choices under the macOS app's keys; the window draws under the real traffic lights.

## Left out or changed from the design

Left out because the store has no data or no action for them:

- the kind segment on Discover and its "See all";
- "How ketch picks the asset" on the package page;
- the `ketch why` terminal;
- the Fix button on Doctor.

Changed because Compose or AWT cannot do what the design draws:

- sheets and the floating bar are nearly opaque, as Compose has no backdrop blur;
- Reduce Transparency, Increase Contrast and Reduce Motion are not followed, as AWT does not expose
  them;
- the hero's float animation is left out.

## How it was checked

- `./gradlew build` in ketch-kotlin: compile, tests, ktlint and detekt; `GlassAppTest`,
  `GlassHelpersTest` and `FindShortcutTest` cover the screens, the helpers and ⌘K.
- `./gradlew check` in `packages/kotlin-glass`.
- Every screen rendered off-screen to PNG, light and dark, and compared with the artboards.

## Not checked

The real macOS window: the traffic lights, the title bar strip and the content drawn under them.
The rendered PNGs do not show the window's chrome, and the app was started but not looked at on
screen. ⌘K is tested in two halves, `FindShortcutTest` on the key event and `GlassAppTest` on the
focus, but not through a real window's key handling. `./gradlew :desktop:run` shows all of this.
