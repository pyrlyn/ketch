---
id: K917
title: Retire workspace-hack or keep it in step automatically
status: open
created_at: 1792603868
updated_at: 1792603929
priority: 1
blocked_by:
- K903
---

`cargo hakari generate --diff` is the most frequent CI failure (27 runs in 20 days, every Dependabot cargo PR) yet not required, so drift merges. It also unifies uniffi, aws-lc-rs, tokio and clap into every member: the CLI compiles uniffi only through it, and `scripts/core-features.sh` builds with it on, so the slim-core builds of R19 are not really proven.

Done when, after a measured build-time comparison, the creator picks: remove workspace-hack, or exclude `ketch-ffi`/`ketch-capi` from unification, regenerate it on Dependabot PRs automatically and make `hakari` required. Either way `cargo tree -p ketch -i uniffi` is empty and the feature check runs without the hack.
