# K963: correctness and security audit

Reviewed baseline: `f83a678e` (`origin/main`), 2026-10-22. Changes are isolated
on `codex/audit-20261022-security`; the main checkout's existing `.gitignore`
change was not touched. The fixes are being submitted for pull-request review.
No release or dependency upgrade was made.

## Scope

Inventoried all 886 tracked files, including 131 Rust, 36 Swift, 35 C#, 12 Vala,
18 shell, and 17 YAML files. Performed targeted source review of checksum
selection, archive paths and links, changelog reads, HTTP credentials, plugin
execution, state/installation boundaries, C ABI ownership, desktop stores,
installers, and workflows. Compared findings with `plan.md` and `ideas.md`;
older entries are not reliable evidence that a defect remains unfixed.

This is a broad scan with focused manual review, not a claim that every line
was audited or that all vulnerabilities are absent. Platform execution coverage
and dependency findings are listed below. Repository links refer to this
baseline plus the K963 patch; external advisory sources were checked on 2026-10-22.

## Fixed defects

| Priority | Defect and consequence | Change and evidence |
| --- | --- | --- |
| P1 | Changelog discovery followed parent directory symlinks outside the installed prefix. A regular `docs/CHANGELOG.md` behind an external `docs` link passed the leaf-only check, exposing external content through CLI/FFI reads. This requires a payload tree containing such a link; it is not a demonstrated bypass of the archive extractor's link validation. | Reuse `extract::resolves_inside` before searching or enumerating directories. Tests cover external links at four search locations, an internal directory link, and the real CLI against an installed scratch package. [Code](../../crates/ketch-core/src/changelog.rs), [CLI regression](../../tests/query.rs). |
| P2 | A checksum sidecar with several unrelated entries fell back to its first hash. Conflicting entries removed by the aggregate parser were also resurrected by that fallback, or replaced with the sole unrelated surviving hash. | Allow unnamed fallback only for a single source entry. Bare hashes must occupy the entire entry. Two negative regressions failed before the fix; named-target and single-entry compatibility tests pass. [Code](../../crates/ketch-core/src/source/github.rs). |
| P2 | Checksum filenames were split on every whitespace character, so `tool archive.zip` became `tool`. This could miss the intended asset or bind the checksum to a different asset name. | Split the digest from the remaining filename once. Regression proves both preservation of the spaced name and absence of the truncated key. [Code](../../crates/ketch-core/src/source/github.rs). |
| P2 | `unwrap_single_dir` followed a single directory symlink and selected it as the payload wrapper. Moving a relative link changes its referent; callers could also select an external directory. | Use `DirEntry::file_type` without following symlinks. Regressions cover a self-link and an external link; real directories and bundles retain existing behavior. [Code](../../crates/ketch-core/src/extract/mod.rs). |
| P2 | Eight CI calls fetched `setup-rust` from mutable `main`; two new call sites failed the repository's workflow linter. | Pin all eight calls to the observed `pyrlyn/ci` main HEAD, `8626a53acafb7bd77db1962536208e549e97bd40`. This freezes the currently selected revision. `jactionlint` now exits successfully with the existing baseline. [Workflow](../../.github/workflows/ci.yml), [pinned source](https://github.com/pyrlyn/ci/tree/8626a53acafb7bd77db1962536208e549e97bd40/.github/actions/setup-rust). |
| P1 | The macOS app failed to compile: importing the generated class `KetchCore` shadowed its module, so `KetchCore.Package`, `KetchCore.KetchCore` and other qualified names resolved as members of the class. | Isolate the class import behind a local `NativeKetchCore` alias and use scoped imports in the adapter. Keep generated bindings, package/module names and the Swift minimum unchanged. The actual Xcode build reproduces the failure and verifies the repair. [Adapter](../../desktop/macos/Ketch/Core/LiveKetchCore.swift), [alias](../../desktop/macos/Ketch/Core/NativeKetchCore.swift). |

The small refactor shares checksum line filtering between aggregate and sidecar
parsing. The misplaced wrapper documentation was moved from `is_bundle_name`
to `unwrap_single_dir`. No public API or dependency was added. Reuse review of
the repository and sibling shared crates found the existing path guard suitable;
no shared checksum-list parser was found, so parsing stays with the GitHub source.

## Dependency findings retained by user decision

The user explicitly chose to document the proposed TLS dependency updates only;
the versions below remain unchanged. No security exceptions were added to make
the scans pass.

`cargo-deny 0.20.2 check advisories` fails with five vulnerability advisories and
one unmaintained dependency. Do not treat the successful unit tests as a clean
security scan. The existing RSA exception in `deny.toml` was not broadened.

