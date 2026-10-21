---
id: B12
title: "`registry validate` passes a symlinked `ketch.toml`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

The guard that stopped `check_tree` reading through a symlink also dropped the folder from discovery, so a tree with one valid package and one symlinked `ketch.toml` could still report validated and exit 0.

`candidate_package_dirs` now lists folders whose `ketch.toml` exists even when it is a link; `is_package_file` rejects non-regular files. `check_tree` records a `ValidationError` without reading through the link; `load_dir` warns and skips. Covered by `check_tree_never_reads_through_a_symlinked_package_file` and `load_dir_warns_when_ketch_toml_is_a_symlink` on Unix.
