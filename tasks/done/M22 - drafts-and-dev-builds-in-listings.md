---
id: M22
title: Drafts and dev builds in listings
status: done
created_at: 1792583854
updated_at: 1792583854
---

`ketch releases` and `info --releases` take `--draft` (drafts and prereleases, the same token and push-access errors as `install --draft`; a `draft` install lists drafts without it). `ketch list` takes `--draft` for its `latest` column, and its update decision is the one `outdated` makes for every channel: a `draft` package is offered the published release and warned about when the draft is gone, a `dev` package shows a new build under its own tag. `install::is_upgrade` is the single rule; the listing cache keys and file format change so old entries are not misread. Done means the above with unit tests on the shared rule and the cache key, end-to-end cases, and the stale M21 docs fixed in all three languages.

Execution plan:

1. `crates/ketch-core/src/source/mod.rs`: one `ask_for_drafts` that widens `ListOpts` for `--draft` and refuses a source without drafts by name; `install::prepare` and `releases::list` both call it, and GitHub's access check stays in `list_releases`/`resolve`.
2. `crates/ketch-core/src/install.rs`: `InstalledBuild`, what an upgrade decision needs of an installed package; `is_upgrade` takes it and the lookup's `ListOpts`, and gains the listing's guard against a prerelease nobody asked for; `upgrade_opts` and `release_of` (latest, or a `dev` package's own tag; drafts dropped for a source without them) shared by `outdated`, `upgrade` and `list`.
3. `crates/ketch-core/src/listing.rs`: `Local` carries the `InstalledBuild`; `Lookup` adds drafts and a `dev` tag to its key; `Found` keeps the trimmed release the rule needs (asset digests only for `dev`); the cache file carries a format number and an older one is ignored; `listing::update_available` goes, `Row::newer` calls `is_upgrade`, and `fill_latest` lets it warn once for a gone draft. `include_drafts` for `list --draft`, installed rows only.
4. `src/cli.rs`, `src/cmd/query.rs`: `--draft` on `releases`, `info` (with `--releases`) and `list`; `crates/ketch-ffi/src/records.rs` uses `Row::newer`.
5. Tests: the shared rule and cache key in `install.rs`/`listing.rs`; `tests/draft.rs` (`releases --draft` and `list` through the stand-in API), `tests/dev.rs` (a republish shows as an update in `list`).
6. Docs: `docs/COMMANDS.md` (`releases`, `info`, `list`, drafts returned by GitHub, the `channel` values) in English, `docs/ru` and `docs/uk`; `bash tests/docs-i18n.sh`.
7. Verify: fmt, clippy `-D warnings`, nextest, and the binary against a scratch root.

Status: done (Claude Code / opus-5.5). `install::is_upgrade` is the one rule: it takes an `InstalledBuild` and the lookup's `ListOpts`, so `outdated`, `upgrade`, `list` and the FFI `Upgrade` record agree on every channel, including the guard against a prerelease nobody asked for, which moved there from `listing::update_available` (removed). The listing cache is format 2, keyed `source#stable|pre|draft` or `source@tag#dev`; a file without that format number is ignored and rewritten. `list --draft` widens installed rows only: a registry row would almost never have push access and would turn into `?`. A pinned package still shows no update in `list`; that stays a display rule.

Tests: `listing::tests` (a dev rebuild by date and by digest, the dev lookup key and kept assets, draft/pre/stable cache keys, a draft install offered its publication, an unasked prerelease, an older cache format set aside); `tests/draft.rs` (`releases --draft` with and without a token, `info --releases --draft`, a draft install listing drafts by itself, `list` and `list --draft` agreeing with `outdated`); `tests/dev.rs` (a republish shows as an update in `list`, a plugin source refuses `releases --draft`). 1156 tests green. Binary in a scratch root: `neovim/neovim@nightly` lists as `nightly (dev)` with `latest` `nightly`, and as `(update available)` once its recorded build was made older; `releases neovim --draft` without a token stops naming the token variables; `info --releases --draft` warns with the same message and still prints the details. Not checked: a real draft (no repository with a draft and a push token at hand), and `outdated` against GitHub in that run (anonymous rate limit, HTTP 403).
