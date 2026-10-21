---
id: M20
title: "Dev builds: floating tags"
status: done
created_at: 1792583854
updated_at: 1792583854
---

A release whose tag is not a version (`nightly`, `dev`, …) installed by name, `pkg@nightly`, records channel `dev` with the tag and the build it got (publication date and asset digest). `outdated`/`upgrade` report it outdated when the same tag now points at a different build, and reinstall it. `ketch.lock` pins the build's checksum so a lock cannot silently move with the tag (row in `docs/LOCKFILE.md`). Done means tests on build comparison and an end-to-end case where the fixture republishes the tag.

Execution plan:

1. `crates/ketch-core/src/model.rs`: `Channel::Dev`, chosen by `Channel::of` for a release whose tag is not a version (after `draft`); `InstalledPackage::published_at`, recorded for `dev` packages only.
2. `crates/ketch-core/src/install.rs`: `rebuilt` compares the build the tag names now with the installed one — the installed asset's digest when the source publishes one, the publication time otherwise, and an asset gone from the release is a new build. `latest_release` resolves a `dev` package's own tag; `is_upgrade` calls a new build under it an upgrade; `prepare` lets a new build under the same tag past "already installed".
3. `crates/ketch-core/src/lockfile.rs`, `src/cmd/lock.rs`: `published` on a `dev` entry, validated; `lock --check` compares it on another target; `sync` on another target, where the hash does not apply, refuses a tag published since (`InstallRequest::expected_published`). The same target keeps the hash pin. `docs/lock.schema.json` regenerated.
4. Tests: build comparison and lock-pin unit tests in `install.rs` and `lockfile.rs`; `tests/dev.rs` through the fixture plugin, which can now publish under a floating tag with a publication time: a republish is outdated and upgraded, a stable release is not, reinstalling the tag takes a new build, and a lock refuses the moved tag on both targets.
5. Docs: `docs/COMMANDS.md` and `docs/LOCKFILE.md` in English, `docs/ru` and `docs/uk`.
6. Verify: fmt, clippy `-D warnings`, nextest, and the binary against a scratch root.

Status: done (Claude Code / opus-5.5). A tag that is not a version lands on `dev` whatever else was opted into, except `--draft`. `dev` packages follow their own tag, so a newer version under another tag is not an upgrade. The lock's same-target `sha256` already pinned the build; `published` covers other targets, where only the publication time is left to compare, so a tag republished in place with the same date is not caught there. Source plugins keep the protocol unchanged; a plugin release whose tag is not a version is on `dev` too, compared by the digest and date it already reports. `list` still looks up `latest` for a `dev` package (its version ordering keeps it from offering a version release as an update); M21 reworks that lookup. Checked with the real binary on `neovim/neovim@nightly` in a scratch root: installed on `dev` with its publication time, up to date, `already installed` on reinstall, `published` in `ketch.lock`, outdated and upgraded after the recorded build was rewound in the scratch state, and a cross-target `sync` refused with a lock recording an older publication time.

Tests: `install::tests` (channel of a tag that is not a version, digest, date, missing date, renamed asset, a stable package ignoring builds under its tag, the lock's publication pin), `lockfile::tests` (`published` written for floating tags only, drift on another target, unprintable values refused), and `tests/dev.rs` through the fixture plugin, which can now publish under a floating tag with a publication time. 1130 tests pass; clippy `-D warnings` and fmt clean.
