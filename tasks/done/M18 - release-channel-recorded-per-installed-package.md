---
id: M18
title: Release channel recorded per installed package
status: done
created_at: 1792583854
updated_at: 1792583854
---

The foundation the other three build on. An installed package remembers which channel it came from, and `upgrade`/`outdated`/`list` read it instead of only today's `--pre`/config/manifest union. Done means: `InstalledPackage` has a `channel` (`stable`, `pre`; `draft` and `dev` are added by M19 and M20), state written before it still reads as `stable`, `install --pre` or `pkg@<prerelease tag>` records `pre`, and `upgrade`/`outdated` of a `pre` package consider prereleases without `--pre`. A stable install over a `pre` one moves it back to `stable`.

Execution plan:

1. `crates/ketch-core/src/model.rs`: `Channel` enum (serde lowercase, default `stable`, skipped when `stable`) and the field on `InstalledPackage`.
2. `crates/ketch-core/src/install.rs`: derive the channel from the selected `Release` (`prerelease` flag or semver prerelease) when recording state; `installed_list_opts` includes prereleases for a `pre` channel.
3. `crates/ketch-core/src/listing.rs`: the same rule through `installed_list_opts`, so `ketch list` agrees with `outdated`.
4. `src/cmd/query.rs` and `crates/ketch-core/src/info.rs`: `info` shows the channel and looks up `latest` by it. `list --json` waits for M21, which reworks the listing anyway.
5. Tests: unit tests for the default, the derivation, `installed_list_opts` and `upgrade_request`; `tests/channel.rs` installs with `--pre`, checks `outdated` without `--pre` sees a newer prerelease, and that an upgrade landing on a stable release stays on `pre`.
6. Docs: `--pre` in `docs/COMMANDS.md`, `docs/ru` and `docs/uk`.
7. Verify: `cargo nextest run --workspace`, `cargo clippy --workspace --all-targets`, `cargo fmt --check`, and the binary against a scratch `KETCH_ROOT`.

Status: done (Claude Code / opus-5.5), commits 64ba2a6 and 549c274. 1099 tests green; `install sharkdp/fd --pre` in a scratch root records `pre`, `--force` without it returns to `stable`. `upgrade_request` lost its `prerelease` parameter: the tag is exact, so only the channel matters. Known gap: `install --pre` over the same tag already installed stops at "already installed" and does not switch the channel; `--force` does.
