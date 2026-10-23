# Improvement review, October 2026

A read-only review of `main` at `57d8a67` (2026-10-21), looking for what would
make ketch better as code and as a product. The tasks it produced are K902–K962
in `plan.md`; this note keeps the evidence behind them. Paths are under
`crates/ketch-core/src/` unless shown otherwise, and line numbers are as of
`57d8a67`. Items already in `ideas.md` or the review tables in `plan.md` are
not repeated.

## State of the project

| Fact | Source |
| --- | --- |
| No green `ci` run on `main` since 2026-10-18; run 39601341616 fails in `package (ubuntu)`, `package (windows)`, `powershell`, `ketch-cs`, `ketch-win-app` | `gh run list -R pyrlyn/ketch --branch main --workflow ci.yml`, checked 2026-10-21 |
| `platform/linux.rs:259` sets `Placement.version`, which `platform/mod.rs` no longer has (removed in #440) | tree at `57d8a67` |
| 30 of the last 40 merged pull requests had non-green checks; ruleset `protect-main` allows an admin bypass | `gh pr list`, `gh api repos/pyrlyn/ketch/rulesets`, checked 2026-10-21 |
| Every macOS job runs on one self-hosted runner (`MACOS_RUNNER=xcode-27-local`); queue waits of 165–284 minutes | `gh api orgs/pyrlyn/actions/runners`, run 39434070532 |
| `cargo hakari generate --diff` failed in 27 runs in 20 days | `gh run list`, checked 2026-10-21 |
| `tests/tasks.rs` failed at `57d8a67`: finished rows were left in the `plan.md` table | `cargo test --test tasks` |
| `ketch-registry` has 7 packages; `builtin.toml` has 2 | `gh api repos/pyrlyn/ketch-registry/git/trees/HEAD?recursive=1`, checked 2026-10-21 |
| GitHub community profile health is 50%: no issue or PR templates, code of conduct or security policy; Discussions off | `gh api repos/pyrlyn/ketch/community/profile`, checked 2026-10-21 |
| `cargo nextest` is not pinned in `mise.toml`; the shim fails locally | `mise.toml` at `57d8a67` |

## Core architecture and code

- **State compatibility.** `STATE_VERSION` (`state.rs:27`) has stayed 1 while
  fields were added with `serde(default)`. The stored manifest snapshot has
  `deny_unknown_fields` (`model.rs:840`), and the stored enums `ManifestOrigin`,
  `LocalKind`, `LinkRole` and `Channel` have no fallback. The CLI, the Homebrew
  bootstrap and each desktop app link their own core and share `~/.ketch`
  (K907).
- **Two cancel tokens.** `Ctx::cancel` (`report.rs:335`) and
  `InstallRequest::cancel` (`install.rs:73`). `ketch-ffi`'s `ctx()`
  (`crates/ketch-ffi/src/lib.rs:124-130`) never sets the caller's token, so
  `fill_latest` during a desktop upgrade cannot be cancelled (K925).
- **Three upgrade planners.** `src/cmd/pkg.rs:259-425`,
  `src/cmd/query.rs:288-392` and `listing::fill_latest` via
  `crates/ketch-ffi/src/lib.rs:703-717` (K926).
- **Errors.** `error.rs:195-231` hints name CLI flags, and the FFI appends them
  to desktop messages; several conflicts map to `KetchError::Other` (K927).
- **Plugins.** `SourceRegistry::load` (`source/mod.rs:306-324`) probes every
  plugin, one after another with a 30-second timeout (`source/plugin.rs:43`),
  on every command (K928).
- **Platform seams.** 14 direct `platform::host()` calls, 7 of them in
  `install.rs`; `platform/unix.rs` and `platform/windows.rs` share 71 six-line
  windows (K929, K930).
- **Dependencies.** `ketch-core --no-default-features` resolves 222 crates and
  all features 437 (`cargo tree`); the `trust` feature adds 177, mostly sigstore
  0.14's network clients; workspace-hack makes the CLI compile uniffi (K917,
  K957). `[profile.dist]` inherits `dev` (`Cargo.toml:131-132`, #279) (K958).
- **Size.** `install.rs` about 2,000 lines of code, `src/ui.rs` 1,365,
  `shell.rs` 1,504, `model.rs` about 1,600; 167 of 510 public core items are
  never named outside the core (K953, K954).

## Product and usability

| Competitor fact | Source (checked 2026-10-21) |
| --- | --- |
| Homebrew core has a `ketch` formula (1broseidon/ketch) | https://formulae.brew.sh/api/formula/ketch.json |
| aqua's registry lists about 2,333 packages, about 2,000 of type `github_release`, MIT | https://github.com/aquaproj/aqua-registry (v4.575.0) |
| mise's registry maps about 1,015 short names to backends, MIT | https://github.com/jdx/mise/tree/main/registry |
| Unauthenticated REST calls are limited to 60 an hour, authenticated to 5,000 | https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api |
| mise reads the GitHub CLI's token | https://mise.jdx.dev/dev-tools/github-tokens.html |
| dra falls back to the GitHub CLI token | https://github.com/devmatteini/dra |
| mise locks a URL and checksum per platform | https://mise.jdx.dev/dev-tools/mise-lock.html |
| mise has `gitlab:` and `forgejo:` backends | https://mise.jdx.dev/dev-tools/backends/ |
| GitHub exposes artifact attestations by digest | https://docs.github.com/en/rest/repos/attestations |
| mise, aqua and pkgx ship GitHub Actions | https://github.com/jdx/mise-action, https://github.com/aquaproj/aqua-installer, https://github.com/pkgxdev/pkgx |
| pkgx runs tools without installing them | https://github.com/pkgxdev/pkgx |
| AM and zap manage AppImages | https://github.com/ivan-hc/AM, https://github.com/srevinsaju/zap |
| Homebrew analytics are on unless switched off | https://docs.brew.sh/Analytics |

Positioning that follows: mise and aqua own per-project versions, and ketch
should not chase them. eget, dra, ubi and stew download, but have no store,
rollback or verified upgrade. Homebrew, Scoop and winget need a formula first.
What ketch can own is any GitHub release, explained (`why`), verified and
reversible, on three OSes, with GUI apps and no telemetry. Two things stand in
the way: a 7-package registry (K905, K910, K911) and the name collision (K906).

## CI and release

- Four macOS jobs (about 85 job-minutes per pull request) share one runner;
  `ketch-ffi` and `macos-app` both build the XCFramework (`ci.yml:236-238`,
  `:291-292`); `ketch-cs` and `ketch-win-app` both run `scripts/csharp.sh`
  (`ci.yml:320-321`, `:389-391`) (K904, K941).
- Versioning restarted at 0.0.1 (#359); `self_update.rs:502` refuses
  `to <= from`; the cask is stuck at 0.10.0 (issue #367) (K908).
- Neither desktop release workflow has ever run; `SUPublicEDKey` is a
  placeholder in `desktop/macos/Ketch/Info.plist` (K944).
- `fuzz/` has 11 targets that CI only type-checks (K945); there is no
  `mise.lock` (K943); Renovate and Dependabot overlap, and CodeRabbit and
  release-plz are inert (K942).

## Desktop apps

- The macOS app runs on `LiveKetchCore` (R9 shipped) and Windows on
  `FfiKetchCore` (D12, #386); Linux is still on the fake (D17).
- The apps use 8 of the 19 calls `ketch-ffi` exports; `pin`, `unpin`,
  `rollback`, `prune`, `history`, `info`, `path_status`, `path_install`,
  `doctor_fix` and `registry_refresh` have no UI (K923, K946).
- Doctor's PATH check reads the current process's PATH (`shell.rs:302-327`),
  so an app started from Finder or the Start menu always warns (K922).
- Store fixes landed unevenly: B130 in two apps, B129 and B131 in one each,
  none in Linux (K921, K947); three fakes and decoders, about 2,000 lines, have
  drifted from the real core (K951).
- What blocks a first release: on macOS, the Apple and Sparkle secrets and the
  real `SUPublicEDKey`; on Windows, the certificate secrets, an update channel
  (K950) and the Windows App SDK licence question; on Linux, D17–D19.
