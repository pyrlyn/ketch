---
id: M19
title: "Draft releases: `--draft`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`ListOpts` gains `include_draft`; the GitHub source keeps drafts when it is set and downloads their assets from the API asset URL with `Accept: application/octet-stream` and the token. `install`, `upgrade`, `outdated` and `list` take `--draft`; `pkg@tag` finds a draft by its intended tag when `--draft` is given. Selection keeps refusing drafts unless asked (`drafts_are_never_selected` stays true for the default). No token, or a token without push access, gives an error naming the reason. Channel `draft`; a published draft upgrades to the release, a deleted one warns. Plugins never receive drafts. Done means the above with unit tests on selection and an end-to-end case through the fixture source.

Execution plan:

1. `crates/ketch-core/src/source/mod.rs`: `ListOpts::include_draft`; `select_release` keeps refusing drafts unless it is set; `Source::supports_drafts`, false by default.
2. `crates/ketch-core/src/source/github.rs`: with `include_draft`, check the token first (none: error naming the token variables; `permissions.push` false on the repository: error naming push access), skip the `/releases/latest` and `/releases/tags/` fast paths (neither returns drafts) and walk the listing with drafts kept. A draft's assets download from their API URL, kept only when it is on the API host, so the token never leaves it; its checksum sidecars are read the same way.
3. `crates/ketch-core/src/model.rs`: `Channel::Draft`; `InstalledPackage::draft` records that the installed release was a draft.
4. `crates/ketch-core/src/install.rs`: `InstallRequest::draft` (implies prereleases), refused for a source without drafts; the channel and the draft flag recorded by `commit`; `installed_list_opts` and `upgrade_request` follow `draft`; one `is_upgrade` for `upgrade` and `outdated`: a published draft under the same tag is an upgrade, a vanished draft is a warning.
5. `src/cli.rs`, `src/cmd/pkg.rs`, `src/cmd/query.rs`: `--draft` on `install`, `upgrade` and `outdated`. `list --draft` comes with M21, which adds `--pre` to `list` and reworks its lookup; until then `list` looks up draft-channel packages without drafts.
6. Tests: selection unit tests (default still refuses drafts), GitHub conversion and access tests, channel tests; `tests/draft.rs` through a stand-in GitHub API (the `import.rs` server moved into `tests/support`): no token, no push access, a draft install with the token only on API requests, published and deleted drafts.
7. Docs: `docs/COMMANDS.md` in English, `docs/ru` and `docs/uk`; `bash tests/docs-i18n.sh`.
8. Verify: fmt, clippy `-D warnings`, nextest, and the binary against a scratch root (a real draft needs a push token; reported if none is available).

Status: done (Claude Code / opus-5.5). `select_release` still refuses drafts unless `ListOpts::include_draft` is set, so `drafts_are_never_selected` holds for every default path. `--draft` implies prereleases. `install::is_upgrade` is now the one rule behind `upgrade` and `outdated`. `list` keeps looking up `latest` without drafts until M21 adds `--pre`/`--draft` to it. Not done: a real draft install, since no repository the available token can push to has a draft, and creating one was not approved; the access check was run for real (no token: refused by name; token without push to `sharkdp/fd`: refused by name; token with push to `pyrlyn/ketch`: installed `v0.10.0` on channel `draft`, `outdated` checks it with the token and warns without one).

Tests: `source::tests` (a draft only when asked, by latest and by exact tag), `source::github::tests` (draft assets from the API host only, published assets keep the public URL, drafts listed only when asked and with a tag, no token named before any request), `model::tests` and `install::tests` (channel, list options, upgrade request, published and deleted drafts), and `tests/draft.rs` through `tests/support/remote.rs`, the stand-in server moved out of `tests/import.rs`. 1116 tests pass; clippy `-D warnings` and fmt clean.
