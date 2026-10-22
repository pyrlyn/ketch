---
id: K928
title: Probe source plugins only when needed
status: open
created_at: 1792603881
updated_at: 1792603881
---

`SourceRegistry::load` spawns every plugin with `capabilities`, one after another with a 30-second timeout, on every install, list, outdated, info and changelog, and on each FFI call. One hung plugin delays every command, GitHub-only ones included, and the apps pay it on each poll.

Done when discovery only lists candidates, a plugin is probed on the first lookup of its scheme (or capabilities are cached by path, mtime and size), `ketch plugin list` still probes all, and a test shows a sleeping plugin does not slow `ketch list` of GitHub packages.
