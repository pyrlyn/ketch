# ketch
https://github.com/pyrlyn/ketch
Catch releases straight from GitHub — a package manager for GitHub-released binaries and apps.

## Cloud review findings (2026-10-08)

New bugs, dead code and moves from a read-only Cursor cloud review of `main` at `077eca5` (agent `bc-89f78a26-1501-5547-96b8-3ef0603e653a`; full report: `cloud/ketch.md` in the private `listepo/roadmap` repo). Bugs take B75–B88; dead code and moves take R14–R19. Ordered P0, P1, P2. **confirmed** means seen in the tree; **suspected** means plausible from the code but not proven. Paths are under `crates/ketch-core/src/` unless shown otherwise; line numbers are as of the review. None of these is in the task table yet: to take one, add its row and a card.

| ID | Priority | Kind | Status | Where | Fix |
| --- | --- | --- | --- | --- | --- |
| B75 | P1 | bug | confirmed | `scripts/cask.sh:81-86`; tap run 37154989737 | The cask postflight `run /bin/sh` does not pass a GitHub token: the step had `GITHUB_TOKEN`/`KETCH_GITHUB_TOKEN` and still hit `API rate limit exceeded`. Forward the token into the cask `run` env, or smoke-test `ketch self install` outside Homebrew's isolated postflight. |
| B76 | P1 | bug | fixed | `registry.rs:117-120` | After the live registry is moved aside, the rollback is `let _ = rename(aside, registry_dir)`; if it fails, `registry_dir` is left empty. Surface the failure and copy-restore. |
| B77 | P1 | bug | fixed | `self_update.rs:932-937` | Uninstall runs `remove_file(exe)?` after packages are gone and state is saved; a busy Windows `.exe` aborts the rest. Warn and continue, like the other uninstall steps. |
| B79 | P1 | bug | fixed | `install.rs:691-729`; `platform/unix.rs:423`; `platform/windows.rs:502` | `place` swaps the store, then links; `ScopedDir` is off when `existing.prefix == store_dir`, so a failure after the swap leaves state pointing at a tree that is not on disk. Restore `.old` on error. |
| B80 | P2 | bug | confirmed | `extract/archive.rs:397-399`; extractor order in `platform/linux.rs:62-66` | `TarXzExtractor` claims any `XZ_MAGIC` and is listed before `GzFileExtractor`; unlike gzip (`:508-509`), xz/bz2 never peek for ustar, so a lone `.xz` file fails as a tarball. Peek the decompressed head; add lone-file xz/bz2 extractors. |
| B81 | P2 | bug | confirmed | `extract/archive.rs:280`, `:488` | Extraction keeps `mode & 0o7777`, so setuid/setgid/sticky bits from an archive survive. Mask `0o7000`. |
| B82 | P2 | bug | confirmed | `source/github.rs:310-312` | Aggregate checksum files are keyed by basename; a later duplicate overwrites an earlier one. Reject or flag a basename that maps to two hashes. |
| B83 | P2 | bug | confirmed | `source/github.rs:427-431` | A sidecar checksum takes the first sorted entry (`BTreeMap::into_values().next()`), not the asset's. Look up the asset name first; fall back to a single entry only when unambiguous. |
| B84 | P2 | bug | fixed | `http.rs:118-186` | Downloads stream with no maximum size. Add a configurable download/unpacked cap and abort over it. |
| B85 | P2 | bug | confirmed | `registry.rs:61-67`; `listing.rs:390-426` | `registry::update` passes a fresh `Cancel::new()` to `Http::download`, and the parallel `ask_all` never checks for cancellation. Thread the caller's `Cancel` through both. |
| B86 | P2 | bug | suspected | `source/plugin.rs:159-168` | A plugin's `asset.url` and `headers` go to `Http::anonymous` with no scheme or host check, though plugin JSON is untrusted. Allow `https:` only; drop `Authorization` and hop-by-hop headers. |
| B87 | P2 | bug | fixed | `source/local.rs` `sha256_tree` | The local tree hash framed only `path\0bytes`. It now frames kind, path, permission bits and length, and hashes a symlink's target. A lock from before this framing does not match until `ketch lock`. |
| B88 | P2 | bug | suspected | `process.rs:461-494` | After `offer_to_stop`, ketch waits 200 ms before `/F`; the install can still hit a locked `.exe`. Wait, with a bound, until the pid is gone. |
| R14 | P2 | dead code | confirmed | `scripts/b32_patch.py:8` | The script opens `src/self_update.rs`, which has moved, and nothing references it. Delete it. |
| R15 | P2 | dead code | confirmed | root `Cargo.toml:61` (`dirs`); `manifest.rs:137-148` (`Resolver::aliases`); `model.rs:596-599` (`Release::asset`); `platform/mod.rs:49-50` (`Placement::version`); `model.rs:329-331` | Drop the unused root `dirs` dependency, `Resolver::aliases` (or wire it to `__complete`/search), `Release::asset` and the never-read `Placement::version`; remove the stale `allow(dead_code)` on `PackageSpec::raw` (read at `state.rs:162`). |
| R16 | P2 | dead code | confirmed | `fuzz/seed.sh:42` | The seed script reads `$root/src/builtin.toml`; the file is `crates/ketch-core/src/builtin.toml`. Fix the path. |
| R17 | P2 | dead code | confirmed | `shell.rs:561-1092` | 23 `cfg_attr(not(windows), allow(dead_code))` on the Windows PATH helpers. Put them in one `#[cfg(any(windows, test))]` module. |
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
| B94 | P1 | bug | confirmed | `install.ps1:310` | `Join-Path $TempDir 'ketch' $BinaryName` passes three positional arguments; Windows PowerShell 5.1, the shell `irm \| iex` runs in, has no `-AdditionalChildPath` and throws after extraction. CI only runs `pwsh ./install.ps1 --help` (`ci.yml:105-110`). Nest the calls and add a CI step that runs the script under `shell: powershell` past extraction. |
| B95 | P1 | bug | fixed | `shell.rs:688-730` (`read_user_path`, `write_user_path`); `install.ps1:105-119` | The user PATH is read expanded via `GetEnvironmentVariable('Path','User')` and written back as `REG_SZ`, so every `%USERPROFILE%`/`%JAVA_HOME%` entry is frozen; `read_user_path` also decodes stdout with `from_utf8_lossy` without the UTF-8 `OutputEncoding`, corrupting non-ASCII entries. Hits `path install/uninstall`, `doctor --fix`, `self uninstall` and the installer. Read and write `HKCU\Environment\Path` through `Microsoft.Win32.Registry` with `DoNotExpandEnvironmentNames`, keep the value kind, and go through the UTF-8 `powershell()` helper. |
| B96 | P2 | vuln | confirmed | `.github/workflows/ci.yml:11-12, 55, 98, 307, 449` | Jobs only test `pull_request.draft != true`, which is true on `issue_comment`, and `hakari` has no `if`: any comment on any issue or PR runs the whole matrix on `main` with `secrets: inherit` and macOS minutes (run 38115907682). Comment runs share the `ci-refs/heads/main` concurrency group, so a queued one replaces a pending push-to-main run (38115904555). Gate every job but `review` on `github.event_name != 'issue_comment'`, or move `/review` into its own workflow. |
| B97 | P2 | vuln | fixed | `.github/workflows/release.yml:117-120`, produced by `scripts/dist-generate.sh:80-87` | `CODESIGN_CERTIFICATE`, its password and `GH_TOKEN` (with `contents: write`) are job-level env on all four build runners, so every dependency `build.rs` and proc-macro can read the Developer ID `.p12`. Patch the env so the certificate reaches only the macOS signing step, and drop `GH_TOKEN` where builds do not need it. |
| B98 | P2 | bug | confirmed | `install.ps1:3`, `:149-326` (every `exit`) | Under `irm … \| iex` the script runs in the caller's session: any error path's `exit 1` closes the user's PowerShell window before the message can be read, and `$ErrorActionPreference = 'Stop'` and script variables leak into the session. Wrap the body in a function or scriptblock, `throw`/`return` instead of `exit`, restore the preference. |
| B99 | P2 | vuln | confirmed | `extract/archive.rs` ~604 (zip `io::copy`), 462-469 (tar `unpack_member`), ~643, ~659 (lone gz/xz/bz2) | No extractor bounds the bytes written or the number of entries; a small gzip/bzip2 bomb or a zip with millions of tiny entries fills the disk under the root. B84 caps only the download. One shared extraction budget (bytes and entries, ratio of compressed size plus an absolute ceiling) across every extractor. |
| B100 | P2 | vuln | confirmed | `extract/archive.rs` ~595 (zip symlink branch) | A zip symlink's target is read with an unbounded `read_to_string`; a symlink entry whose body is gigabytes of highly compressed bytes is decompressed into memory. Read through `take(4097)` and reject targets over 4 KiB. |
| B101 | P2 | bug | fixed | `extract/archive.rs:462-469`, `:420-423` | `payload_mode` is applied to directories as they are written. A `0o555` directory before its children fails extraction with EACCES (Nix and some reproducible tarballs; GNU tar copes); one after its children stays read-only, so `remove_any` (`platform/unix.rs:46`) fails on uninstall and upgrade and temp dirs leak. Apply directory modes after all members are written and always force `u+rwx` on directories. |
| B102 | P2 | bug | suspected | `extract/archive.rs:71-127` (`materialize_symlink_as_copy`, `copy_dir_contents`) | On Windows without symlink rights, a member `d -> .` or `sub/d -> ..` passes `check_link_target` and is materialised by copying its target into itself while `WalkDir` walks it: endless recursion or a full disk. Refuse to materialise a directory link whose target is an ancestor of the link, or snapshot the entry list first. |
| B103 | P2 | bug | fixed | `install.rs:262` (`prepare`), `install.rs:647` (`commit`); `lockfile.rs` plan | State is keyed by name and nothing compares `existing.source` with `manifest.source`. `ketch install otherowner/ripgrep --yes` silently replaces `BurntSushi/ripgrep`, takes over its `rg` link, runs update hooks and mixes retained versions from two sources; `ketch sync` plans a same-name, other-source lock entry as missing and does the same. Refuse in `prepare` when the sources differ and point at `--name`. |
| B104 | P2 | bug | fixed | `platform/unix.rs:131, 473`; `platform/macos.rs:263, 430`; `platform/windows.rs:404, 543` | Placement clears each occupied destination (old symlink, copied `/Applications/X.app`, Windows bin copy) right before writing the new one; on a later failure only the new links are unplaced and the store restored, so the old version's links and app copy are gone while state still records them. A `copy_tree` of a large `.app` failing on a full disk loses the working app and leaves an unrecorded half copy. Move occupied destinations aside and restore them on failure. Adjacent to B79, which covers only the store. |
| B105 | P2 | bug | confirmed | `platform/windows.rs:198-203` (`still_placed`, `CopiedFile`), `:404` (`copy_binary`) | Ownership of a copied bin is proven by `same_contents(link, record.target)`. On a same-version reinstall the swap changes `record.target` first, so `copy_binary`'s second `destination_available` reports "already exists and was not installed by ketch": `ketch sync` cannot repair a drifted payload and `--force` reinstalls fail (extras too). Decide ownership once in preflight, or compare against `<store>.old`. |
| B106 | P2 | bug | confirmed | `platform/windows.rs:328`; `model.rs:1462` (`glob_match`) | A manifest `bin.path` written with `/` (`bin/tool.exe`) is matched against `rel.to_string_lossy()`, which uses `\` on Windows; `glob_match` does not normalise separators, so any manifest with a directory in `bin.path` fails with "payload does not contain it". Normalise both sides to `/`, add a Windows test, and check `extra::resolve_under`. |
| B107 | P2 | bug | confirmed | `self_update.rs:113` (`install_self`), `:402`, `:487` (`update`) | `expose_self_docs(...)?` runs after the new version is placed but before `state.save`. A user-owned completion or man file at the destination makes `clear_destination` fail: the new binary is on disk, `state.json` names the old one, and every later self upgrade repeats it. The `AlreadyInstalled` branch already warns instead. Save state first and make docs exposure a warning everywhere. |
| B108 | P2 | bug | fixed | `process.rs:323-342` (`cmd_hits`) | A process counts as an occupant when its command line contains the path as a plain substring. Upgrading `rg` with `--yes` SIGTERMs, then SIGKILLs, a running `…/bin/rga`, `…/rg-helper`, or any editor or shell whose arguments mention the path (Linux, Windows). Match whole NUL-split arguments against exact canonical paths and prefer exe and open-fd evidence. Separate from B88. |
| B109 | P2 | vuln | fixed | `state.rs:235-276` (`Lock::acquire_path`) | `create_new` and the pid write are not atomic: a second process that reads the still-empty file calls it stale and renames the live lock away. Two processes that judged one dead pid stale can each rename, the second over the first's fresh lock. Both then run, and the first `Drop` deletes the other's lock. Write the pid to a temp file and link it into place without replace, treat an empty lock as busy unless old, re-read before renaming, or use an OS advisory lock. |
| B110 | P2 | vuln | confirmed | `desktop/macos/Ketch/Store/KetchStore.swift:197-211`; `Views/PackageDetailView.swift:127` | Client release notes are parsed as inline Markdown into `Text(AttributedString)`, which keeps `.link` runs clickable through the default `openURL` for any scheme: `[Release notes](zoommtg://…)` or a phishing `https` link is one click away. Strip `.link` attributes or install an `openURL` handler that allows only `https`. |
| B111 | P2 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`; `desktop/windows/Ketch.AppCore/FakeKetchCore.cs:131`; `crates/ketch-ffi/src/lib.rs:166-171` | Discover's shelf calls `search("")`. Both fakes return the whole registry; the real core returns `Other("nothing to search for")`, so with the live core (R9) Discover opens with an error and an empty shelf. No contract scenario covers it. Decide the semantics in `ketch-ffi` (or add `browse`) and add a scenario. |
| B112 | P2 | bug | suspected | `desktop/windows/Ketch.App/Pages/InstalledPage.xaml.cs:63-74`; `MainWindow.xaml.cs:85-86, 124-151` | Uninstall and Install buttons stay enabled while an operation runs, so a confirm `ContentDialog` can be open when the core asks a decider question; WinUI allows one dialog, `ShowAsync` throws inside the fire-and-forget `AskAsync`, and the core thread blocks in `done.Wait()` until Cancel. Disable those buttons while `IsRunning`; catch dialog failures and answer `null`/`false`. |
| B113 | P3 | vuln | suspected | `extract/mod.rs:81-136` (`safe_member_path`); `config.rs:471` (`sanitize_component`); `model.rs:1116` (`usable_file_name`) | DOS device names (`CON`, `NUL`, `AUX`, `PRN`, `COM1-9`, `LPT1-9`, also with an extension), names with a trailing dot or space, and `<>:"\|?*` pass. An archive member, bin, tag or package named `nul.txt` writes to a device, `foo.` collides with `foo`, a store dir can fail to create. Reject these case-insensitively per component on Windows. |
| B114 | P3 | vuln | fixed | `extract/archive.rs:304-331` (`check_link_target`); readers `extra.rs:197`, `changelog.rs` `find_file`, `platform/unix.rs:61, 227`, `platform/windows.rs` `payload_executable_entry` | Link targets are checked lexically, ignoring links created earlier: `L -> .` then `N -> L/L/../../..` passes but escapes. `resolve_under`, `find_file`, `ensure_executable` (chmod follows links) and `payload_executable_entry` (checks one hop) then follow it, so local files outside the store can be linked into bin, man or completion dirs and `chmod u+rx`'d. Canonicalize every link in `finish_deferred_links` and require it under the destination; canonicalize in `payload_executable_entry`. |
| B115 | P3 | vuln | fixed | `extract/macos.rs:175-199` (DMG `copy_volume`), `:210-237` (PKG) | DMG copy skips only top-level symlinks; `ditto` keeps nested absolute or escaping links, and PKG payloads get no link checks. The B114 readers then follow them outside the payload. After the copy, walk the tree with `symlink_metadata` and run every link through the containment check. |
| B116 | P3 | vuln | confirmed | `source/plugin.rs:204-244` (`discover`) | Plugin discovery scans the plugin dir and every PATH entry, including ketch's own bin dir, and runs each `ketch-source-*` with `capabilities`. A client app that ships such a binary is executed by every later ketch command that loads sources. Discover only in the plugin dir, or exclude `cfg.bin_dir` and anything resolving into the store. |
| B117 | P3 | bug | confirmed | `changelog.rs:~99` (`from_file`), `src/cmd/query.rs:589` | The client changelog is read with an unbounded `read_to_string(...)?`: a multi-GB file or a link to `/dev/zero` exhausts memory, and a non-UTF-8 file makes `ketch changelog` fail instead of falling back to release notes. Require a regular file (`symlink_metadata`), read through `take` with a cap of a few MiB, decode lossily. |
| B118 | P3 | vuln | suspected | `install.rs` ~1420-1465 (`verify_checksum`); `src/cmd/pkg.rs:540` | When a release that was installed checksum-verified is followed by one without checksums (or an attacker controlling the release removes them), the upgrade installs with only the generic first-use warning; `checksum_verified` is never consulted. Refuse such a downgrade unless `--yes` or an explicit flag. |
| B119 | P3 | bug | confirmed | `shell.rs:1302-1310` (`block_span`) | It pairs the first BEGIN with the first END after it. A stray BEGIN without END (hand edit, crash mid-write) makes `splice` append a second block, and a later `unsplice` deletes every user line between the orphan BEGIN and the real END. Pair END with the nearest preceding BEGIN or refuse when marker counts differ. |
| B120 | P3 | bug | fixed | `shell.rs` (`marker_at_line_start`, `block_span`) | A marker line may end in `\r\n` or `\r`. The span consumes that ending, so a CRLF profile is updated in place instead of gaining a second block. |
| B121 | P3 | bug | suspected | `shell.rs:1377-1405` (`write`) | When a startup file is a dangling symlink (dotfiles repo not checked out), canonicalize fails, the raw path is used, and the temp-file rename replaces the user's symlink with a regular file. Resolve with `read_link` and write the target, or refuse. |
| B122 | P3 | bug | fixed | `src/ui.rs` (`BarProgress::start`) | The download label reaches `set_message` through `printable` before it is truncated, the same filter as every other status line. |
| B123 | P3 | bug | confirmed | `platform/{macos,unix,windows}.rs` `restore_retired_store`, from `relink`/`rollback` | In these paths `move_into_store` is a no-op and `sweep_swap_leftovers` does not run; if linking fails while a stale `<ver>.old` from an earlier interrupted swap exists, the error path deletes the live prefix and renames the stale copy over it. Restore only when this call made the swap (have `move_into_store` say so). |
| B124 | P3 | bug | fixed | `platform/windows.rs:80-104` (`busy_aside`, `clear_for_replace`) | `tool.exe.old.<pid>-<ms>` files left when a running exe is renamed aside are never swept (only ketch's own asides are), so they pile up with every upgrade of a running tool. Sweep `*.old.*` siblings of recorded links at the start of commit, relink and uninstall. |
| B125 | P3 | vuln | confirmed | `crates/ketch-ffi/src/callbacks.rs:128-162`; `crates/ketch-ffi/src/records.rs:238-241` | Event `detail`/`verb`/`name`/`label` and `Changelog.version` carry client asset names and tags to Swift, C# and Vala unsanitised, though the `records.rs` header says they pass through `changelog::sanitize`; GUIs get bidi overrides raw. Sanitise string fields in `From<report::Event>` and `Changelog::new`. |
| B126 | P3 | bug | suspected | `crates/ketch-capi/vapi/ketch.vapi` (`StopFunc`); `crates/ketch-capi/include/ketch.h:37` | `KetchStopFn` returns C `bool`, the Vala delegate returns `gboolean` (int): an incompatible function-pointer type (error by default on GCC ≥ 14) and a return-ABI mismatch. `tests/capi.vala` never passes a `ChooseFunc` or `StopFunc`. Return `int32_t` and cover both callbacks in the Vala test. |
| B127 | P3 | bug | confirmed | `crates/ketch-capi/src/lib.rs:61-65` (`ErrorBody`) | `ErrorBody` flattens `KetchError`, whose `Network`/`Verification`/`Other` variants already have `message`, and adds its own: the JSON envelope has a duplicate `"message"` key that strict parsers reject. Skip or rename the outer field. |
| B128 | P3 | bug | confirmed | `desktop/macos/Ketch/Core/ContractScenario.swift:132`; `KetchCoreProtocol.swift:99` | `Busy` with an unknown holder becomes `pid 0`, and the user reads "ketch is running in another process (pid 0)". Make `busy(pid:)` optional, as on Windows. |
| B129 | P3 | bug | confirmed | `desktop/macos/Ketch/Store/KetchStore.swift:135-145, 312-321` | The background update loop and window-key refresh report errors through `report`, which sets `errorMessage`; offline with the real core, a modal alert pops on every interval and focus change. Log background failures without alerting. |
| B130 | P3 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`, `PackageDetailView.swift:59-62`; `desktop/windows/Ketch.App/Pages/DiscoverPage.xaml.cs:36-42` | Core calls ignore task cancellation, so an older, slower search or changelog reply can land after a newer one (Windows also searches on every keystroke). Tag requests and drop out-of-date replies; debounce search. |
| B131 | P3 | bug | confirmed | `desktop/windows/Ketch.AppCore/KetchStore.cs:325-328` (`Operate`) | When an operation is already running, `Operate` returns silently: a confirmed Uninstall or a clicked Install does nothing. Set `ErrorMessage` as macOS does. |
| B132 | P3 | bug | fixed | `install.ps1` | `--version 0.10.0` is given a leading `v` before the download URL is built, the same as `install.sh`. A tag that already starts with `v` is left alone. |
| B133 | P3 | vuln | confirmed | `install.sh:7-359` | The body is not wrapped in a function, so under `curl \| bash` a truncated download runs whatever complete lines arrived. Low impact today, but the guard is missing. Put the body in `main() { … }` and call `main "$@"` last. |
| B134 | P3 | bug | suspected | `install.ps1:229, 263` | `[Net.ServicePointManager]::SecurityProtocol` is never set; on older Windows 10/Server, PowerShell 5.1 defaults to TLS 1.0/1.1, which GitHub rejects. OR in `Tls12` at the top. |
| B135 | P3 | bug | confirmed | `.github/workflows/ci.yml:439-445` | `/review` dispatches with `--ref "$branch"` from the PR's `headRefName`; for a fork PR that ref is missing (the job fails) or names a base branch such as `main`, and CI runs on `main` while the bot says it ran on the PR head. Refuse when `headRepository` is not this repo, or check the dispatched ref's SHA equals the head SHA. |
| B136 | P3 | bug | confirmed | `Justfile:147` (`lint-cask`) | `just check` writes `cask/Casks/ketch.rb` into the repo root and `cask/` is not ignored, so `scripts/release.sh --local` then stops at its clean-tree check (`release.sh:94`). Write it under `target/` or ignore `/cask/`. |
| B137 | P3 | bug | confirmed | `tests/commit-msg-breaking.sh`; `Justfile` `lint-commits`; `ci.yml` | `done.md:375` says the test is wired into `just lint-commits`, but neither the Justfile nor CI calls it. Add it to `lint-commits` and CI's shell step. |
| B138 | P3 | vuln | confirmed | `Cargo.lock` (cargo-deny 0.20.2, default config) | RUSTSEC-2026-0285: rustls 0.23.43 via ureq and octocrab, fixed in ≥ 0.23.45 (low impact; `cargo update -p rustls` needs creator permission). RUSTSEC-2023-0071: rsa 0.9.10 (Marvin) via pgp, sigstore and jsonwebtoken, no upstream fix, verification-only use; record an explicit ignore with that reason in a new `deny.toml`. |

Fixed and merged, cards in `done.md`: B84 (#301), B90 (#295), B91 (#317), B92 (#307), B93 (#316), B95 (#308), B97 (#311), B101 (#302), B103 (#298), B104 (#296), B108 (#310), B109 (#309), B111 (#313), B114 (#304), B115 (#305), B124 (#299), B130 (#314).

| # | Status | Priority | Complexity | Readiness | Agent |
| --- | --- | --- | --- | --- | --- |
| B60 | in progress | P3 | 1 | 80% | Cursor / grok 4.7 |
| B65 | in progress | P0 | 2 | 0% | Cursor / grok 4.7 high |
| R3 | in progress | P1 | 3 | 67% | Cursor / grok 4.7 high |
| M17 | in progress | P2 | 4 | 95% | Cursor / grok 4.7 |
| R5 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| R6 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| R8 | in progress | P3 | 3 | 90% | Claude Code / sonnet-5.5 |
| F12 | in progress | P3 | 5 | 60% | Claude Code / opus-5.5 |
| F13 | in progress | P3 | 4 | 80% | Claude Code / opus-5.5 |
| F14 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| F18 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| D11 | in progress | P3 | 5 | 10% | Claude Code / sonnet-5.5 |
| D12 | todo | P3 | 4 | 0% | |
| D13 | todo | P3 | 3 | 0% | |
| D14 | todo | P3 | 4 | 0% | |
| D16 | todo | P3 | 5 | 0% | |
| D17 | todo | P3 | 4 | 0% | |
| D18 | todo | P3 | 3 | 0% | |
| D19 | todo | P3 | 4 | 0% | |
| B74 | done | P3 | 3 | 100% | Cursor / grok 4.7 |
| R12 | done | P3 | 1 | 100% | Cursor / grok 4.7 |
| R20 | in progress | P2 | 3 | 90% | Claude / sonnet-5.5 |
| B98 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| B99 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| B102 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| R18 | done | P2 | 3 | 100% | Cursor / grok 4.7 |
| R19.1 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| R19.2 | in progress | P2 | 2 | 90% | Claude Code / opus-5.5 |
| R19.3 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| R19.4 | in progress | P2 | 3 | 90% | Claude Code / opus-5.5 |
| B113 | in progress | P3 | 3 | 90% | Claude Code / opus-5.5 |
| B118 | in progress | P3 | 3 | 90% | Claude Code / opus-5.5 |
| B123 | in progress | P3 | 3 | 90% | Claude Code / opus-5.5 |

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

### B60. Windows self-update leaves `ketch.exe.old` behind

Observed on Windows: `ketch self update` 0.4.4 → 0.5.1 succeeded, but the old binary stayed in the bin dir as `ketch.exe.old`. Windows will not delete the file backing a running image, and at the success cleanup in `replace_binary` (`let _ = std::fs::remove_file(&backup)` after the smoke test) the process doing the deleting is that renamed old image — so the removal fails with access denied and the error is dropped. `install_self`'s aside cleanup has the same silent drop, and a stale `.old` left behind also becomes a destination that can fail the next swap's rename.

Plan: sweep the aside at the start of the self commands instead of at the end of the swap. `self update` and `self install` delete `<bin>/ketch.exe.old` before touching anything: by then any earlier updater process has exited, so the deletion works. Failure stays non-fatal. `doctor` gains a stale-`.old` note for the case where even that fails.

Execution: `aside_candidates` names both leftovers (`ketch.exe.old` from `replace_binary`, `ketch.old` from `install_self` on Windows). `sweep_stale_asides` runs at the start of `update` (not on `--dry-run`) and `install_self`, and `replace_binary` sweeps its own destination again before the rename. A failure warns and continues. The end-of-swap delete stays a single best-effort try: on Windows this process is that image, so the delete cannot succeed until exit. `stale_aside_check` warns from `doctor` when a leftover is still in the bin dir. Tests cover the two names, a sweep that deletes and one that ignores a missing file, and the doctor note.

## Plan 2026-09-27 (drafting with Ivan)

### Goals

### Tasks

### F8. Spinner and progress bar

Show a spinner while a command is running so the user sees that it started. Use a progress bar where measurable progress is available, and a spinner elsewhere. Match the behavior in rtok.

Status: done. `ui::activity` draws a bar when the total is known and a spinner otherwise (`{spinner:.cyan} {msg}`, ticking every 120ms). Resolve, extract, registry fetch and self-update hold one. `ketch list` counts `N/M packages` with `counter`. Tests: `a_known_total_is_a_bar`, `an_unknown_total_is_a_spinner`.

### R3. Cross-platform CI

Run verification on macOS, Windows and Linux. A ketch config is either a local file in the project or pushed to a registry; keep that model. The cross-platform check must catch OS-specific binary selection bugs like the one in B64.

Current state: `ci.yml` has separate jobs on macOS, Linux and Windows running lint, the full nextest suite and the `tui`-feature tests on all three. Formatting and commit-message checks run on macOS only, PowerShell syntax on Windows only. The packaging matrix runs on all three OSes. `Swatinem/rust-cache` is already in every job. `verify.yml` mirrors the same three-OS checks before a release.

To add:

1. Binary selection regression test: see B65 below.
2. Both config paths, local file and registry: cover the select-mode prompt when the binary name is missing and several candidates match, and assert the chosen binary is written back into the config.
3. Caching: rust-cache is already in place. Also evaluate caching the mise toolchain and the target directories on all three OSes. Do this in any case, and base the decision on the before/after build-time numbers from the cox and ketch infra-template PRs.

Findings: no workflow edit. `ci.yml` jobs `check` (`macos-latest`), `check-linux` (`ubuntu-latest`), `check-windows` (`windows-latest`) and `package` (all three) already run lint and the full nextest suite, so a binary-selection test is picked up with no extra job. `verify.yml` job `verify` uses the same three runners. `Swatinem/rust-cache@v2` is already in each of those jobs.

Do not add a second cache. `jdx/mise-action` already caches the toolchain, and `rust-cache` already caches `target/`. Numbers from [ketch#151](https://github.com/pyrlyn/ketch/pull/151) run [36318217579](https://github.com/pyrlyn/ketch/actions/runs/36318217579), the current-workflow run [36318223420](https://github.com/pyrlyn/ketch/actions/runs/36318223420), the following warm main run [36320755597](https://github.com/pyrlyn/ketch/actions/runs/36320755597), and [cox#57](https://github.com/listepo/cox/pull/57) run [36318140763](https://github.com/listepo/cox/actions/runs/36318140763):

- Mise cache hits on the ketch infra-template jobs: 35 MB macOS, 46 MB Linux, 62 MB Windows. Cox shows the same macOS and Linux hits and has no Windows job.
- macOS `check`: 8m9s on a target-cache miss, then 1m47s after restoring 778 MB.
- Linux `check-linux`: 894 MB restored in 12s, job 1m12s.
- Windows `check-windows`: 815 MB restored, and the restore itself was 2m52s of a 5m56s job. Another target cache would pay that download again.

Item 2 stays with B64. Item 1 is B65, which closes with B64's `tests/bin_choice.rs`.

### B65. Binary selection regression test

Add a fixture with two similarly named binaries (for example `rtok` and `rtok-hook`) and assert the intended one is chosen on every OS: macOS, Windows and Linux. This is the test that would have caught the Windows alphabetical-sort bug, where `rtok hook` was selected instead of the intended binary.

Note for the owner: B64's branch `b64-bin-name` already adds `tests/bin_choice.rs` with `rtok`, `rtok-hook` and `other-tool` fixtures, not gated by OS. Reuse or extend it once B64 merges instead of writing a second fixture.

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

Status: PR https://github.com/pyrlyn/ketch/pull/187 merged 2026-10-01. The version moved to `[workspace.package]`; `release.sh` and `crate-version.sh` read it there, and v0.9.0 was released after the merge. Remaining: the doc examples that became doctests are marked `ignore` and should be rewritten (18 `ignore` fences under `crates/` at the last check).

### R6. A reporter instead of the global `ui::` sink

The pipeline prints through `ui::` directly (`install.rs`, `self_update.rs`, `registry.rs`, `listing.rs`), and `ui.rs` keeps global state for the TUI. Replace that with a `Reporter` passed into the core (or typed events on a channel, generalising `tui::Event`): progress, status, warning, log. The CLI implements it with today's `ui.rs`, the TUI with its events. `ui.rs` stays the only place that prints, and `log::record` is still reached through it.

Done when the core has no `ui::` calls, CLI output is byte-for-byte the same (the existing `trycmd`/`insta` snapshots pass unchanged), and a test reporter can assert the events of an install.

Plan:
1. Inventory (as of f60d85e): `ui::` calls outside `cmd/`, `ui.rs` and `main.rs` — `install.rs` 35, `self_update.rs` 27, `registry.rs` 8, `listing.rs` 7, `http.rs` 5, `hooks.rs` 4, `process.rs` 4, `trust.rs` 4, `log.rs` 3, `source/mod.rs` 3, and 1–2 each in `changelog`, `config`, `manifest`, `resolve`, `state`, `stats`, `extract`, `platform/unix`, `platform/windows`, `source/{github,local,plugin}`. Globals in `ui.rs`: `COLOR`, `LEVEL`, `BARS` (indicatif `MultiProgress`), `TUI`, `TUI_INPUT_PAUSE`; `log.rs` has `SINK`.
2. Define in the core `pub enum Event` (typed, not rendered strings): `Step { package, stage }` (resolve, download, verify, extract, link, hooks), `Progress { id, done, total }`, `Status`, `Warn`, `Note` (verbose), each carrying structured fields; and `pub trait Reporter: Send + Sync { fn event(&self, e: Event); }`. Core entry points take `&dyn Reporter` (via a small `Ctx { cfg, reporter, … }` so signatures do not grow per task).
3. Convert module by module, smallest first, `install.rs` and `self_update.rs` last; each step is its own commit with snapshots unchanged.
4. The binary's `ui.rs` implements `Reporter` by rendering exactly today's lines and bars; the TUI controller implements it by mapping `Event` to its own events (drop the string-stripping path in `ui::line`). `log::record` stays called from `ui.rs` only; a non-CLI host gets a `LogReporter` adapter in the core that records events to the log file, so the GUI's operations are logged too.
5. Colour and verbosity become renderer settings, not core globals; `log::SINK` is initialised by the host.
6. AGENTS.md "Conventions": "All output goes through `ui::`" becomes "The core reports through `Reporter`; only `ui.rs` prints".

Check: `grep 'ui::' crates/ketch-core/src` is empty; snapshots unchanged; a new unit test installs a fixture package with a recording reporter and asserts the event sequence; `--tui` still works (manual run against a `KETCH_ROOT` scratch tree).

Status: PR https://github.com/pyrlyn/ketch/pull/189 merged 2026-10-01. `ui.rs` and `tui/` left the core; the core reports through `Ctx { cfg, report }`, with `LogReporter` and a test `Recorder`. Remaining: colour and verbosity are still global inside `ui.rs` (plan step 5); the TUI still receives events through `ui::Terminal`; the `--tui` check ran on a zero-size pty, so it is weak and needs a manual run on a real terminal.

### R8. Core calls from a long-running host

A GUI keeps running between operations and must not freeze. Core operations must be callable from a worker thread, and the process lock must be tryable: `state::Lock` gets a non-blocking acquire, so a frontend can report "another ketch is running" instead of waiting. Check that nothing in the core relies on process-global state that a second operation in the same process would see stale (config, the `ui` init, cached listings).

Done when two operations in one process run one after the other in a test, and a held lock gives a typed "busy" error.

Finding from the survey: `Lock::acquire_path` already fails fast with the holder's pid, but it treats a lock file holding *its own pid* as re-entrant (`owned: false`). In a GUI process two concurrent operations would both pass. That is the main fix here.

Plan:
1. Lock: keep the lock file for cross-process exclusion, and add an in-process guard (a `static` `Mutex<bool>`/`AtomicBool` "held by this process") so a second acquire in the same process fails with the same busy error instead of adopting the lock. Keep the existing re-entrancy only where the CLI relies on it (find the callers first; if none, remove it and say why in the commit).
2. Typed error: `Error::Busy { pid: Option<u32> }` (today it is a message), so a frontend can show "ketch is busy (pid N)" and retry, and the CLI prints the same text as today.
3. Cancellation: a `Cancel` token (`Arc<AtomicBool>`) in the context, checked between packages and between download chunks; a cancelled operation cleans up its temp dir and returns `Error::Cancelled`. The CLI wires it to Ctrl-C where it already handles SIGINT (the TUI's exit 130 path).
4. Process globals: `Config::load` reads env and files on every call — the host rebuilds `Config` per operation, so config edits in `config.toml` are seen; `log::SINK` initialised once per process; the `listing.rs` cache is file-based, fine. `tokio` runtime for `push.rs` is created per call, confirm it is not nested inside a host runtime.
5. `Send`: core entry points are callable from any thread (no `Rc`, no thread-locals in the pipeline).

Check: unit tests — two locks in one process → second is `Busy`; lock released on drop and on error; a cancelled fixture install leaves no partial store folder and no state entry; an end-to-end test runs the CLI while a lock is held and asserts the busy message.

Status: PR https://github.com/pyrlyn/ketch/pull/184 merged 2026-10-01. Pid re-entrancy had no callers and was removed. Remaining: SIGINT wiring for the CLI (no signal handler exists; tokens are never cancelled there), and `self upgrade` / registry downloads still pass a never-cancelled token.

### F12. Native macOS app (SwiftUI) on `ketch-ffi`

A SwiftUI app in the repository (e.g. `desktop/macos/`), consuming R9's XCFramework and Swift package: installed and registry packages (list, search), install, upgrade, uninstall, changelog, doctor, with progress from the reporter callback and dialogs for the decider. It manages the same ketch root as the CLI and respects the same lock (R8).

Decided (creator, 2026-09-30): the app shares the ketch root (`~/.ketch`, or `KETCH_ROOT`) with the CLI — one state, one lock, one store. Also decided: a menu-bar extra is in scope (status and pending upgrades at a glance, quick actions), and the app follows ketch's triple licence (GPL-3.0-only, royalty-free, commercial). Minimum macOS version: 26 (the deployment target of the app and of R9's XCFramework).

Design (creator, 2026-10-01): the UI is frosted (matte) or glossy glass with a 3D effect. On macOS 26 that means the native Liquid Glass material (SwiftUI `.glassEffect`, `GlassEffectContainer`), with depth from layering, shadows and specular highlights — system materials, not a hand-drawn imitation, so it follows accessibility settings (Reduce Transparency, Increase Contrast).

Plan:
1. Project: `desktop/macos/` with the app target described in a text spec (XcodeGen or Tuist — pick at start with a sourced comparison, add to `toolchain.md`) so the project is reviewable in diffs; bundle id under the creator's team, deployment target macOS 26, Swift 6 strict concurrency, depends on the local `KetchCore` package from R9. `AGENTS.md` layout table gets the new paths.
2. Architecture: one `@Observable` `KetchStore` on the main actor owning the state; every core call runs in `Task.detached` and reports back through the `Reporter` callback hopped to the main actor. A `KetchCoreProtocol` wraps the FFI object so view models are testable with a fake.
3. Main window (`NavigationSplitView`): Installed (name, version, source, update badge; upgrade, uninstall, reveal in Finder), Discover (registry search, install), package detail (description, versions, changelog rendered from sanitized Markdown via `AttributedString(markdown:)`, release link), Activity (current operation with per-package progress and a log, Cancel button), Doctor (findings with fix actions the core offers).
4. Decisions: the binary choice becomes a sheet listing candidates; confirmations for uninstall/upgrade-all are app dialogs (the CLI's `cmd/` confirmations, re-done in the frontend per R7).
5. Menu bar: `MenuBarExtra` with the number of pending upgrades, "Upgrade all", the running operation's progress, "Open ketch", "Quit"; an update check on launch and on a timer while running (interval in Settings). Optional "Open at login" via `SMAppService.mainApp`.
6. Busy and shared root: a `Busy` error shows "ketch is running in another process (pid N)" with Retry; the app re-reads state when the window becomes key, so installs made from the CLI show up. The root shown in Settings (`KETCH_ROOT` honoured when set in the app's environment).
7. Settings: update-check interval, include prereleases, GitHub token presence (read-only note pointing to the CLI's config — the app does not store tokens), open `config.toml`.
8. About screen names the licence (GPL-3.0-only / royalty-free / commercial) and links the repository.
9. Tests: Swift Testing for `KetchStore` with the fake core (install flow, busy, cancel, decider sheet); one UI smoke test that launches the app against a scratch root.

Check: the app builds and runs on macOS 26 on both architectures; manual pass against a scratch `KETCH_ROOT`: install a fixture package, see it in the CLI's `ketch list`, uninstall from the CLI and see it disappear in the app; a held CLI lock shows the busy state; `swift test` and the UI smoke test pass in CI.

Status: part 1 (app shell on a fake core) merged as PR https://github.com/pyrlyn/ketch/pull/185 (2026-10-01). XcodeGen 2.46.0 chosen over Tuist and plain SwiftPM (sources in `docs/research-desktop.md`). `Theme.swift` now reads F14's generated tokens and the screens were rebuilt on the Liquid glass design (F16 and F17, `done.md`). R9 (PR https://github.com/pyrlyn/ketch/pull/208) is merged, so the core is available. Remaining: a `LiveKetchCore` adapter over the generated `KetchCore` (`Ketch/Core/CoreFactory.swift` still returns `FakeKetchCore`; the adapter maps R9's structured `Changelog`), and the manual checks against the real core.

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

Status: PR https://github.com/pyrlyn/ketch/pull/190 merged 2026-10-01. Sparkle 2.10.0 with EdDSA; the feed is `releases/download/desktop-appcast/appcast.xml`, independent of `/releases/latest`. Two real bugs fixed on the way: `cliff.toml`'s unanchored `tag_pattern` and `select_release` ranking `desktop-v*` above CLI tags under `--pre`. Remaining: waiting on the creator for the secrets `APPSTORE_CONNECT_KEY`, `APPSTORE_CONNECT_KEY_ID`, `APPSTORE_CONNECT_ISSUER_ID`, `SPARKLE_ED_PRIVATE_KEY` and the matching `SUPublicEDKey` committed; the XCFramework step in `desktop-release.yml` is still a skipped hook (`XCFRAMEWORK: 'false'`) although R9 has merged; no real signed release has run.

### F14. Design system for the macOS app: `DESIGN.md` and tokens

The creator asked for a new, polished design with tokens (2026-10-01), following the F12 glass requirement: frosted or glossy glass with a 3D effect, macOS 26 Liquid Glass.

Plan:
1. Research the `DESIGN.md` format (a design spec agents read: tokens in front matter plus prose) with primary sources; follow it if it is a maintained spec, else a documented equivalent.
2. `desktop/macos/DESIGN.md`: principles, glass and depth rules, colour (light and dark, accent, semantic status colours for installed / update / error / busy), typography (SF Pro scale), spacing, radii, elevation levels (shadow and highlight per layer for the 3D effect), motion, icons (SF Symbols), components (sidebar, package row, card, progress, sheet, menu-bar extra, buttons), accessibility fallbacks (Reduce Transparency, Increase Contrast, Reduce Motion).
3. Tokens as one source: `desktop/macos/design/tokens.json` in the W3C Design Tokens format, generated into Swift (`Tokens.swift`, header says it is generated) by a maintained generator (e.g. Style Dictionary), run by a `just` recipe, with a drift check.
4. `desktop/macos/design/preview.html`: a static page rendering the tokens and key components in light and dark, for review.
5. Hand-off to F12: the app uses the generated tokens instead of literals.

Check: the generator reproduces `Tokens.swift` byte-for-byte; the preview renders both themes; text colours meet WCAG AA contrast on their glass backgrounds (checked by the script or documented per pair).

Status: PR https://github.com/pyrlyn/ketch/pull/188 merged 2026-10-01. Google Labs DESIGN.md spec (alpha, `@google/design.md` 0.4.0) with generated front matter; tokens in W3C DTCG 2025.10; Style Dictionary 5.5.5 with custom formats for Swift (four appearances), CSS and the front matter; `just design-check` covers drift, WCAG AA and lint. Research in `docs/research-design-system.md`. `Theme.swift` reads `Tokens.swift` since F16. Remaining: an Accessibility Inspector pass on the real Liquid Glass material.

### F18. Figma design for macOS, Windows and Linux

The creator asked (2026-10-01) to update the Figma file "ketch for macOS — Liquid glass"
(`v7OJLmQEyCFbJ63uSYpJ9g`) for the new requirements: the app is coming to Windows and Linux too
(R10, R11), the macOS app as built in F17 differs from the first mock-ups, and the core now
exposes decisions the screens do not show yet (binary choice, stopping running processes, the
ambiguous `bin` glob, the busy lock, pin and rollback).

Done when the file has a shared layer (common screen specs, shared semantic tokens, truly
cross-platform components) and per-platform pages; the macOS screens match the F17 app; the new
feature screens exist for macOS in light and dark; Windows 11 (WinUI 3 / Fluent) and Linux
(GTK 4 + libadwaita, or what R11 recommends) variants exist for Installed, Discover, Updates,
Package detail, Settings, the binary-choice dialog and the tray or its equivalent, in light and
dark; prototype links cover the new frames; and the Figma mapping doc names the pages,
collections, modes and the code syntax of every variable.

Execution plan:
1. Read the F17 SwiftUI views, `tokens.json`, R10 and R11 (if pushed), the `ketch-ffi` surface
   and the F17 screenshots.
2. Figma, shared layer: a `Platform` variable collection (modes macOS / Windows / Linux) for
   material, radius, spacing and type-size differences beside `Color` (Light / Dark), after
   checking the plan's mode limit; a Shared page with wireframe-level screen specs and the
   cross-platform components; per-platform pages.
3. Sync the macOS screens with F17 (bottom progress bar, Discover hero and shelf, Doctor fix
   text, monogram icons, native toolbar controls, busy banner, Settings tabs, orange update
   badges, smoke-tinted terminal).
4. New macOS screens, light and dark: binary choice, stop running processes, ambiguous `bin`
   glob, busy lock, activity detail, pin / unpin and rollback, update notifications; anything
   the core does not expose yet is marked "needs core" in the frame description.
5. Windows and Linux variants in each platform's idiom, light and dark, on the shared tokens.
6. Prototype links; screenshot-verify each step.
7. Update the Figma mapping doc (`desktop/macos/design/figma.md`, or a platform-neutral place
   if the structure warrants it), open a PR, wait for CI.

Status: the Figma work is done — shared pages, macOS synced with F17 plus ten new frames,
Windows (Fluent) and Linux (libadwaita, per R11) pages in light and dark, prototype flows on
every page. `figma.md` lives in `desktop/design/`. Left: the creator's
review of the file and the merge of the PR.

### D11. Windows: WinUI 3 app shell on a fake core

The Windows app's screens can be built before the binding is settled, the way F12 started on macOS. Research: sections 3b and 3c.

Done when a WinUI 3 app (Windows App SDK, .NET 10, built with `dotnet build`, no Visual Studio required) has the common screens in a `NavigationView` with a `TitleBar` over Mica, `ContentDialog` and `InfoBar` for decisions and busy, light, dark and contrast themes, all on a fake core reading D4's fixtures, with a Windows CI job that builds and runs its tests.

Execution plan:

1. `desktop/windows/Ketch.AppCore` (net10.0, no UI types, so it builds and tests anywhere): `IKetchCore`, the records, `CancelToken`, `ContractScenario` (System.Text.Json over `desktop/contract/scenarios`, the same mapping as `ContractScenario.swift`), `FakeKetchCore` (a port of the macOS fake: samples, simulated pipeline, scripted replay, lock switch) and `KetchStore` (`INotifyPropertyChanged`; installed, updates, held, search, doctor, activity, log, busy, pending binary choice; install, upgrade, uninstall, cancel, retry).
2. `desktop/windows/Ketch.AppCore.Tests` (MSTest, as `KetchCore.Tests`): every scenario decodes and maps, the fake replays them (events, questions, busy, cancelled, errors), and the store's states.
3. `desktop/windows/Ketch.App` (WinUI 3, `WindowsPackageType=None`, self-contained, built by `dotnet build`): `App.xaml` merges `desktop/design/generated/KetchTokens.xaml`; `MainWindow` with a `TitleBar` over `MicaBackdrop` and a `NavigationView` (Installed, Discover, Updates, Activity, Doctor, Settings pinned at the bottom); a package detail page; `ContentDialog` for uninstall, upgrade-all and binary choice; `InfoBar` for busy and errors; Settings picks light, dark or system, and high contrast follows Windows through the token file's `HighContrast` dictionary.
4. CI: a `ketch-win-app` job on `windows-latest` with SHA-pinned actions: `dotnet test` for `Ketch.AppCore.Tests`, `dotnet build` for `Ketch.App`. If the Windows App SDK cannot build without Visual Studio, stop and report rather than work around it.
5. `toolchain.md` rows (Windows App SDK, `Microsoft.WindowsAppSDK`), `desktop/windows/README`-level notes in the contract README (the Windows fake is now here), SPDX headers on every new source file.
6. Verify: `dotnet test` for `Ketch.AppCore.Tests` locally (net10.0 builds on this Mac), `dotnet build` of the WinUI project only in CI (not buildable on macOS; noted in the PR).

### D12. Windows: the app on the real core

Swap the Windows app's fake core for the binding. Depends on D10, D11, D1 and D2.

Done when the app runs every screen on `ketch-ffi` through D10's binding, work runs off the UI thread with events marshalled to the `DispatcherQueue`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

The app ships both as an MSIX and unpackaged (D14), and an unpackaged app has no `ApplicationData`, so settings go in a file under `%LOCALAPPDATA%\ketch` that works in both.

### D13. Windows: tray icon, notifications, start at login, links

The Windows counterparts of the macOS menu-bar extra, notifications, login item and URL scheme. WinUI has no tray control, so the icon is Win32's notification area. Research: sections 1 and 3c.

Done when a notification-area icon opens the tray panel, `AppNotificationManager` posts update notices unpackaged, start at login and the `ketch` protocol are registered through `ActivationRegistrationManager` (with D8's validation rules), one instance runs at a time via `AppInstance`, and each can be switched off in Settings.

### D14. Windows: release pipeline

How the Windows app reaches users and updates itself, separate from the CLI's release. R10's open decisions on distribution and the Windows App SDK licence come first.

Done when CI builds, on `desktop-windows-v*` tags and without touching `/releases/latest`, both an MSIX and an unpackaged self-contained zip, signed (Artifact Signing or the creator's choice), updates arrive through the chosen route (Velopack or winget), and the creator's answers to R10's decisions are recorded. The creator chose to ship both forms. The MSIX is signed with a certificate held in repository secrets named the way the macOS ones are (`MACOS_CERTIFICATE`, `MACOS_CERTIFICATE_PWD`), and the release fails when they are missing rather than shipping unsigned.

Settings persistence differs between the two: an MSIX has `ApplicationData`, an unpackaged app has none (D12 stores settings in a way that works in both).

### D16. Linux: Vala + GTK 4 app shell on a fake core

The Linux app's screens in Vala with GTK 4 and libadwaita, following the GNOME HIG, built before the C ABI is ready. GTK 4 + libadwaita over KDE and Blueprint for the markup were decided by the creator (2026-10-01, open decisions 1 and 2). Research: sections 3b, 3c and 5.

Done when a Meson project builds a libadwaita app with the common screens in an `AdwNavigationSplitView` that adapts to narrow windows, Blueprint files (pinned as a Meson subproject) for the UI, `AdwAlertDialog`, `AdwBanner` and toasts for decisions, busy and finished work, dark and high-contrast styles, all on a fake core reading D4's fixtures, with a Linux CI job that builds and runs its tests.

### D17. Linux: the app on the real core

Swap the Linux app's fake core for `ketch-capi`. Depends on D15 and D16.

Done when every screen runs on the C ABI, calls run off the main loop with events returned through the `GLib.MainContext`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

### D18. Linux: notifications, background and autostart

GNOME has no tray in its HIG; an app that checks in the background asks the Background portal and notifies through `GNotification`. Research: sections 1 and 3c.

Done when update notices go through `GNotification` (the Notification portal under Flatpak), background running and start at login are requested through the Background portal (libportal) with an XDG autostart entry outside a sandbox, `ketch://` links follow D8's rules, and there is no tray: the creator deferred a StatusNotifierItem tray (2026-10-01, open decision 8).

### D19. Linux: packaging and release

How the Linux app reaches users. R10 left Flatpak against distribution packages open, and Flatpak needs home access for PATH work.

Done when the creator has chosen the format, CI builds it on `desktop-linux-v*` tags without touching `/releases/latest`, the app ships AppStream metadata and a `.desktop` file that validate, and updates come from the chosen package manager.

### M17. `ketch import`: a package from winget, Homebrew or a Linux repository

Requested by the creator (2026-10-02). `ketch import winget|brew|linux <name>` looks a package up by name in another package manager, converts its definition to a ketch manifest, writes it as a user manifest (`~/.ketch/manifests/<name>.toml`) and installs it the normal way. Re-running it is idempotent: an unchanged conversion with the release already installed prints that everything is up to date and touches nothing; a changed conversion or a newer upstream version rewrites the manifest and installs. The manifest holds only what ketch needs (`name`, `source`, `kind` for an app, `bin`, `[asset.target]` pins); nothing else from the source is copied.

Hard rule: only packages whose artifacts are GitHub Release assets convert. Anything else writes nothing and exits non-zero with `<name> can't be converted: it is not distributed through GitHub Releases, and that is not supported yet.`

Done when the three converters, the command, docs (`docs/COMMANDS.md`, `docs/MANIFESTS.md`) and offline tests (recorded fixtures, a temp root, never the real config) are in, and `just check` is green.

Research (sources checked 2026-10-02):

- Homebrew: `formulae.brew.sh/api/formula/<name>.json` and `/api/cask/<token>.json` (https://formulae.brew.sh/docs/api/). A cask's `url` is the macOS arm64 download; `variations` keyed by macOS release (`sequoia`, …) are the Intel ones, `arm64_<release>` the arm64 ones; `sha256` may be `no_check`; `artifacts` lists `app`, `binary`, `pkg`, `installer` and others (response of `/api/cask.json`, 7778 casks). A formula's `urls.stable.url` is the source it builds from; its binaries are bottles on `ghcr.io` (`bottle.stable.files`), never GitHub Releases. Of 817 core formulae whose stable URL is under `releases/download/`, none is a prebuilt archive naming an OS and an architecture (`/api/formula.json`) — so a formula converts only in that case and is otherwise "source only".
- winget: the community source is the `microsoft/winget-pkgs` repository, `manifests/<first letter>/<Id with dots as folders>/<version>/<Id>.installer.yaml` (https://github.com/microsoft/winget-pkgs/tree/master/doc/manifest/schema/1.12.0). The community source has no public REST endpoint (the REST protocol is for private sources and the msstore source), so versions are listed with the GitHub contents API and the manifest read raw. `InstallerType`, `NestedInstallerType`, `NestedInstallerFiles` and `Commands` may sit at the root or per installer; one architecture may have several installers (per `Scope` or `InstallerLocale`), often with the same URL (`Git.Git` 2.55.0.5).
- YAML: `serde_yaml` is deprecated (crates.io, `0.9.34+deprecated`), `serde_yaml_ng` and `serde_norway` last released 2024; `serde-saphyr` 1.3.0 (2026-09-16, MIT/Apache-2.0, pure Rust) is maintained, so it reads the winget manifests. Its `rust-version` is 1.89, above ketch's declared MSRV 1.86, which nothing in CI checks — flagged for the creator.
- Linux: there is no cross-distribution source that names an artifact URL. Repology (https://repology.org/api) maps names across distributions but carries no download URLs (**unverified**: the API page could not be reached on 2026-10-02). Debian, Fedora and the official Arch repositories build from source, so their recipes point at source archives. Flathub is keyed by reverse-DNS app IDs, not package names. The AUR is name-based with a JSON API (https://wiki.archlinux.org/title/Aurweb_RPC_interface, `rpc/v5/info`, `rpc/v5/search?by=provides`), and its `-bin` packages repackage upstream's prebuilt artifacts, with per-architecture `source_<arch>` URLs and `sha256sums_<arch>` in a machine-readable `.SRCINFO` (https://wiki.archlinux.org/title/.SRCINFO). So `linux` means Arch Linux: the official repositories (https://wiki.archlinux.org/title/Official_repositories_web_interface, `.SRCINFO` from `gitlab.archlinux.org/archlinux/packaging/packages/<pkgbase>`) and the AUR, the official one first.

Execution plan:
1. This card.
2. `serde-saphyr` for the winget YAML (one commit, `toolchain.md` and `rust.md` rows).
3. Core `crates/ketch-core/src/import/`: the shared rules (a GitHub release URL, the tag, per-target asset globs, the rejection error) and one converter per source on plain data, with recorded fixtures under `crates/ketch-core/src/import/fixtures/`; the fetching behind a small trait so tests never reach the network; base URLs overridable by `KETCH_IMPORT_BREW`, `KETCH_IMPORT_WINGET_API`, `KETCH_IMPORT_WINGET_RAW`, `KETCH_IMPORT_ARCH`, `KETCH_IMPORT_ARCH_GITLAB`, `KETCH_IMPORT_AUR`.
4. Writing the manifest through `manifest.rs` (the module that owns user-manifest files), rendering through `wizard::render`, and the idempotency decision in the core.
5. `ketch import` in `cli.rs` and `cmd/import.rs`; end-to-end tests in `tests/import.rs` against a local mock of the sources and of the GitHub API.
6. Docs: `docs/COMMANDS.md`, `docs/MANIFESTS.md`, help snapshots, man pages.

Rules decided here:
- GitHub Releases means every artifact URL is `https://github.com/<owner>/<repo>/releases/download/<tag>/<file>` (or `releases/latest/download/<file>`), all of one repository and one tag. A GitHub homepage or an `archive/` source tarball does not count.
- Mixed installers: if any artifact ketch would use, on any architecture, is hosted elsewhere, nothing converts — one manifest has one source, and a partial conversion would install on one machine and silently fall back to guessing on another.
- An artifact that is a source archive (an archive naming neither an OS nor an architecture, for a Homebrew formula) is not a release artifact.
- Installer formats ketch cannot place (winget `msi`, `msix`, `exe`, `inno`, `nullsoft`, `wix`, `burn`; cask `pkg`, `installer` and the other non-`app`, non-`binary` artifacts; Linux `.deb`/`.rpm`) are refused with their own message, after the GitHub check.
- A file at the manifest path that does not start with the ``# Written by `ketch import`` header is the user's and is never replaced; the command refuses instead.
- The host must be one of the converted targets, or nothing is written: a manifest this machine cannot install from would leave a package `ketch install` fails on.
- Idempotency: the rendered file is compared byte for byte with the one on disk, the installed version with the source's tag. Same file and installed ≥ source → "Everything is up to date", nothing touched; a newer tag → rewrite and upgrade; same version, changed file → reinstall (`force`); installed newer than the source → the installed release stays and only the manifest is redone. The install pins the source's asset (`asset_override`) and checksum (`expected_sha256`); a missing checksum warns and installs, checked the usual way.
- Bin paths and asset patterns have the version replaced by `*`, so the manifest keeps matching after the next release.

Status: steps 1–6 landed in #215. `docs/ru` and `docs/uk` translate the user-facing docs, including `ketch import`. Still open for the creator: `serde-saphyr`'s MSRV is 1.89, above the declared 1.86. A cask `binary` whose source starts with `$APPDIR` stays inside the app bundle; `kind = "app"` places the bundle and links nothing.

### B74. xz archives decompress entirely into memory

`lzma-rs` decoded each XZ block into one `Vec` before any plaintext was written (`decode::xz::read_block`), so a channel adapter around `xz_decompress` still held a hostile multi-GB block in memory. Done: `TarXzExtractor` and `XzFileExtractor` read through `lzma-rust2`'s `XzReader`, which yields plaintext as it is decoded, and refuse a block whose LZMA2 dictionary would take more than 256 MiB of decoder memory. `docs/PLUGINS.md` states that protocol v1 has no checksum subcommand, so a plugin asset without a sha256 `digest` is trust on first use.

### R12. Consolidate duplicated helpers

Done. `remove_any` already lives in `fs_kit::tree::remove_any` (R20); the local copies are gone. `Http::has_token` is used by the GitHub source before a draft listing, so it stays. `sibling` is one function in `platform/mod.rs`: the suffix is appended as bytes (a non-UTF-8 name is kept) and a path with no file name becomes `pkg`. The three backends call that. The GZIP/XZ/BZ2/ZIP magics and `looks_like_tar` are `pub(crate)` in `extract/archive.rs`; the local source classifies from them and still does not peek inside a compressed stream.

### R20. File and directory helpers from `fs-kit`

The creator ordered one entry point for file work. `fs-kit` (path identity, atomic writes, tree copy and removal, backups) replaces ketch's own copies: `dunce` and `file-backup` are dropped, the two `remove_any` copies on Unix and in `self_update.rs` are gone, `src/lib.rs` `lexical_join` is `fs_kit::path::normalize`, and the temp-file-plus-rename writers in `state.rs`, `lockfile.rs`, `listing.rs`, `manifest.rs` and `shell.rs` go through `fs_kit::write`. Done means: no `dunce`, no `file-backup`, and every behavior listed below as kept local has a reason on this card.

Plan: (1) add `fs-kit` once under `[workspace.dependencies]`, as a path dependency until it is on crates.io, then `fs-kit = "0.1"` from crates.io with the `just setup` `paths` override for local work (0.1.0 is published); (2) swap canonicalize and simplify calls; (3) swap `remove_any`, `lexical_join` and the atomic writers, with `fsio::write_private` keeping the `0600` and replace-the-link behavior the tempfile writers had; (4) run fmt, clippy and the whole test suite.

Kept local on purpose: `source/local.rs` `copy_tree` (its Windows branch materialises links through `materialize_local_symlink`, with its own missing-target error, where `fs-kit` would create links when Developer Mode allows); `http.rs` download (a streamed write with a cancel check); the store-swap and registry-swap renames; `extract/macos.rs` `copy_tree` (shells out to `ditto`). `platform/windows.rs` `remove_any` and `extract/archive.rs` `copy_dir_contents` moved once `fs-kit` kept the OS error code (`fs_kit::raw_os_error`) and gained `Links::Skip`; the Windows half is checked by Windows CI only. Overlaps R12 (`remove_any` duplicate) and R18 (the rename-over-temp copies).

### B98. `install.ps1` closes the user's PowerShell window on error

Row B98 in the 2026-10-12 findings. Done: the body runs in a function, error paths `throw`/`return`, and `$ErrorActionPreference` is restored.

Plan: wrap `install.ps1`; keep `--help` and arguments working. Check: a Pester-free script test (or CI step) that sources the script with a failing argument and the session survives.

### B99. Extraction has no byte or entry budget

Row B99 in the 2026-10-12 findings. Done: one shared budget (bytes, entries, ratio plus ceiling) bounds every extractor.

Plan: a budget type in `extract/`, threaded through zip, tar and lone-file extractors. Check: tests with a small gzip bomb and a many-entry zip that fail with a clear error.

### B102. Windows symlink materialisation can recurse forever

Row B102 in the 2026-10-12 findings. Done: a directory link whose target is an ancestor of the link is refused.

Plan: check ancestry in `materialize_symlink_as_copy` and snapshot the entry list before copying. Check: unit tests for `d -> .` and `sub/d -> ..`.

### R18. One atomic replace helper in `ketch-core`

Done. File writes go through `fs_kit::write` (`replace_file` in `manifest.rs`, and the same helper the other file writers already used). The store swap is one `swap_into_store` in `platform/mod.rs`: stage beside the destination, retire the live tree to `.old`, and swap. Each backend passes its own copy (`ditto` on macOS, `copy_tree` on Linux and Windows) for the cross-filesystem case. `finalize_store_swap` and `restore_retired_store` live next to it. Behaviour is unchanged: `.old` stays until links succeed, and a path that is already the store is not retired.

### B113. Windows-reserved names pass the path guards

Row B113 in the 2026-10-12 findings. Done: DOS device names, trailing dot or space and `<>:"|?*` are rejected per component on Windows.

Plan: one check shared by `safe_member_path`, `sanitize_component` and `usable_file_name`. Check: table tests over the reserved names.

### B118. An upgrade silently drops checksum verification

Row B118 in the 2026-10-12 findings. Done: a package installed checksum-verified refuses an unverified upgrade unless `--yes` or an explicit flag.

Plan: consult `checksum_verified` in `install.rs` `verify_checksum`; the flag in `cli.rs`. Check: an end-to-end test of both paths.

### B123. Relink and rollback restore a stale `.old`

Row B123 in the 2026-10-12 findings. Done: the store is restored only when this call made the swap.

Plan: have `move_into_store` report the swap; `restore_retired_store` uses it. Check: a test with a stale `.old` and a failing link that keeps the live prefix.

### R19. Heavy subsystems out of the always-built core

Row R19 in the 2026-10-08 findings (issue #222). Done: `stats` (diesel), `push` (octocrab, tokio), `trust` (sigstore, pgp) and the `extract` codecs are optional in `ketch-core`, so `ketch-ffi` and the desktop apps link only what they use, and the CLI keeps every feature on by default.

Split into R19.1–R19.4 below (#319–#322, stacked in that order). Plan: one sub-task per subsystem, each a cargo feature in `crates/ketch-core/Cargo.toml` with the CLI enabling it, the FFI choosing its set, and CI building the core with `--no-default-features` plus each feature alone. Check: `cargo hack`-style builds of each feature alone (or a scripted loop if `cargo-hack` is not pinned) and the full suite.

### R19.1. `stats` as an optional core feature, checked alone

diesel, diesel_migrations and libsqlite3-sys behind `stats`; `stats::record` does nothing without it. Adds `scripts/core-features.sh`, `just core-features` and a CI step that build the core with no features and with each alone (#319).

### R19.2. Registry push as an optional core feature

octocrab and push's use of tokio behind `push`; the desktop apps no longer link it (#320, on R19.1).

### R19.3. Publisher signatures as an optional core feature

sigstore, minisign-verify and pgp behind `trust`; without it a `require` policy refuses the install, `warn` installs on the checksum alone, unknown keys are still refused (#321, on R19.2).

### R19.4. Archive codecs as optional core features

`gzip`, `xz`, `bzip2`, `zip`; plain tar always. A build without a codec reports an unsupported archive instead of linking it as a program; one `portable_extractors()` list replaces the three platform copies (#322, on R19.3).
