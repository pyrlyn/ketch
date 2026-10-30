# T19. Done

What T17 dropped from the canvas now works on real data, with one exception, named below.

## What was done

- **All / CLI tools / Apps** on Discover. `ketch-wire`'s `RegistryPackage` gained `isApp`; a repository
  found by search is neither kind, and the selector leaves it out of both.
- **"How ketch picks the asset"** and the **`ketch why`** block on a package's Overview. `Call.Why` asks
  the package's source, installs nothing and returns a `Resolution` that serve sanitizes like every
  other thing a release wrote. A page that cannot get an answer still opens. The block is
  `TerminalOutput` in `kotlin-fluent`, which holds no business logic.
- **Fix** on Doctor. `Check.fixable` comes from `repairable` in `ketch-core`, the only check
  `ketch doctor --fix` answers: a `PATH` set up nowhere. The button says `Fixing…` while it runs, as
  the repair goes through PowerShell and took 4.9 s in the VM; a press with no answer looked like a
  bug. The check then reads `Fixed`.
- **Open folder** on a check about ketch's own folder.
- **The selected row**: the package last opened stays marked, on `Navigation.selected`.
- **Update in the notification**: `WindowsToast`, in place of the tray's balloon on Windows. A click
  opens the confirmation, not an update, as the person still decides in the window.

The notice's price, on purpose: Windows reports a click only to a process that is still there, and a
packaged app's COM activator needs an identity an MSI does not give. So a hidden PowerShell child
posts the notice and waits for it, for up to 10 minutes, and the app's AppUserModelID is written
under `HKCU`. Any failure falls back to the tray's balloon.

Commits: `d397cbf` `feat(kotlin-fluent): add TerminalOutput, a command and what it printed`, on the
branch of https://github.com/pyrlyn/packages/pull/2.

## What was not done

**"Also remove its configuration" stays out.** Neither Rust ketch nor this port knows where a package
keeps its configuration, so the checkbox could only delete a guessed path or do nothing. Deleting a
guessed path is worse than not having the box.

## How it was checked

- `./gradlew build --no-watch-fs` on the Mac: compile, tests, ktlint, detekt, successful. 191 desktop
  tests, 0 failures; `FluentAppTest` and `KetchStoreTest` cover each item above, `RecordsTest` covers
  serve's records, `WindowsToastTest` the notice's XML. The contract scenarios `search`,
  `search-browse` and `doctor` carry the new fields.
- On the MSI installed in the VM: the notice `1 update available`, `ripgrep 14.0.0 → 15.2.0`, with its
  Update button. A click in the notification centre brought the window forward and opened
  `Update 1 package?`, and the PowerShell child ended. `ketch rollback ripgrep` returned 14.0.0, and
  `Update all` put 15.2.0 back.
- Fix ran on a real `PATH` problem in the VM and read `Fixed` once it ended.

A `kind` in the registry is rare (one of seven entries in the run), so Apps is a short list today.
