# Ideas

- `BinSpec` (`bin = [{...}]` entries) and `AssetSelector` (`[asset]`) lack `#[serde(deny_unknown_fields)]`, so a misspelt key there is silently ignored, contradicting `docs/MANIFESTS.md` ("unknown keys are an error"). Found by M14. Fixing it changes behaviour (a manifest with a typo stops loading), so it would be a breaking change. Needs creator approval before it becomes a task.

## Localisation of the desktop apps

Deferred by the creator (2026-10-01, R11 open decision 7): the desktop apps ship in English only for now. When localisation is wanted, each app keeps its native format (String Catalog, `.resw`, gettext PO) and one Weblate project translates all three (`docs/research-desktop-platforms.md`, section 2, "Strings"). The first step was planned as R11's task D6, "String keys and glossary for three apps":

Three apps with three native string formats (String Catalog, `.resw`, gettext) will translate the same phrases differently unless the keys and terms are agreed once. Research: section 2, "Strings".

Done when a short convention for string keys and an English glossary of ketch's terms (package, source, pin, hold, store, link) are in the desktop docs, and each app's localisation task points at them.

## Linux tray

Deferred by the creator (2026-10-01, R11 open decision 8): the Linux app has no tray and relies on the Background portal. The option kept for later is a StatusNotifierItem through `libayatana-appindicator-glib`, which is GPL-3.0 and so fits only the GPL build (`docs/research-desktop-platforms.md`, sections 1 and 3c).

## Review ideas (2026-10-12)

Improvements from the read-only whole-project review of `main` at `13a6694` whose bugs are B89–B138 in `plan.md`. Not approved; none is a task yet. Paths are under `crates/ketch-core/src/` unless shown otherwise.

### Install pipeline and platforms

- Regression tests for rollback → upgrade → prune, and for an upgrade into a retained version whose placement fails (B90).
- `install.rs` `remove_package_dir`: take `&State`, so no front end can remove a directory a recorded package owns.
- `src/cmd/pkg.rs` `uninstall`: name the orphan folder and confirm unless `--yes`, instead of removing it silently.
- `self_update.rs` `remove_root_at`: tests for a root equal to home spelled differently (case, trailing component, symlink) and for a shared-prefix root.
- `state.rs` `Lock`: replace the pid-file protocol with an OS advisory lock and add a multi-process stress test (B109).
- `hooks.rs` / `install.rs` `recorded_hooks`: uninstall and rollback run the hooks snapshotted in `state.json`; re-read the user's manifest when it still exists, so a deleted hook does not run.
- `platform/unix.rs` `still_placed` (`CopiedApp`): record a bundle identifier or `Info.plist` hash, so a vendor copy of `/Applications/X.app` the user put back is not deleted on uninstall.
- `install.rs` `batch`: dedup after resolve by manifest name, so `ripgrep` and `BurntSushi/ripgrep` in one command are not prepared, committed and hooked twice.
- `platform/*` `move_into_store`: fold the three near-identical copies and their finalize/restore helpers into one (gives B104 and B123 one place to fix; related to R12, R18).
- `lockfile.rs` `validate`: reject `local:` (and maybe plugin schemes) in a shared lockfile, per the module's "lock never chooses a path" rule.
- `process.rs` `offer_to_stop`: show the matched process name and command line, not just the pid.
- `install.rs` `commit`: document in `docs/MANIFESTS.md` that a `before_*` hook's side effects remain when placement then fails.
- `platform/windows.rs` `copy_binary`: only the `.exe` is copied into `bin`, so releases with sibling DLLs or exe-relative resources break; use a shim/launcher, or at least warn when the exe's directory has `.dll` files.
- `platform/unix.rs` `link_binary`: warn when a new link name (`git`, `ls`) shadows a binary already on PATH.

### Untrusted input

- `trust.rs` `gpg_check`: accept only Binary and Text signature types, so a key, revocation or timestamp signature from the trusted key cannot count as a signature over the asset.
- `registry.rs` `candidate_package_dirs`: use `symlink_metadata` and skip package folders that are symlinks in the fetched tree.
- `extract/mod.rs` `unwrap_single_dir`: `path().is_dir()` follows symlinks; use `symlink_metadata` so a lone link is never taken for the wrapper dir.
- `push.rs` `file()`: handle a directory response from the contents API explicitly instead of `items.pop()`.
- `src/cmd/import.rs:113`: the dry-run prints rendered TOML through `ui::out` without `ui::printable`/`changelog::sanitize`.
- `config.rs` `sanitize_component`: also reject zero-width and other invisible format characters (U+200B–200F, U+FEFF, …), not only the bidi controls, against look-alike names.
- `changelog.rs` `find_file`: check that the canonical path stays inside the package prefix.

### CLI, shell, logs

