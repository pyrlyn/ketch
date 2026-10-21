---
id: B66
title: Windows self-uninstall removes the registry entries ketch wrote at install
status: done
created_at: 1792583218
updated_at: 1792583218
---

Ivan: uninstalling ketch on Windows must remove the registry entry that was added at install.

What ketch writes to the registry today: only `HKCU\Environment\Path`. `install.ps1` adds the bin dir there, and so does `ketch path install` (`shell::install_user`, through `[Environment]::SetEnvironmentVariable(..., 'User')`). There is no Apps & Features (`...\CurrentVersion\Uninstall\ketch`) key, because `dist-workspace.toml` sets `installers = []`. `self uninstall` removes the Path entry only when `UninstallPlan.user_path` is true (`shell::user_path_configured(cfg)`). That is false under `--keep-packages`, and it may miss an entry written by `install.ps1 -InstallDir <dir>` for a bin dir that is not `cfg.bin_dir`.

Plan:
1. Reproduce on a Windows runner: `install.ps1` (default and with `-InstallDir`), then `ketch self uninstall --yes`, then read `HKCU\Environment\Path` and list what is left.
2. Keep one inventory of every registry value ketch writes (a function in `src/shell.rs`, for example `registry_entries(cfg)`): the user Path entry today, and M12's `HKCU\Software\Microsoft\Command Processor\AutoRun` addition later. `self uninstall` removes each entry it finds, matching Path entries the way `install.ps1`'s `Normalize-PathKey` does (quotes, slashes, trailing separator, case).
3. `ketch doctor` warns when an inventory entry points into a ketch root that no longer exists.
4. Ask Ivan whether he also expects an Apps & Features entry. That would be new (register at `self install`, remove at uninstall), not a fix.

Check: Windows e2e (`tests/install_windows.rs` or `tests/install_ps1.rs`): after `install.ps1` + `ketch self uninstall --yes`, the user Path holds no entry for the ketch bin dir, with and without `-InstallDir`; `just check` green on all three OSes.

Decisions (Ivan): no Apps & Features entry; only clean up what ketch writes.

Result: `shell::registry_entries(cfg)` is the one inventory of registry values ketch writes (`RegistryEntry::UserPath` today; M12's AutoRun joins it), and `self uninstall` removes each one through `shell::remove_registry_entry`, before the root so the bin dir can still be resolved. A Path entry now matches the bin dir by spelling (case, quotes, slashes, trailing separator — as `install.ps1`'s `Normalize-PathKey`) or, while the folder exists, by resolving both paths, which covers 8.3 short names. `install.ps1 -InstallDir` never puts that dir on the user PATH — it always adds `<root>\bin` — so there was no second entry to chase. `--keep-packages` keeps the entry, like the shell blocks, because the packages left in the bin dir still need it. `ketch doctor` warns about user PATH entries that name a ketch bin dir whose folder is gone. Tests: unit tests for resolution matching, stale detection and the empty inventory off Windows; `tests/install_windows.rs` writes a quoted, upper-case, trailing-backslash entry for the sandbox bin dir and checks `self uninstall --yes` removes it. The Windows parts were verified only in CI.
