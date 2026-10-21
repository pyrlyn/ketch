---
id: B35
title: "`tui` feature never compiled by CI and never shipped"
status: done
created_at: 1792583854
updated_at: 1792583854
---

The `tui` feature is optional and not in release tarballs, but nothing in CI compiled or tested it — so breakage there went unnoticed.

Added a `Test (tui)` step (`cargo test --all-targets --locked --features tui`) to the `check`, `check-linux`, and `check-windows` jobs in `.github/workflows/ci.yml`. `scripts/package.sh` is unchanged; AGENTS.md does not require shipping TUI in releases.
