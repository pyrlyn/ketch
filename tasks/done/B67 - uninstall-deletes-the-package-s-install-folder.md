---
id: B67
title: Uninstall deletes the package's install folder
status: done
created_at: 1792583218
updated_at: 1792583218
---

Ivan: uninstall must delete the program's folder (for example the `ketch` folder).

Today `install::uninstall` removes the current prefix and every retained prefix with `remove_store_dir`, which then calls `remove_dir(store/<name>)`. That call only succeeds when the directory is empty, so any leftover keeps `store/<name>/` in place. Leftovers include `<version>.incoming` or `<version>.old` siblings from `move_into_store` (removed best-effort with `let _ = remove_any`), or a file Windows kept locked. For ketch itself, `self_update::remove_root_at` wipes the named directories and then `remove_dir(root)`. The root stays when anything else is in it. On Windows the running `ketch.exe` cannot delete its own image, so `~/.ketch` survives the uninstall. This is the same cause as B60.

Plan:
1. Package uninstall: after the prefixes are gone, remove `store/<name>/` whole (`remove_dir_all`). Guard it with `is_inside_store`, and only for a direct child of the store named exactly like the package. On failure, warn and name the path.
2. One helper sweeps stale `.incoming` / `.old` siblings. B68 reuses it.
3. Self uninstall on Windows: finish the root removal after the process exits, with a detached `cmd /c` that waits for the PID and then removes the root. `unsafe_code = "forbid"` rules out calling the Win32 API directly. The home-directory safety rule in `remove_root_at` stays: a root equal to `$HOME` is never wiped.
4. `ketch doctor` notes a `store/<name>/` that has no state record.

Check: e2e: install, uninstall, then `store/<name>` does not exist, including with a planted `1.0.0.old` sibling; Windows e2e: `self uninstall` leaves no `~/.ketch` after the process exits; `just check`.

Done (Claude Code / opus-5.5): `install::uninstall` removes `store/<name>/` whole through `remove_package_dir` — only a direct child of the store named exactly like the package, inside the store after symlinks resolve — so `.incoming` / `.old` leftovers go with it; prefixes outside that folder still go through `remove_store_dir`. Self uninstall collects what `remove_root_at` could not delete and, on Windows, hands it to a detached PowerShell that waits for the ketch PID, removes only those named paths and then deletes the root non-recursively (a root equal to `$HOME` is never handed on). Step 4 already existed: `ketch doctor`'s `orphans` check names a store folder with no state record. The shared stale-sibling sweep is left for B68, its first user. Tests: unit tests in `install.rs` and `self_update.rs`, e2e `tests/install.rs::uninstall_removes_the_package_folder_with_what_a_failed_swap_left_in_it` and `tests/self_uninstall_root.rs` (all OSes).

Status: done 2026-09-30
