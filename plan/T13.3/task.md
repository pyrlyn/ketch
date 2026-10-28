---
status: in progress
priority: P2
complexity: 3
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T13.3. The command line on Windows

The Windows parts of `src/` (PowerShell and cmd completions, `system`, the console), the Windows
end-to-end tests (`install_windows.rs`, `completion_windows.rs` and the Windows cases of the
others), and the mingwX64 `ketch.exe` inside the desktop app's MSI. Done when they pass on the
Windows VM. `install_ps1.rs` stays with the install scripts in T15 (the creator's decision).

## Execution plan

1. Build: `mingwX64()` in `:ketch` and `:ketch-wire`; split anything POSIX-only in `ketch/src`
   (tty, signals, `getenv` users) into `unixMain`/`mingwMain` actuals.
2. `src/`: `system.rs`'s user PATH branches (`path install`, `path uninstall`, `report_user`,
   `Setup.UserPath`), `ui.rs`'s virtual terminal switch for the console, paths shown with
   Windows separators.
3. E2E support on Windows as Rust's `tests/support`: the `.cmd` plugin, `.cmd` programs and
   sleepers, no chmod, `ketch.exe`; the suite's Unix-only tests moved to `unixTest`.
4. `install_windows.rs` and `completion_windows.rs` to `mingwTest`; PowerShell 7 installed on
   the VM (official MSI) so both PowerShells are tested, as in Rust.
5. MSI: `bundledKetch` takes the mingwX64 release `ketch.exe` on Windows; JDK 21 (Temurin MSI)
   on the VM; `packageMsi` built there; the installed app's `ketch.exe` runs.
6. Verify: `./gradlew build` on the Mac; `:ketch` mingwX64 tests on the VM as the user and as
   SYSTEM; record decisions in `plan/README.md`; close with `done.md`.
