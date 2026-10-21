---
id: R2
title: One Rust version, from mise.toml
status: done
created_at: 1792583854
updated_at: 1792583854
---

`mise.toml` pins Rust (`rust = { version = "1.98.1", components = "rustfmt,clippy" }`) and is the single source of the Rust version for local work, CI and release builds. This reverses R1's "the Rust toolchain not pinned": the runner's default stable moved under every workflow independently, so a release could ship from a compiler no pull request had tested. ci.yml, verify.yml, sonarcloud.yml, release-plz.yml and bump.yml install Rust with `jdx/mise-action` (pinned to v4.3.0 by SHA, `install_args` limited to what each job needs), and `.github/build-setup.yml` does the same before rust-cache in the release build, then adds the matrix entry's cross targets with `rustup target add` (mise sets `RUSTUP_TOOLCHAIN`, so they land on the pinned toolchain). `release.yml` is regenerated with `just dist-generate`. rustfmt and clippy are requested explicitly because mise installs the minimal rustup profile. The MSRV (`rust-version = "1.86"` in `Cargo.toml`) is unchanged and separate.

Status: done 2026-09-27 (PR #150)
