---
id: A2
title: 2026-09-20 product audit — docs sync, version guard, troubleshooting, reference plugin, e2e gaps
status: done
created_at: 1792583218
updated_at: 1792583218
---

ROADMAP rewritten to match reality (signatures → shipped M3, man pages → shipped M4, `why` → shipped M7, registry maturity → partial with registry CI dropped as F2); `todo.md` synced with `plan.md`. Version drift closed (`Cargo.toml` = CHANGELOG 0.4.6 = tag `v0.4.6` = `site/hugo.toml`) plus a CI guard: `tests/crate-version.sh` fails when crate version, latest tag, and changelog entry disagree (wired into `just lint-shell` and CI). Registry CI resolved as dropped with local validation + pre-push hook in `docs/REGISTRY.md`; staleness needs no new code (`registry status` / doctor already report age from `registry.meta.toml`). Added `docs/TROUBLESHOOTING.md` (site-wired), `examples/ketch-source-example`, trycmd help snapshots (`tests/cases/help*.trycmd`), `tests/plugin_fail.rs`, `tests/lock_extras.rs` (lock exit 8 + extras link/unlink, new `Sandbox::ok_env`).

Verified: `cargo fmt --check` clean, `cargo clippy --all-targets -D warnings` clean, full `cargo nextest run` 571 passed, shell checks green. Left open: notarisation secrets (creator step), multi-version/aqua evaluation (see A3).