- `src/ui.rs:1207`, `listing.rs:578`: measure column widths with `unicode-width`, not `chars().count()`, so CJK and emoji names align.
- `src/cmd/pkg.rs:544`: `pkg.sha256.get(..12)` instead of `&pkg.sha256[..12]`, so a short or corrupt state entry cannot panic.
- `shell.rs:685` `windows_path_key`: fold full Unicode case, not only ASCII, so non-ASCII PATH entries dedupe.
- `log.rs:266`, `config.rs`: create the log file and `config.toml` with mode 0600 on Unix, since `config.toml` may hold `github_token`.
- `src/cmd/system.rs:278` `plan_lines`: "{root} and everything in it" overstates what is removed; match the wording to the named entries.
- `src/cmd/system.rs:262` `chosen()`: `dedup()` without `sort()` leaves non-adjacent duplicates.
- `src/complete.rs:125`: `__complete registry` parses every registry manifest on each TAB; cache the names next to `registry.meta.toml`.
- `src/cmd/registry.rs:164-166`: pass `cfg.registry` through `changelog::sanitize` in the warning, as the other `status` lines do.

### FFI, C ABI and desktop apps

- `crates/ketch-capi`: build the shipped `.so` with the `ffi` profile; `profile.release` has `panic = "abort"`, which turns `respond`'s `catch_unwind` into a process abort.
- `crates/ketch-capi/src/abi.rs`: make `KetchCore` reference-counted (`ketch_core_ref`/`unref`, vapi `ref_function`), so a Vala app cannot free it while a worker call runs.
- `crates/ketch-capi/Cargo.toml`: the cdylib re-exports every `uniffi_*` symbol; add a version script or exported-symbols list.
- `crates/ketch-ffi/src/records.rs`: `#[serde(deny_unknown_fields)]` on `InstallOptions` for the C API, so a misspelt option is an error.
- `crates/ketch-ffi/src/lib.rs`: `rollback` and `prune` (and `ketch_rollback`/`ketch_prune`) take no cancel token, unlike the other mutating calls.
- `crates/ketch-ffi/src/lib.rs:624-645`: read-only calls (`root`, `config`, `installed`) run `ensure_dirs` and `log::init`, writing a log header on every UI poll; add a light path for reads.
- `log.rs:165`: the global `SINK` lets two `KetchCore`s on different roots, or a log-level change, retarget each other's log mid-operation.
- `desktop/macos/Ketch/Core/KetchCoreProtocol.swift:91-94`: add `stopProcesses` to the Swift `Decider` (Windows has it), so `upgrade-stops-processes` replays on macOS.
- `desktop/macos/Ketch/Core/FakeKetchCore.swift:269, 304`: a declined binary choice throws `cancelled` in the fake, the real core fails with an ambiguity error; make the fake and the contract agree.
- `crates/ketch-capi/tests/capi.vala`: exercise `ChooseFunc`/`StopFunc` and a multi-package batch with concurrent event callbacks.
- `desktop/windows/Ketch.AppCore/KetchStore.cs:414`: `Changed(Activity)` fires for every progress chunk; throttle it.
- `desktop/design/build.mjs:713`: the `^---\n` front-matter regex fails on a CRLF checkout; accept `\r?\n`.

### Installers, CI, release

- `dist-workspace.toml`: turn on `github-attestations` (or a sigstore signature); `SHA256SUMS` comes from the same release as the tarball, so it proves integrity, not provenance. The installers and `self upgrade` could verify it, since ketch already depends on sigstore.
- `install.sh:177-238`: `--proto '=https' --tlsv1.2` for curl, `--https-only` for wget.
- `install.sh:65-79`: a clear error when `--version`, `--root` or `--install-dir` has no value, instead of "unbound variable".
- `install.ps1:224-256`: the `KETCH_INSTALL_RELEASE_DIR` hook (fake `v9.9.9`) has no test left; remove it or bring the test back.
- `install.ps1`: `$ProgressPreference = 'SilentlyContinue'`; `Invoke-WebRequest` on 5.1 is very slow with the progress bar.
- `.github/workflows/ci.yml:102, 277, 286, 327`, `tap.yml:303`: pin `actions/checkout@v7` and `actions/setup-node@v7` to a SHA like the other actions, with `persist-credentials: false`.
- `.github/workflows/tap.yml:333`: the tap token sits in `tap/.git/config` while the cask postflight runs the new ketch; pass it via `http.extraheader` only for the push, or push from a separate job.
- `.github/workflows/release-ci-alert.yml:219`: `license-check` names a workflow that no longer exists.
- `.github/build-setup.yml`: the release rust-cache and mise cache are saved on tag refs, which no later tag can restore; drop the save or warm the cache from a `main` job.
- `scripts/site-build.sh:24`: `npx --yes pagefind@1` pulls an unpinned major; pin it in `package.json`. The `pages.yml` it and `site-install-hugo.sh` mention is gone, so both scripts may be dead.
- CI: `cargo deny check advisories` with a committed `deny.toml` (B138).
- Docs drift: AGENTS.md says `just setup` writes a gitignored `.cargo/config.toml` (it now writes `config.local.toml`); AGENTS.md's macOS app and `scripts/xcframework.sh` rows and the Justfile xcframework comment say arm64 + x86_64, but the builds are arm64 only.
- `scripts/release.sh:72`: bumping from a tagged `X.Y.Z-pre` skips `X.Y.Z` itself; matters only if prerelease versions are ever used.
