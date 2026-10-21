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
| B81 | P2 | bug | fixed | `extract/archive.rs` `payload_mode` | Archive modes are masked to `0o777`, so setuid, setgid and sticky bits do not survive extraction. |
| B80 | P2 | bug | fixed | `extract/archive.rs:397-399`; extractor order in `platform/linux.rs:62-66` | `TarXzExtractor` claims any `XZ_MAGIC` and is listed before `GzFileExtractor`; unlike gzip (`:508-509`), xz/bz2 never peek for ustar, so a lone `.xz` file fails as a tarball. Peek the decompressed head; add lone-file xz/bz2 extractors. |
| B81 | P2 | bug | confirmed | `extract/archive.rs:280`, `:488` | Extraction keeps `mode & 0o7777`, so setuid/setgid/sticky bits from an archive survive. Mask `0o7000`. |
| B82 | P2 | bug | confirmed | `source/github.rs:310-312` | Aggregate checksum files are keyed by basename; a later duplicate overwrites an earlier one. Reject or flag a basename that maps to two hashes. |
| B83 | P2 | bug | fixed | `source/github.rs` `sidecar_hex` | A sidecar checksum uses the line that names the asset, and a single unnamed entry only when the file is unambiguous. |
| B82 | P2 | bug | fixed | `source/github.rs` `parse_checksum_file` | A basename that maps to two different hashes is dropped, not overwritten by the later line. |
| B83 | P2 | bug | confirmed | `source/github.rs:427-431` | A sidecar checksum takes the first sorted entry (`BTreeMap::into_values().next()`), not the asset's. Look up the asset name first; fall back to a single entry only when unambiguous. |
| B84 | P2 | bug | fixed | `http.rs:118-186` | Downloads stream with no maximum size. Add a configurable download/unpacked cap and abort over it. |
| B85 | P2 | bug | fixed | `registry.rs` `update`; `listing.rs` `ask_all` | Registry download uses the caller's `Cancel`, and `ask_all` stops between packages when it fires. |
| B86 | P2 | bug | fixed | `source/plugin.rs` `allowed_asset_url`, `plugin_fetch_headers` | ketch fetches a plugin asset only for `https` or loopback `http`. `Authorization` and hop-by-hop headers, including names listed by `Connection`, are dropped before the request. |
| B87 | P2 | bug | fixed | `source/local.rs` `sha256_tree` | The local tree hash framed only `path\0bytes`. It now frames kind, path, permission bits and length, and hashes a symlink's target. A lock from before this framing does not match until `ketch lock`. |
| B88 | P2 | bug | fixed | `process.rs` `terminate` | After a polite stop, ketch waits until the pid is gone, bounded by five seconds, and only then sends the forceful kill. The replace that follows no longer races a process that is still exiting. |
| R14 | P2 | dead code | confirmed | `scripts/b32_patch.py:8` | The script opens `src/self_update.rs`, which has moved, and nothing references it. Delete it. |
| R15 | P2 | dead code | fixed | root `Cargo.toml:61` (`dirs`); `manifest.rs:137-148` (`Resolver::aliases`); `model.rs:596-599` (`Release::asset`); `platform/mod.rs:49-50` (`Placement::version`); `model.rs:329-331` | Drop the unused root `dirs` dependency, `Resolver::aliases` (or wire it to `__complete`/search), `Release::asset` and the never-read `Placement::version`; remove the stale `allow(dead_code)` on `PackageSpec::raw` (read at `state.rs:162`). |
| R16 | P2 | dead code | fixed | `fuzz/seed.sh` | The seed script and its readme name `crates/ketch-core/src/builtin.toml`. |
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
| B94 | P1 | bug | fixed | `install.ps1:310` | `Join-Path $TempDir 'ketch' $BinaryName` passes three positional arguments; Windows PowerShell 5.1, the shell `irm \| iex` runs in, has no `-AdditionalChildPath` and throws after extraction. CI only runs `pwsh ./install.ps1 --help` (`ci.yml:105-110`). Nest the calls and add a CI step that runs the script under `shell: powershell` past extraction. |
| B95 | P1 | bug | fixed | `shell.rs:688-730` (`read_user_path`, `write_user_path`); `install.ps1:105-119` | The user PATH is read expanded via `GetEnvironmentVariable('Path','User')` and written back as `REG_SZ`, so every `%USERPROFILE%`/`%JAVA_HOME%` entry is frozen; `read_user_path` also decodes stdout with `from_utf8_lossy` without the UTF-8 `OutputEncoding`, corrupting non-ASCII entries. Hits `path install/uninstall`, `doctor --fix`, `self uninstall` and the installer. Read and write `HKCU\Environment\Path` through `Microsoft.Win32.Registry` with `DoNotExpandEnvironmentNames`, keep the value kind, and go through the UTF-8 `powershell()` helper. |
| B96 | P2 | vuln | fixed | `.github/workflows/ci.yml:11-12, 55, 98, 307, 449` | Jobs only test `pull_request.draft != true`, which is true on `issue_comment`, and `hakari` has no `if`: any comment on any issue or PR runs the whole matrix on `main` with `secrets: inherit` and macOS minutes (run 38115907682). Comment runs share the `ci-refs/heads/main` concurrency group, so a queued one replaces a pending push-to-main run (38115904555). Gate every job but `review` on `github.event_name != 'issue_comment'`, or move `/review` into its own workflow. |
| B97 | P2 | vuln | fixed | `.github/workflows/release.yml:117-120`, produced by `scripts/dist-generate.sh:80-87` | `CODESIGN_CERTIFICATE`, its password and `GH_TOKEN` (with `contents: write`) are job-level env on all four build runners, so every dependency `build.rs` and proc-macro can read the Developer ID `.p12`. Patch the env so the certificate reaches only the macOS signing step, and drop `GH_TOKEN` where builds do not need it. |
| B98 | P2 | bug | confirmed | `install.ps1:3`, `:149-326` (every `exit`) | Under `irm … \| iex` the script runs in the caller's session: any error path's `exit 1` closes the user's PowerShell window before the message can be read, and `$ErrorActionPreference = 'Stop'` and script variables leak into the session. Wrap the body in a function or scriptblock, `throw`/`return` instead of `exit`, restore the preference. |
| B99 | P2 | vuln | fixed | `extract/archive.rs` ~604 (zip `io::copy`), 462-469 (tar `unpack_member`), ~643, ~659 (lone gz/xz/bz2) | No extractor bounds the bytes written or the number of entries; a small gzip/bzip2 bomb or a zip with millions of tiny entries fills the disk under the root. B84 caps only the download. One shared extraction budget (bytes and entries, ratio of compressed size plus an absolute ceiling) across every extractor. |
| B100 | P2 | vuln | confirmed | `extract/archive.rs` ~595 (zip symlink branch) | A zip symlink's target is read with an unbounded `read_to_string`; a symlink entry whose body is gigabytes of highly compressed bytes is decompressed into memory. Read through `take(4097)` and reject targets over 4 KiB. |
| B101 | P2 | bug | fixed | `extract/archive.rs:462-469`, `:420-423` | `payload_mode` is applied to directories as they are written. A `0o555` directory before its children fails extraction with EACCES (Nix and some reproducible tarballs; GNU tar copes); one after its children stays read-only, so `remove_any` (`platform/unix.rs:46`) fails on uninstall and upgrade and temp dirs leak. Apply directory modes after all members are written and always force `u+rwx` on directories. |
| B102 | P2 | bug | fixed | `extract/archive.rs:71-127` (`materialize_symlink_as_copy`, `copy_dir_contents`) | On Windows without symlink rights, a member `d -> .` or `sub/d -> ..` passes `check_link_target` and is materialised by copying its target into itself while `WalkDir` walks it: endless recursion or a full disk. Refuse to materialise a directory link whose target is an ancestor of the link, or snapshot the entry list first. |
| B103 | P2 | bug | fixed | `install.rs:262` (`prepare`), `install.rs:647` (`commit`); `lockfile.rs` plan | State is keyed by name and nothing compares `existing.source` with `manifest.source`. `ketch install otherowner/ripgrep --yes` silently replaces `BurntSushi/ripgrep`, takes over its `rg` link, runs update hooks and mixes retained versions from two sources; `ketch sync` plans a same-name, other-source lock entry as missing and does the same. Refuse in `prepare` when the sources differ and point at `--name`. |
| B104 | P2 | bug | fixed | `platform/unix.rs:131, 473`; `platform/macos.rs:263, 430`; `platform/windows.rs:404, 543` | Placement clears each occupied destination (old symlink, copied `/Applications/X.app`, Windows bin copy) right before writing the new one; on a later failure only the new links are unplaced and the store restored, so the old version's links and app copy are gone while state still records them. A `copy_tree` of a large `.app` failing on a full disk loses the working app and leaves an unrecorded half copy. Move occupied destinations aside and restore them on failure. Adjacent to B79, which covers only the store. |
| B105 | P2 | bug | fixed | `platform/windows.rs` (`retired_targets`) | A same-version reinstall compares a copied bin with `<store>.old`, so the copy ketch made is still recognised as ours. |
| B106 | P2 | bug | confirmed | `platform/windows.rs:328`; `model.rs:1462` (`glob_match`) | A manifest `bin.path` written with `/` (`bin/tool.exe`) is matched against `rel.to_string_lossy()`, which uses `\` on Windows; `glob_match` does not normalise separators, so any manifest with a directory in `bin.path` fails with "payload does not contain it". Normalise both sides to `/`, add a Windows test, and check `extra::resolve_under`. |
| B107 | P2 | bug | fixed | `self_update.rs` (`record_self_docs`) | Self install and self upgrade save the new package before linking man pages and completions. A user-owned file at a doc destination is a warning, so `state.json` names the version that is on disk and a later upgrade does not repeat the failure. |
| B106 | P2 | bug | fixed | `model.rs` (`glob_match_path`); `platform/windows.rs`, `platform/unix.rs` | A manifest `bin.path` written with `/` (`bin/tool.exe`) was matched against `rel.to_string_lossy()`, which uses `\` on Windows, so any directory in `bin.path` failed. Both sides are folded to `/` before the glob. `extra::resolve_under` still refuses a backslash: `safe_member_path` treats one as an unsafe name, and `doc/rg.1` already resolves. |
| B107 | P2 | bug | confirmed | `self_update.rs:113` (`install_self`), `:402`, `:487` (`update`) | `expose_self_docs(...)?` runs after the new version is placed but before `state.save`. A user-owned completion or man file at the destination makes `clear_destination` fail: the new binary is on disk, `state.json` names the old one, and every later self upgrade repeats it. The `AlreadyInstalled` branch already warns instead. Save state first and make docs exposure a warning everywhere. |
| B108 | P2 | bug | fixed | `process.rs:323-342` (`cmd_hits`) | A process counts as an occupant when its command line contains the path as a plain substring. Upgrading `rg` with `--yes` SIGTERMs, then SIGKILLs, a running `…/bin/rga`, `…/rg-helper`, or any editor or shell whose arguments mention the path (Linux, Windows). Match whole NUL-split arguments against exact canonical paths and prefer exe and open-fd evidence. Separate from B88. |
| B109 | P2 | vuln | fixed | `state.rs:235-276` (`Lock::acquire_path`) | `create_new` and the pid write are not atomic: a second process that reads the still-empty file calls it stale and renames the live lock away. Two processes that judged one dead pid stale can each rename, the second over the first's fresh lock. Both then run, and the first `Drop` deletes the other's lock. Write the pid to a temp file and link it into place without replace, treat an empty lock as busy unless old, re-read before renaming, or use an OS advisory lock. |
| B110 | P2 | vuln | fixed | `desktop/macos/Ketch/Store/KetchStore.swift` | Changelog Markdown keeps a `.link` run only when its scheme is `https`. Other schemes stay as text, so the default `openURL` action cannot open them. |
| B111 | P2 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`; `desktop/windows/Ketch.AppCore/FakeKetchCore.cs:131`; `crates/ketch-ffi/src/lib.rs:166-171` | Discover's shelf calls `search("")`. Both fakes return the whole registry; the real core returns `Other("nothing to search for")`, so with the live core (R9) Discover opens with an error and an empty shelf. No contract scenario covers it. Decide the semantics in `ketch-ffi` (or add `browse`) and add a scenario. |
| B112 | P2 | bug | suspected | `desktop/windows/Ketch.App/Pages/InstalledPage.xaml.cs:63-74`; `MainWindow.xaml.cs:85-86, 124-151` | Uninstall and Install buttons stay enabled while an operation runs, so a confirm `ContentDialog` can be open when the core asks a decider question; WinUI allows one dialog, `ShowAsync` throws inside the fire-and-forget `AskAsync`, and the core thread blocks in `done.Wait()` until Cancel. Disable those buttons while `IsRunning`; catch dialog failures and answer `null`/`false`. |
| B113 | P3 | vuln | fixed | `extract/mod.rs:81-136` (`safe_member_path`); `config.rs:471` (`sanitize_component`); `model.rs:1116` (`usable_file_name`) | DOS device names (`CON`, `NUL`, `AUX`, `PRN`, `COM1-9`, `LPT1-9`, also with an extension), names with a trailing dot or space, and `<>:"\|?*` pass. An archive member, bin, tag or package named `nul.txt` writes to a device, `foo.` collides with `foo`, a store dir can fail to create. Reject these case-insensitively per component on Windows. |
| B114 | P3 | vuln | fixed | `extract/archive.rs:304-331` (`check_link_target`); readers `extra.rs:197`, `changelog.rs` `find_file`, `platform/unix.rs:61, 227`, `platform/windows.rs` `payload_executable_entry` | Link targets are checked lexically, ignoring links created earlier: `L -> .` then `N -> L/L/../../..` passes but escapes. `resolve_under`, `find_file`, `ensure_executable` (chmod follows links) and `payload_executable_entry` (checks one hop) then follow it, so local files outside the store can be linked into bin, man or completion dirs and `chmod u+rx`'d. Canonicalize every link in `finish_deferred_links` and require it under the destination; canonicalize in `payload_executable_entry`. |
| B115 | P3 | vuln | fixed | `extract/macos.rs:175-199` (DMG `copy_volume`), `:210-237` (PKG) | DMG copy skips only top-level symlinks; `ditto` keeps nested absolute or escaping links, and PKG payloads get no link checks. The B114 readers then follow them outside the payload. After the copy, walk the tree with `symlink_metadata` and run every link through the containment check. |
| B116 | P3 | vuln | confirmed | `source/plugin.rs:204-244` (`discover`) | Plugin discovery scans the plugin dir and every PATH entry, including ketch's own bin dir, and runs each `ketch-source-*` with `capabilities`. A client app that ships such a binary is executed by every later ketch command that loads sources. Discover only in the plugin dir, or exclude `cfg.bin_dir` and anything resolving into the store. |
| B117 | P3 | bug | fixed | `changelog.rs:~99` (`from_file`), `src/cmd/query.rs:589` | The client changelog is read with an unbounded `read_to_string(...)?`: a multi-GB file or a link to `/dev/zero` exhausts memory, and a non-UTF-8 file makes `ketch changelog` fail instead of falling back to release notes. Require a regular file (`symlink_metadata`), read through `take` with a cap of a few MiB, decode lossily. |
| B118 | P3 | vuln | suspected | `install.rs` ~1420-1465 (`verify_checksum`); `src/cmd/pkg.rs:540` | When a release that was installed checksum-verified is followed by one without checksums (or an attacker controlling the release removes them), the upgrade installs with only the generic first-use warning; `checksum_verified` is never consulted. Refuse such a downgrade unless `--yes` or an explicit flag. |
| B119 | P3 | bug | fixed | `shell.rs:1302-1310` (`block_span`) | It pairs the first BEGIN with the first END after it. A stray BEGIN without END (hand edit, crash mid-write) makes `splice` append a second block, and a later `unsplice` deletes every user line between the orphan BEGIN and the real END. Pair END with the nearest preceding BEGIN or refuse when marker counts differ. |
| B116 | P3 | vuln | fixed | `source/plugin.rs:204-244` (`discover`) | Plugin discovery scans the plugin dir and every PATH entry, including ketch's own bin dir, and runs each `ketch-source-*` with `capabilities`. A client app that ships such a binary is executed by every later ketch command that loads sources. Discover only in the plugin dir, or exclude `cfg.bin_dir` and anything resolving into the store. |
| B117 | P3 | bug | confirmed | `changelog.rs:~99` (`from_file`), `src/cmd/query.rs:589` | The client changelog is read with an unbounded `read_to_string(...)?`: a multi-GB file or a link to `/dev/zero` exhausts memory, and a non-UTF-8 file makes `ketch changelog` fail instead of falling back to release notes. Require a regular file (`symlink_metadata`), read through `take` with a cap of a few MiB, decode lossily. |
| B118 | P3 | vuln | fixed | `install.rs` (`refuse_checksum_downgrade`); `src/cli.rs` `--allow-unverified` | A package installed against a published checksum refuses a release that publishes none. `--allow-unverified` or `--yes` consents; a lockfile hash stands in for that consent. |
| B119 | P3 | bug | confirmed | `shell.rs:1302-1310` (`block_span`) | It pairs the first BEGIN with the first END after it. A stray BEGIN without END (hand edit, crash mid-write) makes `splice` append a second block, and a later `unsplice` deletes every user line between the orphan BEGIN and the real END. Pair END with the nearest preceding BEGIN or refuse when marker counts differ. |
| B120 | P3 | bug | fixed | `shell.rs` (`marker_at_line_start`, `block_span`) | A marker line may end in `\r\n` or `\r`. The span consumes that ending, so a CRLF profile is updated in place instead of gaining a second block. |
| B121 | P3 | bug | fixed | `shell.rs` `startup_target` | A dangling startup symlink is followed with `read_link`. The target file is written when its directory exists; a missing checkout is refused, and the symlink is left in place either way. |
| B122 | P3 | bug | fixed | `src/ui.rs` (`BarProgress::start`) | The download label reaches `set_message` through `printable` before it is truncated, the same filter as every other status line. |
| B123 | P3 | bug | fixed | `platform/{macos,unix,windows}.rs` `restore_retired_store`, from `relink`/`rollback` | In these paths `move_into_store` is a no-op and `sweep_swap_leftovers` does not run; if linking fails while a stale `<ver>.old` from an earlier interrupted swap exists, the error path deletes the live prefix and renames the stale copy over it. Restore only when this call made the swap (have `move_into_store` say so). |
| B124 | P3 | bug | fixed | `platform/windows.rs:80-104` (`busy_aside`, `clear_for_replace`) | `tool.exe.old.<pid>-<ms>` files left when a running exe is renamed aside are never swept (only ketch's own asides are), so they pile up with every upgrade of a running tool. Sweep `*.old.*` siblings of recorded links at the start of commit, relink and uninstall. |
| B125 | P3 | vuln | confirmed | `crates/ketch-ffi/src/callbacks.rs:128-162`; `crates/ketch-ffi/src/records.rs:238-241` | Event `detail`/`verb`/`name`/`label` and `Changelog.version` carry client asset names and tags to Swift, C# and Vala unsanitised, though the `records.rs` header says they pass through `changelog::sanitize`; GUIs get bidi overrides raw. Sanitise string fields in `From<report::Event>` and `Changelog::new`. |
| B126 | P3 | bug | fixed | `crates/ketch-capi/vapi/ketch.vapi` (`StopFunc`); `crates/ketch-capi/include/ketch.h:37` | `KetchStopFn` returns C `bool`, the Vala delegate returns `gboolean` (int): an incompatible function-pointer type (error by default on GCC ≥ 14) and a return-ABI mismatch. `tests/capi.vala` never passes a `ChooseFunc` or `StopFunc`. Return `int32_t` and cover both callbacks in the Vala test. |
| B127 | P3 | bug | confirmed | `crates/ketch-capi/src/lib.rs:61-65` (`ErrorBody`) | `ErrorBody` flattens `KetchError`, whose `Network`/`Verification`/`Other` variants already have `message`, and adds its own: the JSON envelope has a duplicate `"message"` key that strict parsers reject. Skip or rename the outer field. |
| B128 | P3 | bug | fixed | `desktop/macos/Ketch/Core/KetchCoreProtocol.swift` | `busy(pid:)` is optional. An unknown holder is logged and shown without a pid, the same as the Windows app. |
| B129 | P3 | bug | confirmed | `desktop/macos/Ketch/Store/KetchStore.swift:135-145, 312-321` | The background update loop and window-key refresh report errors through `report`, which sets `errorMessage`; offline with the real core, a modal alert pops on every interval and focus change. Log background failures without alerting. |
| B125 | P3 | vuln | fixed | `crates/ketch-ffi/src/callbacks.rs` `From<report::Event>`; `records.rs` `Changelog::new` | Event strings and `Changelog` name/version pass through `changelog::sanitize` before they reach Swift, C# and Vala. |
| B126 | P3 | bug | suspected | `crates/ketch-capi/vapi/ketch.vapi` (`StopFunc`); `crates/ketch-capi/include/ketch.h:37` | `KetchStopFn` returns C `bool`, the Vala delegate returns `gboolean` (int): an incompatible function-pointer type (error by default on GCC ≥ 14) and a return-ABI mismatch. `tests/capi.vala` never passes a `ChooseFunc` or `StopFunc`. Return `int32_t` and cover both callbacks in the Vala test. |
| B127 | P3 | bug | fixed | `crates/ketch-capi/src/lib.rs:61-65` (`ErrorBody`) | `ErrorBody` flattens `KetchError`, whose `Network`/`Verification`/`Other` variants already have `message`, and adds its own: the JSON envelope has a duplicate `"message"` key that strict parsers reject. Skip or rename the outer field. |
| B128 | P3 | bug | confirmed | `desktop/macos/Ketch/Core/ContractScenario.swift:132`; `KetchCoreProtocol.swift:99` | `Busy` with an unknown holder becomes `pid 0`, and the user reads "ketch is running in another process (pid 0)". Make `busy(pid:)` optional, as on Windows. |
| B129 | P3 | bug | fixed | `desktop/macos/Ketch/Store/KetchStore.swift` | The update timer and a window becoming key call `refresh(alerting: false)`. A failure is a log line. A refresh the user asked for still sets `errorMessage`. |
| B130 | P3 | bug | fixed | `desktop/macos/Ketch/Views/DiscoverView.swift:44-47`, `PackageDetailView.swift:59-62`; `desktop/windows/Ketch.App/Pages/DiscoverPage.xaml.cs:36-42` | Core calls ignore task cancellation, so an older, slower search or changelog reply can land after a newer one (Windows also searches on every keystroke). Tag requests and drop out-of-date replies; debounce search. |
| B131 | P3 | bug | fixed | `desktop/windows/Ketch.AppCore/KetchStore.cs` (`Operate`) | When an operation is already running, `Operate` returned silently: a confirmed Uninstall or a clicked Install did nothing. It now sets `ErrorMessage` to the same sentence macOS uses. `A_second_operation_while_one_is_running_says_so` covers it. |
| B132 | P3 | bug | fixed | `install.ps1` | `--version 0.10.0` is given a leading `v` before the download URL is built, the same as `install.sh`. A tag that already starts with `v` is left alone. |
| B133 | P3 | vuln | confirmed | `install.sh:7-359` | The body is not wrapped in a function, so under `curl \| bash` a truncated download runs whatever complete lines arrived. Low impact today, but the guard is missing. Put the body in `main() { … }` and call `main "$@"` last. |
| B134 | P3 | bug | fixed | `install.ps1` | The installer adds `Tls12` with `-bor` before any download, so older Windows PowerShell can reach GitHub and a host that already enabled TLS 1.3 keeps it. |
| B133 | P3 | vuln | fixed | `install.sh:7-359` | The body is not wrapped in a function, so under `curl \| bash` a truncated download runs whatever complete lines arrived. Low impact today, but the guard is missing. Put the body in `main() { … }` and call `main "$@"` last. |
| B134 | P3 | bug | suspected | `install.ps1:229, 263` | `[Net.ServicePointManager]::SecurityProtocol` is never set; on older Windows 10/Server, PowerShell 5.1 defaults to TLS 1.0/1.1, which GitHub rejects. OR in `Tls12` at the top. |
| B135 | P3 | bug | confirmed | `.github/workflows/ci.yml:439-445` | `/review` dispatches with `--ref "$branch"` from the PR's `headRefName`; for a fork PR that ref is missing (the job fails) or names a base branch such as `main`, and CI runs on `main` while the bot says it ran on the PR head. Refuse when `headRepository` is not this repo, or check the dispatched ref's SHA equals the head SHA. |
| B136 | P3 | bug | fixed | `Justfile` (`lint-cask`); `ci.yml` package job | `just check` and the macOS package job wrote `cask/Casks/ketch.rb` into the repo root and `cask/` was not ignored, so `scripts/release.sh --local` stopped at its clean-tree check. Both now write `target/cask/Casks/ketch.rb` (`target/` is gitignored; `brew style` still sees a `Casks/` directory). |
| B137 | P3 | bug | confirmed | `tests/commit-msg-breaking.sh`; `Justfile` `lint-commits`; `ci.yml` | `done.md:375` says the test is wired into `just lint-commits`, but neither the Justfile nor CI calls it. Add it to `lint-commits` and CI's shell step. |
| B138 | P3 | vuln | fixed | `Cargo.lock` (cargo-deny 0.20.2, default config) | RUSTSEC-2026-0285: rustls 0.23.43 via ureq and octocrab, fixed in ≥ 0.23.45 (low impact; `cargo update -p rustls` needs creator permission). RUSTSEC-2023-0071: rsa 0.9.10 (Marvin) via pgp, sigstore and jsonwebtoken, no upstream fix, verification-only use; record an explicit ignore with that reason in a new `deny.toml`. |

Fixed and merged, cards in `done.md`: B84 (#301), B90 (#295), B91 (#317), B92 (#307), B93 (#316), B95 (#308), B97 (#311), B101 (#302), B103 (#298), B104 (#296), B108 (#310), B109 (#309), B111 (#313), B114 (#304), B115 (#305), B124 (#299), B130 (#314).

| # | Status | Priority | Complexity | Readiness | Agent |
| --- | --- | --- | --- | --- | --- |
| R5 | in progress | P2 | 4 | 90% | Claude Code / opus-5.5 |
| F13 | in progress | P3 | 4 | 80% | Claude Code / opus-5.5 |
| D12 | todo | P3 | 4 | 0% | |
| D17 | todo | P3 | 4 | 0% | |
| D18 | todo | P3 | 3 | 0% | |
| D19 | todo | P3 | 4 | 0% | |

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

Status: PR https://github.com/pyrlyn/ketch/pull/187 merged 2026-10-01. The version moved to `[workspace.package]`; `release.sh` and `crate-version.sh` read it there, and v0.9.0 was released after the merge. Remaining: the doc examples that became doctests are marked `ignore` and should be rewritten (18 `ignore` fences under `crates/` at the last check).

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

### D17. Linux: the app on the real core

Swap the Linux app's fake core for `ketch-capi`. Depends on D15 and D16.

Done when every screen runs on the C ABI, calls run off the main loop with events returned through the `GLib.MainContext`, cancel and `Busy` behave as in the contract, and a manual pass against a scratch root is recorded.

### D18. Linux: notifications, background and autostart

GNOME has no tray in its HIG; an app that checks in the background asks the Background portal and notifies through `GNotification`. Research: sections 1 and 3c.

Done when update notices go through `GNotification` (the Notification portal under Flatpak), background running and start at login are requested through the Background portal (libportal) with an XDG autostart entry outside a sandbox, `ketch://` links follow D8's rules, and there is no tray: the creator deferred a StatusNotifierItem tray (2026-10-01, open decision 8).

### D19. Linux: packaging and release

How the Linux app reaches users. R10 left Flatpak against distribution packages open, and Flatpak needs home access for PATH work.

Done when the creator has chosen the format, CI builds it on `desktop-linux-v*` tags without touching `/releases/latest`, the app ships AppStream metadata and a `.desktop` file that validate, and updates come from the chosen package manager.
