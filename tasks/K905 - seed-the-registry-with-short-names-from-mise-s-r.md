---
id: K905
title: Seed the registry with short names from mise's registry
status: open
created_at: 1792603859
updated_at: 1792603928
priority: 0
blocked_by:
- K910
---

`pyrlyn/ketch-registry` holds 7 packages and `builtin.toml` 2, so `ketch install jq` fails: installing by name, the first thing a new user tries, almost always ends in "use owner/repo". mise's `registry/*.toml` (about 1,015 short names) and aqua's registry (about 2,000 GitHub-release entries) are MIT and already map names to repositories.

Done when a converter script turns each mise entry with an `aqua:`, `github:` or `ubi:` backend into `<name>/ketch.toml` (source, description, `provides` from its bins), at least 300 entries pass `ketch registry validate`, a NOTICE credits the MIT sources, and `ketch install jq`, `fd`, `bat`, `gh` and `lazygit` resolve.
