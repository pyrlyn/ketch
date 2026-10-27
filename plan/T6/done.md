# T6. Platform backends, processes, shell — done

`platform` is ported: `Placement`, `TrustVerdict`, `DoctorCheck`, the `Platform` interface and
`host()`, the store swap, claims, and the Unix layer (discovery, bin specs, links, preflight,
extras, ownership). `MacOsPlatform` places binaries and `.app` bundles (copied with `ditto`,
or linked), checks trust with `codesign` and `spctl`, clears quarantine and runs its doctor
checks; `LinuxPlatform` places binaries. `process` finds who holds a file being replaced (`lsof`
on macOS, `/proc` on Linux), offers to stop them and terminates them. `shell` writes, rewrites and
removes the PATH block in bash, zsh and fish startup files, detects shells, and reports status
and the doctor PATH check. The decisions are in `plan/README.md` under Decisions.

Not ported here, waiting for T13 with their tests: `#[cfg(windows)]` code and the Windows-only
helpers (`windows_path_*`, PowerShell profile writing and quoting, the cmd AutoRun edit, the
registry user PATH) — 22 `shell.rs` tests among them. The Rust test of a sibling name that is not
UTF-8 has no Kotlin counterpart: a Kotlin `Path` always holds a valid string.

Commits: kotlin-kit fd08f82, 9fc0059 (fs), 97010d0 (process); ketch-kotlin d85f679, 2afe556,
6db50d0, f3fe762, 364c75d, 3699dfb and this close.

Checked on the Mac: `./gradlew build` (411 native tests, ktlint, detekt) is green, `./gradlew
check` in kotlin-kit is green, and `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxArm64
:ketch-core:compileTestKotlinLinuxArm64` compiles. The Linux-only tests (`LinuxHostTest`, the
`/proc` listing) compile but run only on a Linux host.
