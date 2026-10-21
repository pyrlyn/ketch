---
id: B47
title: "`release.sh` scrapes `cargo metadata` by regex"
status: done
created_at: 1792583218
updated_at: 1792583218
---

The regex depended on `"name"` immediately preceding `"version"` in `cargo metadata` output. When it failed, the script aborted on a leftover release branch with only "nothing was pushed" as guidance.

`package_version()` now reads the version via `cargo pkgid --offline`. `abort_release()` cleans up an uncommitted release branch automatically and, after a commit, explains how to push or delete the local (and remote) branch. Added `tests/release-sh-version.sh` and wired it into CI shell checks.
