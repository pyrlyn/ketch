# ketch
https://github.com/pyrlyn/ketch
Catch releases straight from GitHub — a package manager for GitHub-released binaries and apps.

## Cloud review findings (2026-10-08)

New bugs, dead code and moves from a read-only Cursor cloud review of `main` at `077eca5` (agent `bc-89f78a26-1501-5547-96b8-3ef0603e653a`; full report: `cloud/ketch.md` in the private `listepo/roadmap` repo). Bugs take B75–B88; dead code and moves take R14–R19. Ordered P0, P1, P2. **confirmed** means seen in the tree; **suspected** means plausible from the code but not proven. Paths are under `crates/ketch-core/src/` unless shown otherwise; line numbers are as of the review. None of these is in the task table yet: to take one, add its row and a card.

| ID | Priority | Kind | Status | Where | Fix |
| --- | --- | --- | --- | --- | --- |
| B75 | P1 | bug | fixed | `scripts/cask.sh:81-86`; tap run 37154989737 | The cask postflight `run /bin/sh` does not pass a GitHub token: the step had `GITHUB_TOKEN`/`KETCH_GITHUB_TOKEN` and still hit `API rate limit exceeded`. Forward the token into the cask `run` env, or smoke-test `ketch self install` outside Homebrew's isolated postflight. |
| B76 | P1 | bug | fixed | `registry.rs:117-120` | After the live registry is moved aside, the rollback is `let _ = rename(aside, registry_dir)`; if it fails, `registry_dir` is left empty. Surface the failure and copy-restore. |
| B77 | P1 | bug | fixed | `self_update.rs:932-937` | Uninstall runs `remove_file(exe)?` after packages are gone and state is saved; a busy Windows `.exe` aborts the rest. Warn and continue, like the other uninstall steps. |
| B79 | P1 | bug | fixed | `install.rs:691-729`; `platform/unix.rs:423`; `platform/windows.rs:502` | `place` swaps the store, then links; `ScopedDir` is off when `existing.prefix == store_dir`, so a failure after the swap leaves state pointing at a tree that is not on disk. Restore `.old` on error. |
| B80 | P2 | bug | fixed | `extract/archive.rs:397-399`; extractor order in `platform/linux.rs:62-66` | `TarXzExtractor` claims any `XZ_MAGIC` and is listed before `GzFileExtractor`; unlike gzip (`:508-509`), xz/bz2 never peek for ustar, so a lone `.xz` file fails as a tarball. Peek the decompressed head; add lone-file xz/bz2 extractors. |
| B81 | P2 | bug | fixed | `extract/archive.rs` `payload_mode` | Archive modes are masked to `0o777`, so setuid, setgid and sticky bits do not survive extraction. |
| B82 | P2 | bug | fixed | `source/github.rs` `parse_checksum_file` | A basename that maps to two different hashes is dropped, not overwritten by the later line. |
| B83 | P2 | bug | fixed | `source/github.rs` `sidecar_hex` | A sidecar checksum uses the line that names the asset, and a single unnamed entry only when the file is unambiguous. |
| B84 | P2 | bug | fixed | `http.rs:118-186` | Downloads stream with no maximum size. Add a configurable download/unpacked cap and abort over it. |
| B85 | P2 | bug | fixed | `registry.rs` `update`; `listing.rs` `ask_all` | Registry download uses the caller's `Cancel`, and `ask_all` stops between packages when it fires. |
| B86 | P2 | bug | fixed | `source/plugin.rs` `allowed_asset_url`, `plugin_fetch_headers` | ketch fetches a plugin asset only for `https` or loopback `http`. `Authorization` and hop-by-hop headers, including names listed by `Connection`, are dropped before the request. |
| B87 | P2 | bug | fixed | `source/local.rs` `sha256_tree` | The local tree hash framed only `path\0bytes`. It now frames kind, path, permission bits and length, and hashes a symlink's target. A lock from before this framing does not match until `ketch lock`. |
| B88 | P2 | bug | fixed | `process.rs` `terminate` | After a polite stop, ketch waits until the pid is gone, bounded by five seconds, and only then sends the forceful kill. The replace that follows no longer races a process that is still exiting. |
| R14 | P2 | dead code | confirmed | `scripts/b32_patch.py:8` | The script opens `src/self_update.rs`, which has moved, and nothing references it. Delete it. |
| R15 | P2 | dead code | fixed | root `Cargo.toml:61` (`dirs`); `manifest.rs:137-148` (`Resolver::aliases`); `model.rs:596-599` (`Release::asset`); `platform/mod.rs:49-50` (`Placement::version`); `model.rs:329-331` | Drop the unused root `dirs` dependency, `Resolver::aliases` (or wire it to `__complete`/search), `Release::asset` and the never-read `Placement::version`; remove the stale `allow(dead_code)` on `PackageSpec::raw` (read at `state.rs:162`). |
| R16 | P2 | dead code | fixed | `fuzz/seed.sh` | The seed script and its readme name `crates/ketch-core/src/builtin.toml`. |
| R17 | P2 | dead code | fixed | `shell.rs:561-1092` | 23 `cfg_attr(not(windows), allow(dead_code))` on the Windows PATH helpers. Put them in one `#[cfg(any(windows, test))]` module. |
| R18 | P2 | move | fixed | `platform/mod.rs` (`swap_into_store`) | Store swaps share one helper. macOS passes `ditto`; Linux and Windows pass `copy_tree`. |
| R19 | P2 | move | suspected | `crates/ketch-core/Cargo.toml:49-66` | `stats` + diesel, `push` + octocrab/tokio, `trust` + sigstore/pgp and the `extract` codecs are always in core, so every desktop/FFI link pays for them (issue #222). Make them optional crates or features. |

B78 is done: `uninstall_self` holds the install lock through the registry entries, the shell blocks and the root. See `done.md`.

B87 is done: `sha256_tree` frames kind, path, mode and length, and includes symlink targets. See `done.md`.

Already tracked here, not added again: `Http::has_token` with no callers is in R12; `clap`/`clap_complete` still in `ketch-core` (`Cargo.toml:19-20`, `shell.rs:37`, `model.rs:716-733`, issues #219/#220) is R5 step 5, not done yet.

Not added: the brand move is done (`brand/build.mjs` already uses `@pyrlyn/brand`); the `theme.js` and `cli-argv` fuzz moves were not verified by the agent.

## Bug and security review findings (2026-10-12)

New bugs and vulnerabilities from a read-only whole-project review of `main` at `13a6694` (Claude Code / opus-5.5, five parallel reviewers: install pipeline and platforms; untrusted input; CLI and shell; FFI, C ABI and desktop apps; installers, scripts, CI and dependencies). Items already listed above (B74–B88, R12, R14–R19) were left out. Bugs take B89–B138. Ordered P1, P2, P3. **confirmed** means traced in the tree; **suspected** means plausible from the code but not proven. Paths are under `crates/ketch-core/src/` unless shown otherwise; line numbers are as of the review. Improvements from the same review are in `ideas.md`. None of these is in the task table yet: to take one, add its row and a card.

| ID | Priority | Kind | Status | Where | Fix |
| --- | --- | --- | --- | --- | --- |
| B89 | P1 | vuln | fixed | `src/cmd/registry.rs:309-327` (`install_from_fixture`); `registry.rs` `check_tree` | `ketch registry validate --fixture` writes each registry manifest into the probe root's user manifest dir, so its origin becomes `User` and `hooks::allowed` runs its `[hooks]`. A registry PR with `after_install = "curl … \| sh"` gets shell on the validating machine or CI runner. Strip `hooks` before writing, and make `check_tree` reject any registry manifest with hooks. |
| B90 | P1 | bug | fixed | `install.rs:1553` (`retain_replaced`), `install.rs:1283` (`prune`), `install.rs:692` (`ScopedDir`) | `retain_replaced` drops only the old version's retained entry, never one whose prefix is the new current prefix. Rollback 1.0→0.9, upgrade back to 1.0, then `prune` (keep=1) deletes the live 1.0 store dir and every link dangles; a failed link in that upgrade also lets `ScopedDir` delete the restored copy. Also drop entries matching the new prefix/version, treat a retained prefix as in-place, and make `prune` never remove `pkg.prefix`. |
| B91 | P1 | bug | fixed | `src/cmd/pkg.rs:194-207`; `crates/ketch-ffi/src/lib.rs:352-356`; `install.rs:1641-1671` (`package_dir_candidate`, `remove_package_dir`) | A name `State::find` misses (exact, case-sensitive) is treated as a leftover and `store/<name>` is `remove_dir_all`'d without confirmation and ignoring `--dry-run`. On APFS or NTFS `ketch uninstall RipGrep` (or `ripgrep.` on Windows) resolves to the installed `store/ripgrep` and wipes it while state still lists it. Refuse when the on-disk entry name is not byte-equal to the name or any recorded prefix lies under it; confirm and honour `--dry-run`. |
| B92 | P1 | bug | fixed | `self_update.rs:1021-1046` (`remove_root_at`) | The only wipe guard is a lexical `cfg.root != $HOME`. With `--root ~/.local`, `KETCH_ROOT=/usr/local` or a home spelled differently (case, symlink), `self uninstall` runs `remove_dir_all` on that root's `bin/`, `cache/`, `logs/`, deleting other programs' files. Wipe named children only when the root carries a ketch marker; otherwise remove only recorded entries; canonicalize and fold case before comparing with home. |
| B93 | P1 | bug | fixed | `.github/workflows/bump.yml:53`; `release.yml:457`; `install.sh:173`; `install.ps1:229`; `source/github.rs:366`, `source/mod.rs:207` | Since #279 every CLI release is published as a prerelease, and `/releases/latest` skips prereleases. `install.sh`, `install.ps1`, `ketch self upgrade` and the cask (postflight and `livecheck :github_latest`) stay on v0.10.0 forever. Publish CLI releases as non-prerelease (keeping the dev-build title and notes), or teach every reader to list releases including prereleases. |
| B94 | P1 | bug | fixed | `install.ps1:310` | `Join-Path $TempDir 'ketch' $BinaryName` passes three positional arguments; Windows PowerShell 5.1, the shell `irm \| iex` runs in, has no `-AdditionalChildPath` and throws after extraction. CI only runs `pwsh ./install.ps1 --help` (`ci.yml:105-110`). Nest the calls and add a CI step that runs the script under `shell: powershell` past extraction. |
| B95 | P1 | bug | fixed | `shell.rs:688-730` (`read_user_path`, `write_user_path`); `install.ps1:105-119` | The user PATH is read expanded via `GetEnvironmentVariable('Path','User')` and written back as `REG_SZ`, so every `%USERPROFILE%`/`%JAVA_HOME%` entry is frozen; `read_user_path` also decodes stdout with `from_utf8_lossy` without the UTF-8 `OutputEncoding`, corrupting non-ASCII entries. Hits `path install/uninstall`, `doctor --fix`, `self uninstall` and the installer. Read and write `HKCU\Environment\Path` through `Microsoft.Win32.Registry` with `DoNotExpandEnvironmentNames`, keep the value kind, and go through the UTF-8 `powershell()` helper. |
| B96 | P2 | vuln | fixed | `.github/workflows/ci.yml:11-12, 55, 98, 307, 449` | Jobs only test `pull_request.draft != true`, which is true on `issue_comment`, and `hakari` has no `if`: any comment on any issue or PR runs the whole matrix on `main` with `secrets: inherit` and macOS minutes (run 38115907682). Comment runs share the `ci-refs/heads/main` concurrency group, so a queued one replaces a pending push-to-main run (38115904555). Gate every job but `review` on `github.event_name != 'issue_comment'`, or move `/review` into its own workflow. |
| B97 | P2 | vuln | fixed | `.github/workflows/release.yml:117-120`, produced by `scripts/dist-generate.sh:80-87` | `CODESIGN_CERTIFICATE`, its password and `GH_TOKEN` (with `contents: write`) are job-level env on all four build runners, so every dependency `build.rs` and proc-macro can read the Developer ID `.p12`. Patch the env so the certificate reaches only the macOS signing step, and drop `GH_TOKEN` where builds do not need it. |
| B98 | P2 | bug | confirmed | `install.ps1:3`, `:149-326` (every `exit`) | Under `irm … \| iex` the script runs in the caller's session: any error path's `exit 1` closes the user's PowerShell window before the message can be read, and `$ErrorActionPreference = 'Stop'` and script variables leak into the session. Wrap the body in a function or scriptblock, `throw`/`return` instead of `exit`, restore the preference. |
| B99 | P2 | vuln | fixed | `extract/archive.rs` ~604 (zip `io::copy`), 462-469 (tar `unpack_member`), ~643, ~659 (lone gz/xz/bz2) | No extractor bounds the bytes written or the number of entries; a small gzip/bzip2 bomb or a zip with millions of tiny entries fills the disk under the root. B84 caps only the download. One shared extraction budget (bytes and entries, ratio of compressed size plus an absolute ceiling) across every extractor. |
| B100 | P2 | vuln | fixed | `extract/archive.rs` `MAX_ZIP_LINK_TARGET` | A zip symlink target is read through `take(4097)` and refused when it is longer than 4 KiB. |
| B101 | P2 | bug | fixed | `extract/archive.rs:462-469`, `:420-423` | `payload_mode` is applied to directories as they are written. A `0o555` directory before its children fails extraction with EACCES (Nix and some reproducible tarballs; GNU tar copes); one after its children stays read-only, so `remove_any` (`platform/unix.rs:46`) fails on uninstall and upgrade and temp dirs leak. Apply directory modes after all members are written and always force `u+rwx` on directories. |
| B102 | P2 | bug | fixed | `extract/archive.rs:71-127` (`materialize_symlink_as_copy`, `copy_dir_contents`) | On Windows without symlink rights, a member `d -> .` or `sub/d -> ..` passes `check_link_target` and is materialised by copying its target into itself while `WalkDir` walks it: endless recursion or a full disk. Refuse to materialise a directory link whose target is an ancestor of the link, or snapshot the entry list first. |
| B103 | P2 | bug | fixed | `install.rs:262` (`prepare`), `install.rs:647` (`commit`); `lockfile.rs` plan | State is keyed by name and nothing compares `existing.source` with `manifest.source`. `ketch install otherowner/ripgrep --yes` silently replaces `BurntSushi/ripgrep`, takes over its `rg` link, runs update hooks and mixes retained versions from two sources; `ketch sync` plans a same-name, other-source lock entry as missing and does the same. Refuse in `prepare` when the sources differ and point at `--name`. |
| B104 | P2 | bug | fixed | `platform/unix.rs:131, 473`; `platform/macos.rs:263, 430`; `platform/windows.rs:404, 543` | Placement clears each occupied destination (old symlink, copied `/Applications/X.app`, Windows bin copy) right before writing the new one; on a later failure only the new links are unplaced and the store restored, so the old version's links and app copy are gone while state still records them. A `copy_tree` of a large `.app` failing on a full disk loses the working app and leaves an unrecorded half copy. Move occupied destinations aside and restore them on failure. Adjacent to B79, which covers only the store. |
| B105 | P2 | bug | fixed | `platform/windows.rs` (`retired_targets`) | A same-version reinstall compares a copied bin with `<store>.old`, so the copy ketch made is still recognised as ours. |
| B106 | P2 | bug | fixed | `model.rs` (`glob_match_path`); `platform/windows.rs`, `platform/unix.rs` | A manifest `bin.path` written with `/` (`bin/tool.exe`) was matched against `rel.to_string_lossy()`, which uses `\` on Windows, so any directory in `bin.path` failed. Both sides are folded to `/` before the glob. `extra::resolve_under` still refuses a backslash: `safe_member_path` treats one as an unsafe name, and `doc/rg.1` already resolves. |
| B107 | P2 | bug | fixed | `self_update.rs` (`record_self_docs`) | Self install and self upgrade save the new package before linking man pages and completions. A user-owned file at a doc destination is a warning, so `state.json` names the version that is on disk and a later upgrade does not repeat the failure. |
| B108 | P2 | bug | fixed | `process.rs:323-342` (`cmd_hits`) | A process counts as an occupant when its command line contains the path as a plain substring. Upgrading `rg` with `--yes` SIGTERMs, then SIGKILLs, a running `…/bin/rga`, `…/rg-helper`, or any editor or shell whose arguments mention the path (Linux, Windows). Match whole NUL-split arguments against exact canonical paths and prefer exe and open-fd evidence. Separate from B88. |
| B109 | P2 | vuln | fixed | `state.rs:235-276` (`Lock::acquire_path`) | `create_new` and the pid write are not atomic: a second process that reads the still-empty file calls it stale and renames the live lock away. Two processes that judged one dead pid stale can each rename, the second over the first's fresh lock. Both then run, and the first `Drop` deletes the other's lock. Write the pid to a temp file and link it into place without replace, treat an empty lock as busy unless old, re-read before renaming, or use an OS advisory lock. |
| B110 | P2 | vuln | fixed | `desktop/macos/Ketch/Store/KetchStore.swift` | Changelog Markdown keeps a `.link` run only when its scheme is `https`. Other schemes stay as text, so the default `openURL` action cannot open them. |
| B111 | P2 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`; `desktop/windows/Ketch.AppCore/FakeKetchCore.cs:131`; `crates/ketch-ffi/src/lib.rs:166-171` | Discover's shelf calls `search("")`. Both fakes return the whole registry; the real core returns `Other("nothing to search for")`, so with the live core (R9) Discover opens with an error and an empty shelf. No contract scenario covers it. Decide the semantics in `ketch-ffi` (or add `browse`) and add a scenario. |
| B112 | P2 | bug | suspected | `desktop/windows/Ketch.App/Pages/InstalledPage.xaml.cs:63-74`; `MainWindow.xaml.cs:85-86, 124-151` | Uninstall and Install buttons stay enabled while an operation runs, so a confirm `ContentDialog` can be open when the core asks a decider question; WinUI allows one dialog, `ShowAsync` throws inside the fire-and-forget `AskAsync`, and the core thread blocks in `done.Wait()` until Cancel. Disable those buttons while `IsRunning`; catch dialog failures and answer `null`/`false`. |
| B113 | P3 | vuln | fixed | `extract/mod.rs:81-136` (`safe_member_path`); `config.rs:471` (`sanitize_component`); `model.rs:1116` (`usable_file_name`) | DOS device names (`CON`, `NUL`, `AUX`, `PRN`, `COM1-9`, `LPT1-9`, also with an extension), names with a trailing dot or space, and `<>:"\|?*` pass. An archive member, bin, tag or package named `nul.txt` writes to a device, `foo.` collides with `foo`, a store dir can fail to create. Reject these case-insensitively per component on Windows. |
| B114 | P3 | vuln | fixed | `extract/archive.rs:304-331` (`check_link_target`); readers `extra.rs:197`, `changelog.rs` `find_file`, `platform/unix.rs:61, 227`, `platform/windows.rs` `payload_executable_entry` | Link targets are checked lexically, ignoring links created earlier: `L -> .` then `N -> L/L/../../..` passes but escapes. `resolve_under`, `find_file`, `ensure_executable` (chmod follows links) and `payload_executable_entry` (checks one hop) then follow it, so local files outside the store can be linked into bin, man or completion dirs and `chmod u+rx`'d. Canonicalize every link in `finish_deferred_links` and require it under the destination; canonicalize in `payload_executable_entry`. |
| B115 | P3 | vuln | fixed | `extract/macos.rs:175-199` (DMG `copy_volume`), `:210-237` (PKG) | DMG copy skips only top-level symlinks; `ditto` keeps nested absolute or escaping links, and PKG payloads get no link checks. The B114 readers then follow them outside the payload. After the copy, walk the tree with `symlink_metadata` and run every link through the containment check. |
| B116 | P3 | vuln | fixed | `source/plugin.rs:204-244` (`discover`) | Plugin discovery scans the plugin dir and every PATH entry, including ketch's own bin dir, and runs each `ketch-source-*` with `capabilities`. A client app that ships such a binary is executed by every later ketch command that loads sources. Discover only in the plugin dir, or exclude `cfg.bin_dir` and anything resolving into the store. |
| B117 | P3 | bug | fixed | `changelog.rs:~99` (`from_file`), `src/cmd/query.rs:589` | The client changelog is read with an unbounded `read_to_string(...)?`: a multi-GB file or a link to `/dev/zero` exhausts memory, and a non-UTF-8 file makes `ketch changelog` fail instead of falling back to release notes. Require a regular file (`symlink_metadata`), read through `take` with a cap of a few MiB, decode lossily. |
| B118 | P3 | vuln | fixed | `install.rs` (`refuse_checksum_downgrade`); `src/cli.rs` `--allow-unverified` | A package installed against a published checksum refuses a release that publishes none. `--allow-unverified` or `--yes` consents; a lockfile hash stands in for that consent. |
| B119 | P3 | bug | fixed | `shell.rs:1302-1310` (`block_span`) | It pairs the first BEGIN with the first END after it. A stray BEGIN without END (hand edit, crash mid-write) makes `splice` append a second block, and a later `unsplice` deletes every user line between the orphan BEGIN and the real END. Pair END with the nearest preceding BEGIN or refuse when marker counts differ. |
| B120 | P3 | bug | fixed | `shell.rs` (`marker_at_line_start`, `block_span`) | A marker line may end in `\r\n` or `\r`. The span consumes that ending, so a CRLF profile is updated in place instead of gaining a second block. |
| B121 | P3 | bug | fixed | `shell.rs` `startup_target` | A dangling startup symlink is followed with `read_link`. The target file is written when its directory exists; a missing checkout is refused, and the symlink is left in place either way. |
| B122 | P3 | bug | fixed | `src/ui.rs` (`BarProgress::start`) | The download label reaches `set_message` through `printable` before it is truncated, the same filter as every other status line. |
| B123 | P3 | bug | fixed | `platform/{macos,unix,windows}.rs` `restore_retired_store`, from `relink`/`rollback` | In these paths `move_into_store` is a no-op and `sweep_swap_leftovers` does not run; if linking fails while a stale `<ver>.old` from an earlier interrupted swap exists, the error path deletes the live prefix and renames the stale copy over it. Restore only when this call made the swap (have `move_into_store` say so). |
| B124 | P3 | bug | fixed | `platform/windows.rs:80-104` (`busy_aside`, `clear_for_replace`) | `tool.exe.old.<pid>-<ms>` files left when a running exe is renamed aside are never swept (only ketch's own asides are), so they pile up with every upgrade of a running tool. Sweep `*.old.*` siblings of recorded links at the start of commit, relink and uninstall. |
| B125 | P3 | vuln | fixed | `crates/ketch-ffi/src/callbacks.rs` `From<report::Event>`; `records.rs` `Changelog::new` | Event strings and `Changelog` name/version pass through `changelog::sanitize` before they reach Swift, C# and Vala. |
| B126 | P3 | bug | fixed | `crates/ketch-capi/vapi/ketch.vapi` (`StopFunc`); `crates/ketch-capi/include/ketch.h:37` | `KetchStopFn` returns C `bool`, the Vala delegate returns `gboolean` (int): an incompatible function-pointer type (error by default on GCC ≥ 14) and a return-ABI mismatch. `tests/capi.vala` never passes a `ChooseFunc` or `StopFunc`. Return `int32_t` and cover both callbacks in the Vala test. |
| B127 | P3 | bug | fixed | `crates/ketch-capi/src/lib.rs:61-65` (`ErrorBody`) | `ErrorBody` flattens `KetchError`, whose `Network`/`Verification`/`Other` variants already have `message`, and adds its own: the JSON envelope has a duplicate `"message"` key that strict parsers reject. Skip or rename the outer field. |
| B128 | P3 | bug | fixed | `desktop/macos/Ketch/Core/KetchCoreProtocol.swift` | `busy(pid:)` is optional. An unknown holder is logged and shown without a pid, the same as the Windows app. |
| B129 | P3 | bug | fixed | `desktop/macos/Ketch/Store/KetchStore.swift` | The update timer and a window becoming key call `refresh(alerting: false)`. A failure is a log line. A refresh the user asked for still sets `errorMessage`. |
| B130 | P3 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`, `PackageDetailView.swift:59-62`; `desktop/windows/Ketch.App/Pages/DiscoverPage.xaml.cs:36-42` | Core calls ignore task cancellation, so an older, slower search or changelog reply can land after a newer one (Windows also searches on every keystroke). Tag requests and drop out-of-date replies; debounce search. |
| B131 | P3 | bug | fixed | `desktop/windows/Ketch.AppCore/KetchStore.cs` (`Operate`) | When an operation is already running, `Operate` returned silently: a confirmed Uninstall or a clicked Install did nothing. It now sets `ErrorMessage` to the same sentence macOS uses. `A_second_operation_while_one_is_running_says_so` covers it. |
| B132 | P3 | bug | fixed | `install.ps1` | `--version 0.10.0` is given a leading `v` before the download URL is built, the same as `install.sh`. A tag that already starts with `v` is left alone. |
| B133 | P3 | vuln | fixed | `install.sh:7-359` | The body is not wrapped in a function, so under `curl \| bash` a truncated download runs whatever complete lines arrived. Low impact today, but the guard is missing. Put the body in `main() { … }` and call `main "$@"` last. |
| B134 | P3 | bug | fixed | `install.ps1` | The installer adds `Tls12` with `-bor` before any download, so older Windows PowerShell can reach GitHub and a host that already enabled TLS 1.3 keeps it. |
| B136 | P3 | bug | fixed | `Justfile` (`lint-cask`); `ci.yml` package job | `just check` and the macOS package job wrote `cask/Casks/ketch.rb` into the repo root and `cask/` was not ignored, so `scripts/release.sh --local` stopped at its clean-tree check. Both now write `target/cask/Casks/ketch.rb` (`target/` is gitignored; `brew style` still sees a `Casks/` directory). |
| B135 | P3 | bug | fixed | `.github/workflows/ci.yml` `review` | `/review` dispatched `--ref` from the PR's `headRefName`. A fork's name is not a ref here, and a same-named branch can be `main`, so CI ran on the wrong commit while the comment named the PR head. The job now refuses a head outside this repository and a ref whose SHA is not `headRefOid`. |
| B138 | P3 | vuln | fixed | `Cargo.lock` (cargo-deny 0.20.2, default config) | RUSTSEC-2026-0285: rustls 0.23.43 via ureq and octocrab, fixed in ≥ 0.23.45 (low impact; `cargo update -p rustls` needs creator permission). RUSTSEC-2023-0071: rsa 0.9.10 (Marvin) via pgp, sigstore and jsonwebtoken, no upstream fix, verification-only use; record an explicit ignore with that reason in a new `deny.toml`. |
| B137 | P3 | bug | fixed | `tests/commit-msg-breaking.sh`; `Justfile` `lint-commits`; `ci.yml` | `done.md` said the breaking-marker test was wired into `just lint-commits`, but neither the Justfile nor CI called it. `lint-commits`, `lint-shell` and the package job's shell step now run it. |

Fixed and merged, cards in `done.md`: B84 (#301), B90 (#295), B91 (#317), B92 (#307), B93 (#316), B95 (#308), B97 (#311), B101 (#302), B103 (#298), B104 (#296), B108 (#310), B109 (#309), B111 (#313), B114 (#304), B115 (#305), B124 (#299), B130 (#314).

| # | Status | Priority | Complexity | Readiness | Agent |
| --- | --- | --- | --- | --- | --- |
| R5 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| F13 | in progress | P3 | 4 | 80% | Claude Code / opus-5.5 |
| D12 | todo | P3 | 4 | 0% | |
| D17 | todo | P3 | 4 | 0% | |
| D18 | todo | P3 | 3 | 0% | |
| D19 | todo | P3 | 4 | 0% | |
| K902 | todo | P0 | 2 | 0% | |
| K903 | todo | P0 | 3 | 0% | |
| K904 | todo | P0 | 3 | 0% | |
| K905 | todo | P0 | 3 | 0% | |
| K906 | todo | P1 | 1 | 0% | |
| K907 | todo | P1 | 3 | 0% | |
| K908 | todo | P1 | 2 | 0% | |
| K909 | todo | P1 | 2 | 0% | |
| K910 | todo | P1 | 2 | 0% | |
| K911 | todo | P1 | 3 | 0% | |
| K912 | todo | P1 | 2 | 0% | |
| K913 | todo | P1 | 2 | 0% | |
| K914 | todo | P1 | 2 | 0% | |
| K915 | todo | P1 | 1 | 0% | |
| K916 | todo | P1 | 1 | 0% | |
| K917 | todo | P1 | 2 | 0% | |
| K918 | todo | P1 | 1 | 0% | |
| K919 | todo | P1 | 1 | 0% | |
| K920 | todo | P1 | 3 | 0% | |
| K921 | todo | P1 | 2 | 0% | |
| K922 | todo | P1 | 3 | 0% | |
| K923 | todo | P1 | 3 | 0% | |
| K924 | todo | P1 | 3 | 0% | |
| K925 | todo | P2 | 2 | 0% | |
| K926 | todo | P2 | 3 | 0% | |
| K927 | todo | P2 | 3 | 0% | |
| K928 | todo | P2 | 2 | 0% | |
| K929 | todo | P2 | 3 | 0% | |
| K930 | todo | P2 | 3 | 0% | |
| K930.1 | todo | P2 | 3 | 0% | |
| K930.2 | todo | P2 | 3 | 0% | |
| K931 | todo | P2 | 4 | 0% | |
| K932 | todo | P2 | 2 | 0% | |
| K933 | todo | P2 | 4 | 0% | |
| K934 | todo | P2 | 2 | 0% | |
| K935 | todo | P2 | 3 | 0% | |
| K936 | todo | P2 | 3 | 0% | |
| K937 | todo | P2 | 3 | 0% | |
| K938 | todo | P2 | 2 | 0% | |
| K939 | todo | P2 | 1 | 0% | |
| K940 | todo | P2 | 2 | 0% | |
| K941 | todo | P2 | 3 | 0% | |
| K942 | todo | P2 | 2 | 0% | |
| K943 | todo | P2 | 2 | 0% | |
| K944 | todo | P2 | 3 | 0% | |
| K945 | todo | P2 | 2 | 0% | |
| K946 | todo | P2 | 3 | 0% | |
| K946.1 | todo | P2 | 3 | 0% | |
| K946.2 | todo | P2 | 3 | 0% | |
| K946.3 | todo | P2 | 3 | 0% | |
| K947 | todo | P2 | 2 | 0% | |
| K948 | todo | P2 | 3 | 0% | |
| K949 | todo | P2 | 3 | 0% | |
| K950 | todo | P2 | 3 | 0% | |
| K951 | todo | P2 | 4 | 0% | |
| K952 | todo | P3 | 2 | 0% | |
| K953 | todo | P3 | 2 | 0% | |
| K953.1 | todo | P3 | 3 | 0% | |
| K953.2 | todo | P3 | 2 | 0% | |
| K953.3 | todo | P3 | 2 | 0% | |
| K954 | todo | P3 | 2 | 0% | |
| K955 | todo | P3 | 2 | 0% | |
| K956 | todo | P3 | 3 | 0% | |
| K957 | todo | P3 | 3 | 0% | |
| K958 | todo | P3 | 1 | 0% | |
| K959 | todo | P3 | 2 | 0% | |
| K960 | todo | P3 | 2 | 0% | |
| K961 | todo | P3 | 2 | 0% | |
| K962 | todo | P3 | 2 | 0% | |
| K963 | todo | P2 | 3 | 0% | |
| K964 | todo | P3 | 1 | 0% | |
| K965 | todo | P3 | 1 | 0% | |
| K966 | todo | P3 | 2 | 0% | |
| K967 | todo | P2 | 2 | 0% | |
| K968 | todo | P3 | 1 | 0% | |

### Ketch audit

Actionable follow-ups from the 2026-09-20 product audit:

1. `ROADMAP.md` is wrong: signatures/trust are still marked "wanted", but `src/trust.rs` + M3 already shipped. Rewrite ROADMAP to match reality; remove shipped items from "wanted". — done: signatures → shipped M3, man pages → shipped M4, `why` → shipped M7, registry maturity → partial with collisions/staleness resolved and registry CI dropped (F2).
2. `todo.md` still lists M3/F5 as open, though they are done. Sync with `plan.md`. — done: todo.md now lists F1/F2/M3/F5 with F1 done (ketch side), F2 dropped, M3 done, F5 done (ketch side).
3. Version drift — done: `Cargo.toml`, `CHANGELOG.md` (`0.4.6`, 2026-09-20), tag `v0.4.6`, and `site/hugo.toml` (`params.version`) are all aligned. `site/sync-docs.py::sync_version()` keeps `site.Params.version` equal to `Cargo.toml` on every site build (covered by `tests/site_version.rs` and `site/test_sync_docs.py`). CI guard added: `tests/crate-version.sh` fails when the crate version, latest tag, and changelog entry disagree; wired into `just lint-shell` and the CI package job's shell-syntax step.
4. Registry has no CI — resolved as dropped (F2): `pyrlyn/ketch-registry` removed its only workflow (commit `5a9bbd6`, "no CI is wanted in this repo"), so there is no upstream to land a validating workflow in. Ketch side documents local validation plus a pre-push hook (`docs/REGISTRY.md`); name/alias collisions are fatal in `ketch registry validate` and warnings on `ketch update` by design (best-effort client).
5. "Is the registry stale?" — resolved: `ketch registry status` and the `ketch doctor` registry line already report the local copy's age and source from `registry.meta.toml` with no network call. `ketch update` remains the only refresh, by design.
6. macOS notarisation: `KETCH_NOTARIZE` exists, but secrets / a real notarized release are not set up yet (F1). Configure when ready — creator step, needs the App Store Connect key; not doable from inside the repo.
7. Docs: no Troubleshooting page (Windows locked exe, brew → self upgrade, registry collisions, notarize failures). — done: `docs/TROUBLESHOOTING.md` (wired into `site/sync-docs.py` + the pages.yml build checklist + `.gitignore`).

### Ketch audit (part 2)

8. No reference plugin in-repo as a copy-paste example. — done: `examples/ketch-source-example` (executable, `sh -n` clean, mirrors `docs/PLUGINS.md`; docs link to it).
9. Tests: trycmd is nearly empty (one `version` snapshot). — done: `tests/cases/help.trycmd` (`--help`) + `tests/cases/help-commands.trycmd` (install/upgrade/doctor/registry `--help`); `cargo test --test trycmd` green.
10. Plugin protocol: little e2e coverage with a fake `ketch-source-*` (fail paths). — done: `tests/plugin_fail.rs` (capabilities failure named by `plugin list`, future-protocol scheme refused, `releases` failure carries stderr); green.
11. Concurrent upgrade stress: few tests for "two `ketch upgrade` at once" / "binary busy mid-upgrade". — done (lock half): `tests/lock_extras.rs::a_second_upgrade_while_the_lock_is_held_reports_the_holder` proves the second run fails with exit 8 and the holder message. "Binary busy mid-upgrade" (in-use process stop offer, Windows rename-aside) is already covered in `src/process.rs` + `tests/self_update.rs` / `tests/auto_update.rs`; no new test added.
12. `extra_paths` e2e: "install → man/completion on disk → uninstall removes them". — done: `tests/lock_extras.rs::extras_are_linked_on_install_and_removed_on_uninstall` (sandbox `XDG_DATA_HOME` via new `Sandbox::ok_env`); green.
13. Evaluate multi-version side-by-side and aqua parity (global lockfile UX, built-in catalog) — done as A3 in `done.md`: all three already shipped (retention + rollback/prune, `lock`/`sync`, `builtin.toml` tiers), no gap, no issues filed.
14. Suggested order: (1) sync ROADMAP/todo + version — done above, (2) ~~CI validate on the registry~~ dropped (F2; local validate + pre-push hook instead), (3) trycmd + fake plugin + uninstall e2e + concurrent stress — done above, (4) notarize secrets — creator step, (5) multi-version issues — done as A3, nothing to file.

---

## Note 2026-09-17 — testing library candidates

Shared catalog: [`listepo/rust.md`](../../rust.md) → Code rules *Testing candidates*
and cargo *Testing candidates (evaluate — not auto-added)*. Do **not** add these
deps unless a concrete gap shows up.

Fits for ketch (1–3):

1. `vfs` — evaluate for install/store unit tests that today use `tempfile` /
   `assert_fs` host trees (path quirks, no disk).
2. `temp-env` — scoped `KETCH_*` / `PATH` overrides in unit tests.
3. `fake` — only if synthetic GitHub release/asset fixtures beat hand-written JSON.

Already covered: `assert_cmd`, `assert_fs`, `insta`, `predicates`,
`pretty_assertions`, `proptest`, `rstest`, `trycmd`. Skip `mockall` /
`tokio-test` / `testcontainers` / extra fuzzers unless a new seam needs them.

## Plan 2026-09-27 (drafting with Ivan)

### Goals

### Tasks

### Priorities

Set in the task table above (creator, 2026-09-27).

## Plan 2026-09-30 (dictated by Ivan)

Eleven tasks Ivan dictated on 2026-09-30. Docs only: nothing below is implemented yet. Ivan's numbering maps to IDs as follows: 1 → B66, 2 → B67, 3 → B68, 4 → F9, 5 → B69, 6 → M10, 7 → M11, 8 → M12, 9 → F10, 10 → F11, 11 → R4. Priorities in the task table are suggestions; the creator confirms them.

Code read for this plan (`main` at `3920dc6`, v0.8.0): `src/self_update.rs` (`uninstall_plan`, `uninstall_self`, `remove_root_at`), `src/install.rs` (`install`, `uninstall`, `remove_store_dir`), `src/platform/unix.rs` and `macos.rs` (`move_into_store`), `src/shell.rs` (`install_user`, `uninstall_user`, `user_path_configured`), `src/cmd/pkg.rs` (`install`, `uninstall`), `src/error.rs`, `src/ui.rs`, `src/extra.rs` (`write_ketch_docs`, `render_manpage`), `src/cli.rs`, `Cargo.toml`, `install.ps1`, `dist-workspace.toml`.

### Suggested order

1. **Lifecycle (one code path).** B67 → B69 → B68 → B66 → F9. B67, B68 and B66 all touch the uninstall/update path (`install::uninstall`, `remove_store_dir`, `move_into_store`, `self_update::uninstall_self`), so they should land in that order, as separate PRs, on one owner. B69 depends on B67: "not found" is only honest once "uninstalled" means no state record, no `store/<name>/`, no links. F9 comes after B68 because its "yes" path runs the update.
2. **Output.** F10 → F11. Both go through the same line helpers in `src/ui.rs` (`step_line`, `success_line`, `warn_line`, `note_line`, `error_lines`), and F11's icon sits in the column F10 colours. The messages added by B69 and F9 go through those helpers too, so they pick up colour and icons without extra work.
3. **Generated from the CLI definition.** M10, M11, M12 all read `Cli::command()` (clap derive), so a CLI change regenerates them. They can land in parallel with 1 and 2, but land after F9/B69 if those add flags so the snapshots are blessed once. M12 depends on M11 (same completer for dynamic values) and on B66 (M12 adds a registry value, `Command Processor\AutoRun`, which self uninstall must remove).
4. **R4 (fuzz)** is independent and can start at any time. Its first step (a library target) touches `src/main.rs` and `Cargo.toml`, so coordinate with whoever is mid-change there.

Dependency summary: B69 ← B67; B68 shares the stale-sibling sweep with B67; F9 ← B68; B66 ← M12 (re-check after M12 adds AutoRun); F11 ← F10; M12 ← M11; R4 independent.

### Dependencies and tooling

- **clap** 4 (derive) is already the CLI. **clap_complete** 4 is already a dependency: `ketch completions <shell> [--install]` and `self install` (`extra::write_ketch_docs`) generate bash, zsh, fish, elvish and PowerShell scripts. M11 and the PowerShell half of M12 extend that; dynamic values need clap_complete's `unstable-dynamic` feature (`CompleteEnv`) or a small hidden `ketch __complete` command. M11 (done) chose `ketch __complete [--root DIR] <installed|registry> [PREFIX]` in `src/complete.rs`: `unstable-dynamic` is outside clap_complete's semver promise. M12 calls the same command.
- **clap_mangen** (new dependency, same clap 4 major; rtok uses 0.3 for `rtok man`) for M10. Today's `extra::render_manpage` hand-writes one `ketch.1`. `mandoc -Tlint` (ships with macOS, `apt install mandoc` on Linux) checks the roff in CI.
- **bash** ≥ 4 with bash-completion 2 for lazy-loaded completions. macOS ships bash 3.2, so document `brew install bash bash-completion@2`.
- **PowerShell**: `Register-ArgumentCompleter -Native` is what clap_complete already emits. `pwsh` 7 exists on the `windows-latest` runner; Windows PowerShell 5.1 uses a different profile directory. **doskey** is built into cmd.exe. cmd has no programmable completion, so doskey gives macros only (see M12).
- **Colour**: no colour crate; `src/ui.rs` writes ANSI itself and honours `--no-color`, `NO_COLOR` and `CLICOLOR_FORCE`. **Emoji width**: `unicode-width` is already a transitive dependency (through indicatif). F11 needs it as a direct dependency to pad columns correctly.
- **Fuzzing**: `cargo-fuzz` (`cargo install cargo-fuzz`, or `"cargo:cargo-fuzz"` in `mise.toml`) plus a **nightly** toolchain (`rustup toolchain install nightly`). `mise.toml` pins stable 1.98.1 only, and that stays the build toolchain. libFuzzer runs on macOS and Linux; Windows is not a target for R4. Use the same layout as rtok's in-progress `test/cargo-fuzz` branch (a standalone `fuzz/` workspace excluded from the root one).

Plan:

## Plan 2026-09-30 — desktop app on `ketch-core`

Seven tasks from `docs/research-desktop.md`. Creator's decision (2026-09-30): a native UI on each platform, macOS first; Windows and Linux follow later (`roadmap.md`). So the core is exported through UniFFI to a SwiftUI app. R5–R8 make the core usable outside a terminal and help the CLI and the TUI on their own. Priorities are suggestions; the creator confirms them.

Order: R5 → R6 and R7 (in either order; R7 after B64) → R8 → R9 → F12 → F13.

### R5. Workspace split: `ketch-core` library crate

Turn the repository into a Cargo workspace. The modules move into `crates/ketch-core` (with a `src/lib.rs`); the `ketch` binary keeps `main.rs`, `cli.rs`, `cmd/`, `complete.rs` and the terminal renderer. The public surface is what `cmd/` calls today. No behaviour change: every existing test passes unchanged, `unsafe_code = "forbid"` and MSRV 1.86 stay on both crates, `release.yml` still matches `dist generate`, and the release asset names do not change.

Overlaps with R4 step 1 (a `src/lib.rs` for the fuzz targets): whichever lands first does it, and the other builds on it.

Plan:
1. Timing: this moves most of `src/`, so start it when the in-progress tasks touching `src/` (B64, B65, M9, F8, R3) have merged, and do the move as pure `git mv` commits so open branches rebase across the renames.
2. Root `Cargo.toml` becomes the `ketch` package plus `[workspace] members = [".", "crates/ketch-core"]` with `[workspace.package]` (edition, `rust-version`, licence, repository) and `[workspace.lints]` (`unsafe_code = "forbid"`) inherited by both. The root stays the `ketch` package so `scripts/release.sh`, `tests/crate-version.sh`, `release-plz.toml` and `dist-workspace.toml` (`members = ["cargo:."]`) keep reading the version where they do now; `ketch-core` gets `version.workspace = true` and `publish = false`, and dist is told to skip it (`dist = false` in its package metadata).
3. `crates/ketch-core/src/lib.rs` declares every module except `cli`, `cmd`, `complete` and the terminal half of `ui`; `builtin.toml`, `sigstore-trusted-root.json`, `migrations/` and `src/snapshots` move with the modules that embed them (fix `include_str!`/`embed_migrations!` paths).
4. Visibility: start with `pub mod` for what `cmd/` uses, `pub(crate)` for the rest; no re-architecture in this task. `ui.rs` stays in the core for now (R6 moves the printing out) so this task is a move only.
5. `main.rs`, `cli.rs`, `cmd/`, `complete.rs` stay in the root package and `use ketch_core::…`. Dependencies split: clap, clap_complete, crossterm/ratatui (`tui` feature) go with the binary; the rest with the core. The `tui` feature is forwarded if the core still needs it.
6. Tests: `tests/` stays with the binary (it drives the real binary). Unit tests move with their modules. `trycmd`/`insta` snapshot paths are checked, not re-recorded.
7. Tooling: `Justfile` (`--workspace` where needed), `ci.yml`, `.cargo/config.toml` `paths` override from `just setup`, `sync-docs.py` if it lists `src/` paths; AGENTS.md "Layout" table updated; `rust.md` and `toolchain.md` unchanged (no new crates).

Check: `just check` clean; `cargo nextest run --workspace` passes with no snapshot changes; `dist build` for the host produces `ketch-<target>.tar.gz` with the same layout; `just dist-generate` leaves `release.yml` unchanged; `scripts/release.sh --dry-run` prints the right next version.

Status: PR https://github.com/pyrlyn/ketch/pull/187 merged 2026-10-01. The version moved to `[workspace.package]`; `release.sh` and `crate-version.sh` read it there, and v0.9.0 was released after the merge. The 18 `ignore` fences under `crates/` are examples that compile (`no_run` only where running would touch GitHub or the real ketch root). `tests/workspace.rs` rejects a new `ignore` fence there. Remaining: step 5, `clap` and `clap_complete` still in `ketch-core`.

### F13. macOS app release pipeline

A separate workflow for the app: `xcodebuild` archive, Developer ID signing with the existing certificate, notarisation and stapling (a `.app`/`.dmg` can be stapled, unlike the bare CLI binary; needs F1's App Store Connect key), and an update mechanism chosen in the task (a sourced comparison first). cargo-dist keeps releasing the CLI; the CLI's asset names do not change.

Constraint found in the survey: `install.sh`, `install.ps1` and the GitHub source (`src/source/github.rs`) resolve the host through `/releases/latest`. An app release in this repository that GitHub marks as latest would send every CLI installer and `ketch self upgrade` to a release without CLI assets.

Plan:
1. Decided (creator, 2026-10-01): app releases live in this repository (monorepo), tagged `desktop-v*` and created with `make_latest: false`. A script in `tests/` asserts the app workflow never creates a release eligible for latest, and that `install.sh`/`install.ps1`/the GitHub source still resolve only CLI releases.
2. Versioning: the app has its own version (`desktop-vX.Y.Z`), separate from the CLI's; release-plz and `scripts/release.sh` ignore it. Changelog section for the app via git-cliff with a path filter on `desktop/` and `crates/ketch-ffi/`.
3. Workflow `.github/workflows/desktop-release.yml`, `workflow_dispatch` only: macOS runner with Xcode 26; `just xcframework`; `xcodebuild archive` + `-exportArchive` with a Developer ID export options plist; signing with the existing `MACOS_CERTIFICATE`/`MACOS_CERTIFICATE_PWD` (hardened runtime on); a `.dmg`; `xcrun notarytool submit --wait` with the App Store Connect secrets from F1; `xcrun stapler staple` on the `.dmg`; `spctl --assess` as the smoke test; SHA256 checksum next to the asset.
4. Updates: compare Sparkle 2 and alternatives with primary sources (versions, dates, licence, EdDSA signing) and pick one; its signing key becomes a repository secret; the appcast is published with the release.
5. Homebrew: optionally a second cask (`ketch-app`) generated by a script like `scripts/cask.sh` and pushed to the tap — only after the first signed release works.
6. Docs: AGENTS.md "Releasing" gets an app subsection (secrets, tag scheme, the latest-release constraint).

Check: a dry run on a branch produces a signed, notarised, stapled `.dmg` that opens on a clean macOS 26 machine without a Gatekeeper prompt; `install.sh` still resolves the CLI release afterwards; the update feed moves an older build to the new one.

Status: PR https://github.com/pyrlyn/ketch/pull/190 merged 2026-10-01. Sparkle 2.10.0 with EdDSA; the feed is `releases/download/desktop-appcast/appcast.xml`, independent of `/releases/latest`. Two real bugs fixed on the way: `cliff.toml`'s unanchored `tag_pattern` and `select_release` ranking `desktop-v*` above CLI tags under `--pre`. Remaining: waiting on the creator for the secrets `APPSTORE_CONNECT_KEY`, `APPSTORE_CONNECT_KEY_ID`, `APPSTORE_CONNECT_ISSUER_ID`, `SPARKLE_ED_PRIVATE_KEY` and the matching `SUPublicEDKey` committed; `release-apple-desktop.yml` builds the XCFramework before the archive; no real signed release has run.

### D12. Windows: the app on the real core

Swap the Windows app's fake core for the binding. Depends on D10, D11, D1 and D2.

Done when the app runs every screen on `ketch-ffi` through D10's binding, work runs off the UI thread with events marshalled to the `DispatcherQueue`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

The app ships both as an MSIX and unpackaged (D14), and an unpackaged app has no `ApplicationData`, so settings go in a file under `%LOCALAPPDATA%\ketch` that works in both.

Status: done. `FfiKetchCore` is the app's `IKetchCore` over the generated binding. Records, events and errors go through the same `ContractScenario` classes the fake replays, so the two cores show the same things. `KetchStore` already runs each call on its own thread and posts events and questions through the `DispatcherQueue`. `CoreFactory` picks the real core, or the fake when `KETCH_APP_FAKE_CORE=1`. Theme preferences live in `%LOCALAPPDATA%\ketch\app-settings.json`, which an MSIX redirects into its package data, so one path serves both forms. A declined binary choice or stop question cancels the operation; a leftover folder has no dialog and is kept. Discover still searches as you type, debounced (B130); an empty query browses the built-in shelf and asks no source. The manual Windows GUI pass (launch against a scratch `KETCH_ROOT`, walk every screen, confirm the choice dialog and a `Busy` retry) was not run here: this environment has no Windows desktop. The adapter tests cover the scratch-root install, cancel, `Busy` and `NotFound` paths, and `ketch-win-app` builds the WinUI project and checks `ketch_ffi.dll` sits beside `Ketch.exe`.

### D17. Linux: the app on the real core

Swap the Linux app's fake core for `ketch-capi`. Depends on D15 and D16.

Done when every screen runs on the C ABI, calls run off the main loop with events returned through the `GLib.MainContext`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

### D18. Linux: notifications, background and autostart

GNOME has no tray in its HIG; an app that checks in the background asks the Background portal and notifies through `GNotification`. Research: sections 1 and 3c.

Done when update notices go through `GNotification` (the Notification portal under Flatpak), background running and start at login are requested through the Background portal (libportal) with an XDG autostart entry outside a sandbox, `ketch://` links follow D8's rules, and there is no tray: the creator deferred a StatusNotifierItem tray (2026-10-01, open decision 8).

### D19. Linux: packaging and release

How the Linux app reaches users. R10 left Flatpak against distribution packages open, and Flatpak needs home access for PATH work.

Done when the creator has chosen the format, CI builds it on `desktop-linux-v*` tags without touching `/releases/latest`, the app ships AppStream metadata and a `.desktop` file that validate, and updates come from the chosen package manager.

## Improvement review (2026-10-21)

A read-only review of `main` at `57d8a67` in four directions: core architecture and code, product and usability (against mise, aqua, eget, ubi, dra, stew, cargo-binstall, Homebrew, Scoop, winget, pkgx), CI and release, and the desktop apps. Findings, numbers and sources are in [`docs/research-improvements.md`](docs/research-improvements.md). The ideas already in `ideas.md` and the review tables above are not repeated; where a task extends one, its card says so.

What matters most:

- `main` has been red since 2026-10-18, and pull requests merge without green checks (K902, K903, K904).
- The registry holds 7 packages, so installing by name almost always fails (K905); `ketch` is also a Homebrew-core formula of another project (K906).
- `state.json` breaks when builds of different versions share one root, which the separately released desktop apps make likely (K907).

Cards are grouped by area and ordered by priority within each group. Priorities and complexity are suggestions; the creator confirms them. Tasks that start with a decision say so in their card.

#### CI and release

### K902. Restore a green main

`main` has had no green `ci` run since 2026-10-18; run 39601341616 at `57d8a67` fails on Linux and Windows. `platform/linux.rs:259` still sets `Placement.version`, which #440 removed (E0560); `shell.rs` calls the private `exe` on Windows after #441 (E0624); `install.ps1` fails to parse under Windows PowerShell 5.1 (UTF-8 without a BOM, `✓` at line 459), and README tells Windows users to run that file from `main`. Every open branch inherits the breakage.

Done when a full `ci` run on `main` is green on every target, `install.ps1` is ASCII-only (or has a BOM) with a test that fails on a non-ASCII byte, and the 5.1 step passes.

### K903. Make every CI job gate merges to main

Ruleset `protect-main` lets the admin role bypass checks, and its 15 required checks leave out `ketch-cs`, `ketch-win-app`, `ketch-capi`, `ketch-linux-app` and `hakari`. 30 of the last 40 merged pull requests had non-green checks; five merged with none green. Superseded `main` runs are cancelled, and a cancelled run never fires `release-ci-alert`, so a red `main` went unnoticed for three days.

Done when one aggregate `ci / gate` job (`needs:` every job, `if: always()`) is the required check, the bypass is off or merges go through a merge queue, and an alert fires when `main` has had no green run for 24 hours, cancelled runs included.

Depends on K902.

### K904. Take the single self-hosted Mac off the PR critical path

`MACOS_RUNNER=xcode-27-local` sends every macOS job to one org-wide runner. Four macOS jobs (about 85 job-minutes) queue behind each other there, waits reach 165–284 minutes, and 428 pull-request runs were cancelled in three days. Since #462 `rust / plan`, `fmt` and `msrv` land on it too, and the docs-only `package (macos)` no-op waits up to two hours for 0.2 minutes of work. `ketch-ffi` and `macos-app` each build the XCFramework.

Done when pull requests use hosted macOS (the Mac only for `main` or a merge queue), plan/fmt/msrv and the docs-only `package` run on `ubuntu-latest`, one XCFramework build feeds both jobs, and the median pull-request wall time over 20 runs is under 30 minutes.

Depends on K903.

#### Product and onboarding

### K905. Seed the registry with short names from mise's registry

`pyrlyn/ketch-registry` holds 7 packages and `builtin.toml` 2, so `ketch install jq` fails: installing by name, the first thing a new user tries, almost always ends in "use owner/repo". mise's `registry/*.toml` (about 1,015 short names) and aqua's registry (about 2,000 GitHub-release entries) are MIT and already map names to repositories.

Done when a converter script turns each mise entry with an `aqua:`, `github:` or `ubi:` backend into `<name>/ketch.toml` (source, description, `provides` from its bins), at least 300 entries pass `ketch registry validate`, a NOTICE credits the MIT sources, and `ketch install jq`, `fd`, `bat`, `gh` and `lazygit` resolve.

Depends on K910.

### K906. Decide how to live with the `ketch` name collision

Homebrew core already ships a `ketch` formula (1broseidon/ketch, web scraping for agents), and the AUR `ketch` is a third project. `brew install ketch` installs the wrong program, and a second `ketch` on PATH is only hinted at for `~/.cargo/bin` (`doctor.rs:405`).

Done when the creator's decision (keep the name and disambiguate, or rename the binary or brand) is recorded, README, site and docs (with ru/uk) point at the tap explicitly, and `ketch doctor` names a Homebrew-core `ketch` that shadows this one.

#### Core architecture and code

### K907. Make `state.json` readable across ketch versions

The CLI, the Homebrew bootstrap and each desktop app (released separately, each linking its own `ketch-ffi`) share one `~/.ketch`. `STATE_VERSION` (`state.rs:27`) has never moved; the stored manifest snapshot inherits `deny_unknown_fields` (`model.rs:840`), the stored enums have no fallback and unknown fields are dropped on save. One new manifest key or enum value written by a newer build makes every older build fail on "could not parse state.json".

Done when the stored snapshot is parsed leniently (user-written files stay strict), stored enums have an `Unknown` fallback, unknown fields round-trip via `serde(flatten)`, a test loads and re-saves a state with an unknown key, enum value and package field unchanged, and the rule for bumping `STATE_VERSION` is written down.

#### CI and release

### K908. Settle the version restart for existing installs

Versioning restarted at 0.0.1 (#359). `self_update.rs:502` refuses `to <= from`, so a ketch on 0.10.0 never upgrades again; the cask is stuck at `version "0.10.0"` because the v0.0.1 tap push failed (#367); release-failure issues #362 and #254 are still open; `/releases/latest` is an unoptimised dev build.

Done when the creator's choice (bump past 0.10, or a migration note plus a `self upgrade` path down) is recorded and implemented, the tap job is fixed and re-run, and the stale release-failure issues are closed.

Depends on K902.

#### Process and tooling

### K909. Reduce plan.md to the active tasks

After the move to rtok `plan.md` is mostly history: about 65 `fixed` rows in two review tables, finished audit lists, empty "Plan 2026-09-27" sections, a dangling "Plan:" and the finished D12 still open in `tasks/`. #465 merged with its own `tests/tasks.rs` red (finished rows left in the table).

Done when `plan.md` holds the header, the task table and the active cards, the review tables and audit notes live in `done.md` or a maintainer doc, D12 is closed with the creator's OK, and `cargo test --test tasks` passes.

#### Product and onboarding

### K910. Resolve for another target in `ketch why` and `ketch info`

Resolution only runs for the host: nothing lets you ask which asset Linux or Windows would get. That blocks checking a registry entry on every OS from one machine, measuring inference, and a lockfile for several targets.

Done when `ketch why <pkg> --target <triple-or-os-arch> [--json]` and `ketch info --assets --target …` resolve without installing, and unit tests cover each supported target.

### K911. Measure asset inference against a public corpus

"Any repository that ships releases" is the headline claim (README, site), and nothing measures it. A pass rate turns the slogan into a number and finds asset-scoring bugs before users do.

Done when a scheduled workflow (network allowed) runs `ketch why --target` for a few hundred repositories from the aqua list across five targets, publishes a pass-rate JSON and a failure list as an artifact, and the first batch of failures has become scoring fixes or registry entries.

Depends on K910.

### K912. Pick up the gh token and explain the rate limit

Unauthenticated GitHub allows 60 requests an hour; `ketch outdated` over a dozen packages spends that quickly. `config.rs:231-246` reads only env vars and `config.toml`, and the error asks the user to run `gh auth token` themselves (`error.rs:198`). dra and mise read the GitHub CLI's token.

Done when, with no token configured, ketch asks `gh auth token` (with a timeout, switchable off in config), a 403/429 names the reset time from `x-ratelimit-reset`, `ketch doctor` shows which token source is used (masked), and unit tests cover each source.

### K913. Offer matches when a short name is unknown

`ketch install <unknown>` stops with advice to run `ketch search` by hand (`manifest.rs:122-130`). One prompt turns that dead end into an install.

Done when, on a terminal, an unknown name shows the top registry and GitHub matches through the `Decider` and installs the one chosen; under `--yes` or without a TTY it still fails, listing them; an end-to-end test runs against the fixture plugin.

### K914. Ship a setup-ketch GitHub Action

CI is where teams adopt a tool installer, and mise, aqua and pkgx each offer a one-line step; ketch has no `action.yml`.

Done when a composite action installs a pinned ketch verified against `SHA256SUMS`, adds the bin dir to `GITHUB_PATH`, passes the token, caches the store keyed on the `ketch.lock` hash, optionally runs `ketch sync`, and a CI job uses it on all three OSes.

### K915. Make licensing and dual licensing clear

`PRICING.md` says prices come later; the README licence text reads like a library template; `cla.yml` is off and CONTRIBUTING never mentions a CLA, so outside contributions would block selling a commercial licence; Cargo says GPL-3.0-or-later while the desktop research says GPL-3.0-only.

Done when the creator's choices are recorded, README has a short licence FAQ (running ketch is free; licences matter when embedding or redistributing `ketch-core`), the CLA is on and named in CONTRIBUTING, the GPL wording is the same everywhere (with ru/uk), and sponsorship is set up or explicitly declined.

### K916. Raise the repository's community health

GitHub's community profile scores 50%: no issue or pull-request templates, no SECURITY.md, no code of conduct, Discussions off, while the README asks for testing volunteers. A security policy is also a trust signal for a tool that verifies downloads.

Done when issue forms exist for bugs (asking for `ketch doctor --json` and `ketch why`), "wrong asset picked" and package requests, plus a PR template, `SECURITY.md` and a code of conduct, and Discussions is on.

#### Process and tooling

### K917. Retire workspace-hack or keep it in step automatically

`cargo hakari generate --diff` is the most frequent CI failure (27 runs in 20 days, every Dependabot cargo PR) yet not required, so drift merges. It also unifies uniffi, aws-lc-rs, tokio and clap into every member: the CLI compiles uniffi only through it, and `scripts/core-features.sh` builds with it on, so the slim-core builds of R19 are not really proven.

Done when, after a measured build-time comparison, the creator picks: remove workspace-hack, or exclude `ketch-ffi`/`ketch-capi` from unification, regenerate it on Dependabot PRs automatically and make `hakari` required. Either way `cargo tree -p ketch -i uniffi` is empty and the feature check runs without the hack.

Depends on K903.

### K918. Pin cargo-nextest and add a nextest profile

Every documented test command needs nextest, but `mise.toml` does not pin it: `cargo nextest run` fails locally with "No version is set for shim: cargo-nextest". There is no `.config/nextest.toml`, and the Windows run reports leaked children (`upgrade_stops_a_process_holding_the_binary_when_yes`, `lists_a_child_running_a_cmd_script`).

Done when nextest is pinned in `mise.toml` and `toolchain.md`, and a `ci` profile sets slow and leak timeouts (a leak fails) and uploads JUnit output.

#### Desktop apps

### K919. Decide the desktop ship order and maintenance budget

About 15,600 lines of Swift, C# and Vala for one maintainer, and fixes already land unevenly (B129–B131 reached one or two apps). Linux has D17–D19 open and needs C ABI safety work first.

Done when the creator's choice is recorded in `docs/research-desktop-platforms.md`, `plan.md` and `roadmap.md`: (a) macOS, then Windows, Linux frozen at the fake shell; (b) all three, with every behaviour fix landing in all apps in one PR; or (c) Linux on gtk4-rs linking `ketch-core`, dropping `ketch-capi`.

### K920. Test the macOS live core against a scratch root

`LiveKetchCore.swift`, the adapter the first notarised build ships, has no test and no recorded manual pass; Windows has `FfiKetchCoreTests` doing exactly that.

Done when Swift tests drive `LiveKetchCore` offline through install, uninstall, a binary choice, cancel, `Busy` and `NotFound` on a scratch root, the `macos-app` job runs them, and a manual pass is recorded in F13.

### K921. Port the B129–B131 store fixes to the Linux app

On the real core an offline machine or a fast typist will hit, in the Linux app, bugs already fixed elsewhere: search results have no generation guard (`store.vala:64-69`, B130), a second operation is dropped silently (`store.vala:151-154`, B131), and a background refresh failure raises an alert (`store.vala:51-52`, B129).

Done when the Linux store behaves as macOS does in all three cases, with `store-test.vala` cases named after each B id.

### K922. Make Doctor actionable in the desktop apps

Doctor's PATH check returns CLI prose ("Run `ketch path install`") and reads this process's PATH (`shell.rs:302-327`, `config.rs:407-413`), so a GUI started from Finder or the Start menu always warns "not in this shell". `doctor_fix` is exported and unused.

Done when each check carries a machine-readable fix kind (none, `path_install`, `new_shell`), `doctor.json` is regenerated, and the apps offer a Fix button for `path_install` and hide "open a new shell".

### K923. Add contract scenarios for the unused core calls

`ketch-ffi` exports 19 calls; the apps and `desktop/contract` cover 8. `pin`, `unpin`, `rollback`, `prune`, `history`, `info`, `path_status`, `path_install`, `doctor_fix` and `registry_refresh` have no scenarios, so no fake can show a screen for them.

Done when `crates/ketch-ffi/tests/contract.rs` generates scenarios for each (rollback: ok, nothing to roll back to, binary choice; path install: dry run), and all three fakes decode them.

### K924. Set up PATH from the macOS app on first launch

No app calls `path_status` or `path_install`, so tools installed from the GUI do not run in a terminal until someone runs `ketch path install`, and someone who installed only the `.dmg` has no CLI to run it with. A Finder launch never sees a shell-profile `KETCH_ROOT`, so app and CLI can silently use different roots.

Done when a first-run sheet shows the root, the bin dir and the per-shell status, previews `path_install(dry_run: true)` and applies it on consent, Settings can reopen it, and store tests run on the new scenarios. Windows and Linux follow as their own tasks once this shape is settled.

Depends on K922, K923.

#### Core architecture and code

### K925. Carry one cancel token per operation

There are two tokens, `Ctx::cancel` (`report.rs:335`) and `InstallRequest::cancel` (`install.rs:73`). `ketch-ffi`'s `ctx()` never sets the caller's token, so a desktop upgrade's network-heavy check phase (`fill_latest`) cannot be cancelled, and `outdated` takes no token at all.

Done when the token lives only on `Ctx`, the FFI context carries the caller's token, `outdated` takes one, and a test shows a cancelled token stops upgrade planning with `Cancelled`.

### K926. Plan upgrades in one place in the core

Three paths answer "what is outdated?" differently: CLI `upgrade` checks serially and fresh (`src/cmd/pkg.rs:259-425`), CLI `outdated` runs its own uncancellable thread pool (`src/cmd/query.rs:288-392`), FFI uses the TTL-cached `listing::fill_latest`. The CLI and the GUI can disagree on "latest", and the command bodies break the thin-`cmd/` rule. The same hand-rolled worker pool appears in `install.rs` and `listing.rs` too.

Done when one core `upgrade::plan` (parallel, cancellable) returns upgrades, unreachable and skipped packages for CLI `upgrade`/`outdated` and FFI `upgrade`/`outdated`, one ordered cancel-aware pool helper replaces the three, and `pkg::upgrade` is under 80 lines.

Depends on K925.

### K927. Keep CLI wording out of core errors

`error.rs:195-231` hints name CLI flags ("Use --force", "Pass --yes") and `ketch-ffi` appends them to every desktop message. Already installed, update available, pinned, no compatible asset, source conflict and rate-limited all collapse to `Other`, so an app cannot offer "Update" or "Unpin".

Done when hints and exit codes belong to the binary (or `hint()` takes an audience), `KetchError` gains `AlreadyInstalled`, `UpdateAvailable`, `Pinned`, `NoCompatibleAsset`, `SourceConflict` and `RateLimited`, contract scenarios are updated, and a test asserts no FFI message contains `--`. Mapping the new kinds in each app may be a follow-up.

### K928. Probe source plugins only when needed

`SourceRegistry::load` spawns every plugin with `capabilities`, one after another with a 30-second timeout, on every install, list, outdated, info and changelog, and on each FFI call. One hung plugin delays every command, GitHub-only ones included, and the apps pay it on each poll.

Done when discovery only lists candidates, a plugin is probed on the first lookup of its scheme (or capabilities are cached by path, mtime and size), `ketch plugin list` still probes all, and a test shows a sleeping plugin does not slow `ketch list` of GitHub packages.

### K929. Pass the platform in, and fault-inject placement

14 direct `platform::host()` calls (7 in `install.rs`) mean `FakePlatform` only reaches asset choice. The riskiest code, commit and rollback after a failed placement (B79, B104, B123), can only be tested on each OS.

Done when the platform travels in `Ctx` (or a pipeline struct, defaulting to `host()`) and a fault-injecting fake proves on every host that a link failure after the store swap restores the store and the old links.

### K930. Move OS-neutral placement code into one module

`platform/unix.rs` and `platform/windows.rs` duplicate bin resolution, executable discovery, `cli_targets`, `writable`, `unplace` and extra linking (71 shared six-line windows), and `macos.rs` repeats part of `unix.rs`. Each copy compiles and is tested only on its own OS, so a fix to one silently misses the other. Extends the `move_into_store` idea in `ideas.md`.

Done when a `platform/common.rs` compiled on every OS holds the shared code with small hooks for name matching and symlink-versus-copy, both variants are unit-tested on every host, and the duplicate-window count is under 10. Split into the two subtasks below.

### K930.1. Share bin resolution and executable discovery

First half of the common placement module: `resolve_bin_specs`, `payload_executable_entry`, `discover_executables` and `cli_targets` move out of `unix.rs` and `windows.rs`.

Done when both platforms call the shared functions, tests for both naming rules run on every host, and behaviour is unchanged.

### K930.2. Share placement, unplace and extra linking

Second half: `writable`, `unplace`, `link_planned_extras` and the shared part of `macos.rs`.

Done when both platforms call the shared code and the duplicate-window count is under 10.

Depends on K930.1.

#### Product and onboarding

### K931. Lock assets and hashes for several targets

`docs/LOCKFILE.md` guarantees a hash only on the target that wrote the lock, so a lock written on a Mac gives Linux CI nothing pinned. mise records a URL and checksum per platform.

Done when `ketch lock --target …` (or a `lock_targets` config key) writes per-target `asset` and `sha256`, `ketch sync` enforces them on each target, and `docs/LOCKFILE.md`, the schema and ru/uk are updated.

Depends on K910.

### K932. Show verification status and add `ketch audit`

Verification is the selling point but invisible: `ketch list` never shows whether a checksum or signature was verified, and `require_checksums` defaults to off. Teams need to find unverified installs.

Done when `ketch list` shows verified, signed or unverified per package, and `ketch audit [--json]` exits non-zero when any package is unverified.

### K933. Verify GitHub artifact attestations for client apps

Publisher identity is checked only when a manifest declares a `trust` table. GitHub artifact attestations exist for many repositories (`GET /repos/{o}/{r}/attestations/sha256:…`), and sigstore is already a dependency; `ideas.md` covers only ketch's own release.

Done when, for an asset whose digest has an attestation, ketch verifies the bundle against the repository identity and records the result in state, `ketch info` and `ketch audit`; it degrades gracefully offline and fails closed only under `require`.

Depends on K932.

### K934. Work behind corporate networks

ureq is built on `webpki-roots` only, so a TLS-intercepting corporate CA fails every request; proxy support (`proxy-from-env`) and `KETCH_GITHUB_API` for GitHub Enterprise exist but are undocumented, and private-repo token scopes are mentioned only in a source comment.

Done when the OS trust store is used (platform verifier), CONFIGURATION.md has a "Corporate networks" section (proxy, GHE, private repos) in en/ru/uk, and a test covers a custom root.

### K935. Ship a GitLab and Forgejo source plugin

The site promises GitLab, Gitea and internal servers, but only `examples/ketch-source-example` exists. mise and ubi cover GitLab and Codeberg.

Done when a maintained plugin speaks the current `PROTOCOL_VERSION` for gitlab.com and Codeberg, installs with ketch, is in the registry, and has an end-to-end test against a fixture server.

### K936. Install Linux AppImages as apps

"Apps as well as binaries" holds only for macOS `.app`; `platform/mod.rs:493` rejects `.appimage`, the format most Linux GUI apps ship.

Done when an AppImage declared as an app is placed with a `.desktop` entry and icon in the user XDG directories, recorded in state, removed on uninstall, and covered by a Linux end-to-end test.

### K937. Import installed tools from Homebrew and winget

`ketch import` takes one name at a time, but switching package managers is a bulk decision; "23 of 40 tools can move" converts users.

Done when `ketch import brew --installed` and `ketch import winget --file export.json` convert what they can, print a table of what was skipped and why, honour `--dry-run`, and fixture tests cover both.

### K938. Publish ketch to a Scoop bucket

ketch ships only through its Homebrew tap and the scripts; Windows developers expect `scoop install`. The `ketch` crate name on crates.io is still free.

Done when the release publish step generates `ketch.json` with its checksum into a `pyrlyn/scoop-bucket`, and the creator's decision on reserving the crates.io name is recorded.

### K939. Correct overstated claims and widen the comparison

README says uninstalling "leaves nothing behind" and the site that nothing is written elsewhere, yet ketch writes to `/Applications`, man and completion directories, shell startup files, PowerShell profiles and cmd AutoRun (all recorded and reversible). The site title still says "listepo tools"; `docs/COMPARISON.md` covers only Homebrew, apt and mise.

Done when the claims are accurate, COMPARISON adds aqua, eget, ubi, dra, stew, cargo-binstall, Scoop and winget with sources, a "no telemetry" statement is added, and ru/uk are updated.

#### Process and tooling

### K940. Fix AGENTS.md drift and move the release runbook out

AGENTS.md (798 lines) promises an auto-revert path that does not exist, says CI installs Rust with jdx/mise-action (it uses pyrlyn/ci `setup-rust`), says `release.yml` tags (dist never tags), lists "Seven things" and gives six, keeps the obsolete untagged-v0.10.0 paragraph, and calls org secrets repository secrets. About 250 lines are release runbook. Agents follow this file literally.

Done when each item is fixed (together with the `ideas.md` docs-drift entry) and the Releasing and app-release sections move to a maintainer doc linked from AGENTS.md, exempt from ru/uk like `docs/qa/`.

#### CI and release

### K941. Run desktop CI once, and only when it can change

`ketch-cs` and `ketch-win-app` both run `scripts/csharp.sh`; `ketch-ffi` and `macos-app` both build the XCFramework; the pipeline's dotnet job reruns `Ketch.AppCore.Tests`; the `--features tui` pass reruns about 250 tests per OS. Six desktop jobs run on every non-docs PR, although the config action already computes a changed-file verdict.

Done when there is one macOS desktop job and one Windows .NET job, a `desktop` output (crates, desktop, scripts, Cargo.lock, mise.toml) turns desktop jobs into reporting no-ops otherwise, and the tui pass runs only tui-dependent tests or one OS.

Depends on K903, K904.

#### Process and tooling

### K942. Keep one dependency bot and drop inert tools

Renovate's preset covers cargo, actions, npm and mise, overlapping Dependabot (one onboarding PR against 17). CodeRabbit has auto-review off and its issue workflow has filed nothing. release-plz runs nowhere but keeps a config, a test and 11 AGENTS.md mentions alive.

Done when Renovate is limited to `mise` (or Dependabot dropped), CodeRabbit is either on or removed, and release-plz is retired with its mentions or pinned and used.

#### CI and release

### K943. Commit mise.lock and install tools with checksums

There is no `mise.lock`; about 20 tools, some git-sourced, install unverified, and the jactionlint baseline accepts unverified `sh` runs of the cargo-dist installer and rustup. The release build runs on these tools.

Done when `mise.lock` is committed, CI installs in locked mode, and the two baseline entries are gone.

### K944. Dry-run the desktop release workflows

`release-apple-desktop.yml` and `release-windows-desktop.yml` have never run; `SUPublicEDKey` is a placeholder and the Windows certificate secrets do not exist. The first real run will meet months of drift.

Done when each workflow has a `dry-run` input that builds and signs with a throwaway certificate and Sparkle key, skips notarisation and publishing, and runs weekly or on changes to its files.

### K945. Run the fuzz targets on a schedule

`fuzz/` has 11 targets over the trust-boundary parsers (archives, manifests, checksums), but CI only type-checks `cfg(fuzzing)`; nothing ever fuzzes.

Done when a weekly Linux job runs each target about two minutes on nightly with a cached corpus, uploads crashes and opens an issue on one.

#### Desktop apps

### K946. Pin, roll back and show history in the desktop apps

All three apps show held packages but cannot pin or unpin, roll back or show history, although `pin`, `unpin`, `rollback`, `history` and `info` are exported. The core work is done; only protocol and UI are missing. One subtask per app.

Done when every app's package page has Pin/Unpin, "Roll back to X" when a retained version exists, and a History tab fed by `history(package, 50)`, with store tests on the new scenarios.

Depends on K923.

### K946.1. macOS: pin, roll back and history

The macOS share of the parity task.

Done when the macOS package page has the three features and store tests on the scenarios.

Depends on K923.

### K946.2. Windows: pin, roll back and history

The Windows share of the parity task.

Done when the Windows package page has the three features and `Ketch.AppCore.Tests` covers them.

Depends on K923.

### K946.3. Linux: pin, roll back and history

The Linux share of the parity task.

Done when the Linux package page has the three features and `store-test.vala` covers them.

Depends on K923.

### K947. Windows: check for updates periodically

macOS refreshes on a timer and has a prerelease setting; Windows refreshes only at launch and on the tray's "Check now", so a tray app started at login announces updates once. Every Windows refresh failure raises an alert (the Windows side of B129).

Done when a timer refreshes at an interval set in Settings (skipped while an operation runs), a background failure is logged, the prerelease switch reaches `InstallOptions`, and `Ketch.AppCore.Tests` covers it.

### K948. Windows: Narrator and keyboard pass

No XAML file sets `AutomationProperties`: row buttons are announced as a bare "Uninstall" or "Update", Doctor severity is a glyph only, and there are no keyboard accelerators. macOS met this bar in D9.

Done when row actions are named "Update {name}" and so on, status has a text equivalent, Ctrl+F, F5 and Ctrl+, exist, and an Axe.Windows or FlaUI smoke test runs in `ketch-win-app` on the fake core.

### K949. Show token status and rate limits in the apps

A GUI launched from the dock has no `GITHUB_TOKEN` and usually no `gh` on PATH, and hourly update checks spend anonymous quota; the rate-limit hint tells it to set an environment variable. `config()` already reports whether a token is set; no app reads it.

Done when each app's Settings shows whether a token is set and where it comes from, and a rate-limit error renders as an actionable banner with the reset time.

Depends on K927, K912.

### K950. Windows: choose and wire the app's update channel

D14 shipped the release workflow but left the update route open; without one every release after the first reaches nobody who already has the app. Options: an in-app check of `desktop-windows-v*` releases that notifies and opens the download page (reuses D13's notifications), Velopack (in-place, changes the app), winget (needs a Microsoft account).

Done when the creator's choice is recorded and the chosen route works end to end against a draft release, covered by `tests/desktop_windows_release.py`.

### K951. Decide how to stop desktop logic drifting apart

Three fakes and three scenario decoders (about 2,000 lines) re-implement one replay and have already drifted from the real core (B111, a declined choice, `stopProcesses` missing on macOS). The progress fold, update notices and busy/retry policy exist per app; the revisit trigger of research decision 5 has fired. Options: a scripted `KetchCore` inside `ketch-ffi` that replays the scenarios (apps test their live adapters on it; plain-.NET tests then need the native library); exporting policy (`ActivityFold`, `UpdateNotices`) from `ketch-ffi`; or a shared store-behaviour spec every app's tests carry.

Done when the creator has picked, and a spike lands the chosen piece in Rust with one app adopting it.

Depends on K919.

### K952. Linux: Orca and keyboard pass

Row buttons carry a bare verb (`window.vala:140`), status icons have no accessible label, and the only shortcut is Ctrl+,. Cheapest before D17 adds screens.

Done when buttons and status icons have accessible labels, Ctrl+F and Ctrl+R exist, and a manual Orca pass is recorded.

#### Core architecture and code

### K953. Split the oversized modules along their seams

Review and merge conflicts concentrate in a few files: `install.rs` (about 2,000 lines of code; `prepare` 274 lines, `commit` 215), `src/ui.rs` (1,365), `shell.rs` (1,504, with an inline `windows_env`), `model.rs` (about 1,600, 35 public types). One subtask per file, each a pure move with no test changes.

Done when every subtask has landed.

### K953.1. Split install.rs

Seams: verification, store housekeeping, upgrade queries, and binary picking (which belongs in `bin_choice.rs`).

Done when `install/{verify,store,upgrade}.rs` exist and `prepare` is split into helpers under 80 lines.

Depends on K926, K929.

### K953.2. Split src/ui.rs

Styles, line kinds, prompts, progress, tables and the `Terminal` reporter share one file.

Done when they live under `src/ui/` and output is unchanged.

### K953.3. Split shell.rs and model.rs

`shell.rs` carries an inline `mod windows_env`; `model.rs` holds 35 public types.

Done when `shell/windows_env.rs` and a `model/` directory exist with no behaviour change.

### K954. Trim the core's public surface and dead code

33 of 38 core modules are `pub`, and 167 of 510 public items are named nowhere outside the core; `allow(dead_code)` keeps `Platform::id`, `app_bundle_extension`, `ReleaseSelection::rejected`, `SourceRegistry::schemes` and `Config::config_file` alive, and `ketch why` hard-codes `rejected: Vec::new()`. AGENTS.md's "everything else stays `pub(crate)`" has drifted. Extends R15.

Done when the flagged items are `pub(crate)` or gone, `rejected` is wired into `ketch why` or deleted, and `unreachable_pub` warns in the core where privacy allows.

### K955. Build the resolver once per batch

`manifest.rs:63` says the `Resolver` is built once per command, but `prepare` builds one per package and FFI `search` one per call, each re-parsing the built-in registry, every registry manifest and every user manifest. The cost grows with the registry.

Done when the resolver is passed into `prepare` and `batch`, and a test counts one load per batch.

Depends on K929, K905.

### K956. Use one HTTP stack for push and everything else

`ketch registry push` runs octocrab on its own tokio runtime, without `http.rs`'s timeouts, body cap or `--verbose` log, and its errors lose exit code 7. `Error::Network` carries `Box<ureq::Error>`, tying the public error to ureq 2. 16 call sites build their own agent although `http.rs` says one per process. `push` already sits behind an `Api` trait.

Done when the GitHub `Api` implementation runs on `Http`, octocrab and tokio leave the `push` feature, `Error::Network` no longer names ureq, and agents are shared per operation. Bumping ureq stays a separate decision.

### K957. Find a lighter Sigstore verifier

The `trust` feature adds 177 crates: sigstore 0.14's `verify` pulls fulcio and rekor clients (reqwest, tokio, hyper, webbrowser, openidconnect, rsa with the ignored B138 advisory) for what is an offline bundle check against an embedded root, and `ketch-ffi` carries it into every app.

Done when a research note with primary sources compares options, and either an upstream change splits offline verification from the network clients or a replacement is chosen, with crate count and binary size measured.

### K958. Optimise hot dependencies in release builds

`[profile.dist]` inherits `dev` on purpose (#279), so released binaries hash, decompress and verify every download unoptimised; the cost is not measured.

Done when hash-plus-extract time of a large asset and tarball size are measured, and, with the creator's OK, per-package `opt-level` overrides apply to sha2, flate2, lzma-rust2, bzip2, zip, pgp and sigstore while ketch's own crates keep debug info.

#### Product and onboarding

### K959. Publish a package catalogue on the site

People browse before they install, and a catalogue helps search ranking; the site has a landing page and docs only.

Done when the Hugo build generates a searchable page (Pagefind) from the registry tree.

Depends on K905.

### K960. Run a tool once without linking it

Trying a tool, or running it once in CI, should not change PATH; pkgx owns that use case. The unlinked install path already exists (`--no-link`).

Done when `ketch run owner/repo -- args` installs into the store unlinked (or reuses a copy), execs with the exit code passed through, `ketch prune` cleans such copies, and an end-to-end test covers it.

### K961. Group `ketch --help` and greet a bare `ketch`

About 30 top-level commands sit in one flat list without examples, and a bare `ketch` prints a clap error.

Done when `ketch --help` groups commands (install, inspect, reproduce, registry, environment, self) with three examples, and a bare `ketch` prints a short getting-started.

#### CI and release

### K962. Mutation-test the trust-boundary guards

Coverage is measured but nothing shows whether the tests would notice a weakened `safe_member_path`, `sanitize_component`, `validate_repo`, `Manifest::validate`, `hooks::allowed` or `changelog::sanitize`.

Done when a scheduled `cargo-mutants` run scoped to those functions reports no missed mutants, or each gap has become a task.

---

## Optimisation and bug review (2026-10-23)

A read-only review of `main` at `f83a678` for speed, build cost, duplication and bugs. Items already planned above (K902–K904 for the red and cancelled `ci` runs, K925, K953, K955–K958) are not repeated. Paths are under `crates/ketch-core/src/` unless shown otherwise; line numbers are as of the review.

#### Core architecture and code

### K963. Run install hooks outside the batch state lock

In `install::batch` (`install.rs:923-933`) each worker calls `commit` while holding the `live` state `Mutex`, and `commit` runs the `before_install` and `after_install` hooks (`install.rs:730`, `:849`) and places the files. One slow hook or a large copy stalls every other worker, so `-j` only helps the download half.

Done when `commit` takes the lock only to re-read and update the state, hooks and placement run outside it, and a test with a sleeping hook shows two packages committing concurrently.

### K964. Stream file contents in sha256_tree

`source::local::sha256_tree` (`source/local.rs:434`) reads every file with `fs::read` before hashing, so locking or verifying a local bundle holds its largest file in memory at once; `http::sha256_file` already streams through a 128 KiB buffer.

Done when the length comes from the metadata, the payload is fed to the hasher through a reused buffer, and the existing `sha256_tree` hashes are unchanged.

### K965. Share one HTTP agent between sources

`Http::new` and `Http::anonymous` (`http.rs:40-60`) build two copies of the same `ureq` agent, and `Sources::load` (`source/mod.rs:307`, `:329`) and the registry update (`registry.rs:63`) each build their own, so connections and TLS sessions to `api.github.com` are not reused within one command. Extends K956.

Done when one builder function sets the user agent and timeouts, and one agent per command is shared by every source and the registry download.

#### CI and release

### K966. Deduplicate crate versions in Cargo.lock

`Cargo.lock` holds two or three versions of 48 crates (`cargo tree -d`): `digest` 0.10/0.11, `hmac`, `pbkdf2`, `cipher`, `base64` 0.21/0.22/0.23, `darling` 0.20/0.21/0.24, `nom` 7/8, `getrandom`, `hashbrown`, `itertools` and more, mostly pulled by sigstore, pgp and octocrab. Each copy adds compile time and binary size. Related to K957 and K958.

Done when the duplicates are listed by the crate that pulls each one, the ones fixable by a bump or a feature are fixed, the rest are recorded as accepted, and a `cargo deny` `multiple-versions` check warns on new ones.

#### Core architecture and code

### K967. Keep a binary at the ketch path during self-replace

`replace_binary` (`self_update.rs:613-630`) renames the running `exe` aside, then copies the new one in. Between the rename and the end of the copy there is no file at `exe`, so a shell, completion or second `ketch` started then fails with not-found, and a crash there leaves only `ketch.old`.

Done when, on Unix, the new binary is copied to a sibling temp file, made executable and renamed over `exe` (atomic), the aside stays for rollback, and Windows keeps its rename-aside path with a test for each.

### K968. Tell a cancelled or failed latest lookup from an unreachable one

`listing::latest` (`listing.rs:440`, `:452`) marks every row without an answer `Latest::Unreachable`, whether the lookup failed, was rate-limited or was skipped because `Cancel` fired in `ask_all`; the error itself goes to `debug` only, so `ketch outdated` shows unreachable with no reason.

Done when a skipped lookup reads `NotChecked`, a failed one carries its error (rate limit named as such) in the row, and a test covers cancel mid-batch.
