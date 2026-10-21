---
id: B132
title: "`install.ps1` omits the `v` on `--version`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`install.sh` accepts `0.10.0` and `v0.10.0` because release tags are always `v`-prefixed. `install.ps1` put the argument into the download URL unchanged, so `--version 0.10.0` requested `…/download/0.10.0/…` and 404ed. After the version is resolved, a value that does not already start with `v` gets one. A tag from the GitHub API already has it, so that path is unchanged.

Test: `a_bare_version_is_printed_with_the_release_tag_prefix` (runs when `pwsh` is installed; the status line is printed before the download).
