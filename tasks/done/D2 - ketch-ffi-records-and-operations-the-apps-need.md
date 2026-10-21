---
id: D2
title: "`ketch-ffi`: records and operations the apps need"
status: done
created_at: 1792583218
updated_at: 1792583218
---

The macOS app's protocol needs things `ketch-ffi` does not give: a changelog across a version range, pinned packages in `outdated` with what holds them, `latest` in search results, and an `uninstall` that can be cancelled, reports progress and removes a leftover store folder for a name with no record, as the CLI does. Research: section 3a, gaps G2–G5.

Done when `changelog_range(package, from, to)`, `Upgrade.pinned` (and the lock that holds it, when known), `RegistryPackage.latest` and the new `uninstall` exist with unit tests, the Swift binding test exercises each, and D4's fixtures can model them.

Execution plan (Claude Code / opus-5.5):

1. `ketch-core::changelog`: a `published_range` beside `published`, sharing its manifest resolution, plus a pure `between` that keeps the releases newer than `from` and no newer than `to`, newest first, drafts and unasked-for prereleases out. Unit tests on `between`.
2. `ketch-core::listing`: `fill_cached`, which answers `latest` from the listing cache only — no network — so search can show it.
3. `ketch-ffi`: `changelog_range` (`from` defaults to the installed version, `to` to the newest); `Upgrade.pinned` and `Upgrade.held_by` (ketch does not record which `ketch.lock` restored a pin, so `held_by` stays `None` until it does), `outdated` reports pinned packages and `upgrade` still skips them; `RegistryPackage.latest` from the cache; `uninstall` removes a leftover `store/<name>/` through `install::remove_package_dir` for a name with no record, then answers `NotFound` as the CLI does. Unit tests in `lib.rs` / `records.rs`.
4. Swift binding test: a range over the `test:` plugin's releases with notes, `pinned`/`heldBy` on an upgrade, `latest` on a search result, a cancelled uninstall that reports nothing removed, and a leftover folder removed.
5. Verify: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`, `just ffi-test`.

Status: done. `changelog_range` reads release notes only: the installed payload's file belongs to the old version and has no sections for the newer ones. `held_by` is always `None` for now, because `ketch sync` restores a pin without recording which `ketch.lock` it came from; recording that is a state change left for a later task. `outdated` now reports pinned packages, marked, and `upgrade` still skips them. `uninstall` reports a `removing` status and a `removed` success per package. Core gained `changelog::published_range` / `between` and `listing::fill_cached`; `published` shares its manifest fallback with the range through `manifest_for`. A breaking change to the binding (new record fields, pinned rows in `outdated`).