| Priority | Finding | Proposed action / reachability |
| --- | --- | --- |
| P1 | `rustls-webpki 0.103.15`: [RUSTSEC-2026-0382](https://rustsec.org/advisories/RUSTSEC-2026-0382), distrusted emSign intermediates can be accepted. | Proposed lockfile update: `0.103.16`. Reachable through the TLS stack used by `ureq`, `octocrab` and `sigstore`. Deferred by the user. |
| P1 | `webpki-roots 1.0.9`: [RUSTSEC-2026-0381](https://rustsec.org/advisories/RUSTSEC-2026-0381), bundled root enables paths through distrusted emSign intermediates. | Proposed lockfile update: `1.0.10`. The `0.26.11` compatibility package re-exports the affected 1.x roots; it is not sufficient protection. Deferred by the user. |
| P2 | `zip 8.6.0`: [RUSTSEC-2026-0367](https://rustsec.org/advisories/RUSTSEC-2026-0367), [0368](https://rustsec.org/advisories/RUSTSEC-2026-0368), [0369](https://rustsec.org/advisories/RUSTSEC-2026-0369). Fixed upstream in `9.0.0`. | The reported high-level extraction APIs are not used by ketch: `unpack_zip` reads members itself, validates targets, and masks permission bits. This reduces reachability of these specific advisories; the scanner still fails. Evaluate a separately approved major-version migration, with existing archive security tests. [Extraction code](../../crates/ketch-core/src/extract/archive.rs). |
| P3 | `basic-toml 0.1.10` is unmaintained: [RUSTSEC-2026-0374](https://rustsec.org/advisories/RUSTSEC-2026-0374). | Transitive through Askama/UniFFI binding generation and workspace feature unification. Follow the upstream replacement; no patched version is offered by this advisory. |

`npm audit --package-lock-only --json` reports five high-severity package entries,
representing two advisory roots in development tooling, not five independent
runtime vulnerabilities:

- `braces <=3.0.3`: nested-pattern stack exhaustion,
  [GHSA-vfj7-8cjw-p6xm](https://github.com/advisories/GHSA-vfj7-8cjw-p6xm),
  propagated through `micromatch`, `@parcel/watcher`, and `@tailwindcss/cli`.
  npm proposes downgrading the direct CLI to 4.3.0; do not apply that blindly.
- `source-map-js <1.2.2`: indexed-source-map denial of service,
  [GHSA-68fv-2mgg-jv7q](https://github.com/advisories/GHSA-68fv-2mgg-jv7q).
  Evaluate a targeted approved lockfile update.

Sources for dependency versions and paths: [Cargo.lock](../../Cargo.lock),
[package-lock.json](../../package-lock.json), and the scanner outputs preserved
under `/tmp/ketch-audit-advisories.log` and `/tmp/ketch-audit-npm.json` for this session.

## Validation

| Check | Result |
| --- | --- |
| Baseline nextest, workspace/all targets/locked | 1309 passed. |
| New regressions against the old implementation | Six unit tests failed as expected: two sidecar, one spaced filename, two wrapper-link, one changelog-directory escape. |
| Final nextest, workspace/all targets/locked | 1318 passed, zero nextest skips; nine new tests include a real CLI install/read regression. Optional environment-gated test bodies can still return early. |
| `cargo clippy --workspace --all-targets --locked -- -D warnings` | Passed. |
| `cargo fmt --all --check`, `git diff --check` | Passed. |
| `cargo check --locked -p ketch-core --no-default-features` | Passed. |
| Windows AppCore tests on .NET 10/macOS arm64 | 56 passed. This does not build or execute WinUI. |
| Workflow lint | Passed after pinning setup-rust. 51 findings remain suppressed by the existing baseline; 14 baseline entries are stale. |
| Shell syntax, release scripts/workflow fixtures, docs-i18n, commit-msg breaking guard, cask smoke, installer truncation | Passed individually after the aggregate gate stopped. |
| Site Python tests | Two passed. |
| macOS app unit/UI tests | After repairing the import collision: 83 Swift Testing unit tests and two XCTest UI tests passed; Xcode reports `TEST SUCCEEDED`. |
| Swift strict formatting lint | Passed for app, unit tests and UI tests; fixed two existing formatting violations in test files. |
| Rust/npm dependency scans | Failed as detailed above; versions were not changed. |
| Aggregate `just lint-shell` | Fails at `tests/crate-version.sh`: workspace version `0.0.1`, latest CLI tag `v0.10.0`. Existing baseline issue, related to K908. No version or release history was rewritten. |

A conventional `cargo test -p ketch-core --lib` run passed 895 tests but failed
`process::tests::yes_stops_the_child_holding_the_file` once. The same test passed
in both baseline and final nextest runs. This is an observed intermittent failure,
not a diagnosed root cause; no speculative process-management change was made.

Linux GTK/Vala and Windows-native behavior were reviewed statically but not run
on this macOS host. No fuzz campaign, full feature matrix, signed distribution
build, or live GitHub Actions run was performed. No remote systems were mutated.

## Follow-up findings

- Linux `Store.search` has no generation check, unlike the macOS and Windows
  stores. An older request can overwrite a newer search result. `operate` also
  silently returns when another operation is active. These are source-level
  candidates requiring Vala regression tests on the native toolchain; they are
  not included among the fixes proven here. [Linux store](../../desktop/linux/src/store.vala).
- The prior B98 PowerShell session-exit finding is already fixed by the scoped
  scriptblock and `Stop-Install` in [install.ps1](../../install.ps1), despite its
  older status in `plan.md`. Avoid repeating that fix.
- Existing workflow baseline suppressions and advisory exceptions deserve an
  explicit follow-up review; passing a baselined linter does not imply no findings.

## Feature proposals (not implementation tasks)

1. **Verification explanation.** Extend package information with the exact asset,
   checksum source, ambiguity/missing-checksum reason, signature identity and
   policy result. A user can distinguish a successful hash comparison from
   authenticated provenance. Reuse `trust`, source checksums and structured events.
2. **Reviewable install transaction.** Produce one machine-readable preview of
   downloads, replaced links, hooks and retained versions before mutation, consumed
   by both CLI and desktop confirmation screens. Reuse the existing resolution
   trace and dry-run paths rather than building a second resolver.
3. **Integrity diagnostics for installed trees.** An opt-in doctor check can
   identify escaping links and changed installed files, report exact paths, and
   propose reinstalling from a pinned lockfile. Keep detection separate from repair.
4. **Cross-desktop behavioral contract tests.** Add shared delayed-search,
   simultaneous-operation and cancellation scenarios, so a fix such as rejecting
   stale search replies reaches Linux, Windows and macOS together.

For maintenance, the already-proposed scheduled RustSec/npm advisory gate is now
backed by concrete findings. Add it only with explicit exception reasons and a
triage policy, not by ignoring every currently failing advisory.
