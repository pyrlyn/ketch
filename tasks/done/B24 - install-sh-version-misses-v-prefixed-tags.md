---
id: B24
title: "`install.sh --version` misses `v`-prefixed tags"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`release.yml` creates the tag as `v$version` and `install.sh` pasted `--version` straight into the download URL. The cask already builds `v#{version}` itself.

After version resolution, `install.sh` now prefixes a bare semver with `v` before building release download URLs (a value that already starts with `v` is left alone). The stub-release harness only serves `v9.9.9` assets, and `version_flags_accept_bare_and_v_prefixed_tags` checks both `9.9.9` and `v9.9.9`.
