# T17. Done

On Windows the desktop app wears the `ketch Store — Windows 11` canvas in the colours of the
`Pyrlyn Windows 11` design system: every screen and dialog of the canvas, driven by the store's
data. Linux and Windows high contrast keep Material 3. `KETCH_LOOK` shows a look on another system.
Commits on branch `T17-fluent-design`, and the `kotlin-fluent` package in
https://github.com/pyrlyn/packages/pull/2, which stands on https://github.com/pyrlyn/packages/pull/1:
the app's build needs both packages.

## What was done

- The `kotlin-fluent` package (`packages/kotlin-fluent`): palette, surfaces, controls, layout and
  dialog for jvm and wasmJs. No business logic: every word, colour and action comes from the caller.
  It reuses `Glyph` and `AutoGrid` from kotlin-glass, which gained the new glyphs.
- `desktop/src/main/kotlin/ketch/desktop/ui/fluent/`: the palette mapping, the shell, one file per
  screen, the dialogs.
- What both looks need moved to `ui/Host.kt`, `ui/Log.kt` and `ui/Summaries.kt` instead of being
  copied.
- An undecorated window with its own title bar: it moves by the bar, maximizes on a double click,
  resizes by its edges and keeps clear of the taskbar. `Prefs` gained the window material.

## Left out or changed from the design

Left out because the store has no data or no action for them:

- the All / CLI tools / Apps selector on Discover;
- "How ketch picks the asset" and the `ketch why` block on the package page;
- the Fix button on Doctor, which shows the `Fix: …` line instead;
- "Open folder", "Also remove its configuration" and the selected row;
- the Update button in the notification.

"Popular command-line tools" became "In the registry", as that is what the list is.

Changed because Compose or AWT cannot do what the design draws:

- the window draws its own wall, so the material is a fill over that wall, not over the desktop, and
  the setting's caption says so;
- Automatic accent is ketch's own blue, as the app does not read the Windows accent colour;
- an undecorated window gets neither Windows 11's rounded corners nor Snap Layouts;
- `maximizedBounds` is tested on the primary screen only.

## How it was checked

- `./gradlew build` in ketch-kotlin: compile, 176 desktop tests, ktlint and detekt, 0 failures.
  `FluentAppTest`, `FluentHelpersTest` and `FindShortcutTest` cover the screens, the helpers and Ctrl+K.
- `./gradlew check` in `packages/kotlin-fluent` (24 tests) and `packages/kotlin-glass`.
- Every screen rendered off-screen to PNG, light and dark, and compared with the artboards.
- The app run on Windows 11 on Arm in the Parallels VM, on Temurin 21 x64 with a cross-compiled
  `ketch.exe`: Segoe UI is picked, `ketch serve` answers Doctor and a live search, all screens open,
  the caption buttons work, the maximized window ends exactly at the taskbar, and the window moves
  and resizes. Tested on one high-DPI monitor.
- That run found what the tests could not: a tap detector inside the drag area took the press, so the
  window would not move. The double click now wraps the area, with a test. Doctor's "1 warnings" went
  the same way.
- The installer was not built on Windows: `conan`, `cmake` and `ninja` are in the VM, in
  `C:\Users\Public\tools`, but were not on `PATH`, so `ketch.exe` came from the Mac and
  `:desktop:run` skipped the native link. T18 built it.

Gradle's file watching reported tasks as up to date after edits here, so the final runs used
`--no-watch-fs`.
