---
id: B58
title: PLUGINS.md flag order disagrees with the client
status: done
created_at: 1792583218
updated_at: 1792583218
---

PLUGINS.md listed `releases <id> [--prerelease] [--limit N]` while the client sends `--limit N` before `--prerelease` when invoking plugin `releases`. Updated the subcommand signature in `docs/PLUGINS.md` to `releases <id> [--limit N] [--prerelease]`.
