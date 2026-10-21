---
id: B32
title: "`self update` replaces whatever binary is running"
status: done
created_at: 1792583218
updated_at: 1792583218
---

With no `ketch` entry in `state.json` it copied the release over `current_exe()` with no check that the path is inside the root.

In-place self-update now refuses when the running binary is outside `cfg.root` (`is_inside_root`), with a message to run `ketch self install` first. Unit tests cover inside, outside, and prefix-trap paths.
