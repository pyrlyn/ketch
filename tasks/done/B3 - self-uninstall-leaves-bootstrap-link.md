---
id: B3
title: "`self uninstall` leaves bootstrap link"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`self uninstall` left `install.sh`'s bootstrap link dangling on `PATH`. An explicit `--install-dir` followed by `self uninstall --yes` removed the root and left `<install-dir>/ketch -> <root>/bin/ketch` pointing at nothing.

`ketch self install --link-dir <dir>` records that path as a `LinkRecord` (including when the package is already installed) so `install::uninstall` takes it back. `install.sh` passes `--link-dir` and no longer writes outside the root itself. Tests: `uninstall_takes_back_a_bootstrap_link_dir`, `self_uninstall_removes_a_bootstrap_link_outside_the_root`.
