---
id: B20
title: "`self update --dry-run` says would update when it would not"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`replaced: false` covered both "already current" and "dry run", and the verb was chosen from `dry_run` alone.

Added `would_update` to `SelfUpdate`, fixed the already-current guard to treat `v`-prefixed tags as the same version (`matches_request`), and chose the verb from `would_update` instead of `dry_run`. Integration tests in `tests/self_update.rs` mock the GitHub API and assert the dry-run message.
