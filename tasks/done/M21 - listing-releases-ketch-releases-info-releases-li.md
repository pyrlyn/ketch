---
id: M21
title: "Listing releases: `ketch releases`, `info --releases`, `list`"
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch releases <pkg>` lists a package's releases newest first with tag, version, publication day and a kind mark (`stable`, `pre`, `dev`, `draft`), and marks the installed one; `--pre` widens it to prereleases, `-n/--limit` and `--json` as elsewhere. `ketch info <pkg> --releases` prints the same table under the details and adds a `releases` array to `--json`; both go through `releases::list` and one renderer in `cmd/query.rs`. `ketch list` shows a non-stable channel after the installed version (`(pre)`), adds `channel` to `list --json`, and takes `--pre` for its `latest` column. `releases` completes installed names like `info`. `ReleaseKind::of` in `crates/ketch-core/src/releases.rs` is the one place a kind is decided: draft, then dev (a tag that does not parse as a version), then pre, then stable.
Follow-up for M19/M20: `--draft` on `releases` and `list` waits for `ListOpts.include_draft`; the `draft` mark already renders if a source returns one, and `Channel::Draft`/`Channel::Dev` only need an arm in the channel mark and in `ReleaseKind`.
Model: Claude Code / sonnet-5.5 · Status: done 2026-10-11 · Priority: P2 · Complexity: 3 · Files: `crates/ketch-core/src/releases.rs`, `crates/ketch-core/src/info.rs`, `crates/ketch-core/src/listing.rs`, `src/cli.rs`, `src/cmd/query.rs`, `src/complete.rs`
Check: `cargo nextest run --workspace` (1116 passed, including `tests/releases.rs`); `ketch releases fd` against GitHub in a scratch root marks the installed tag.
