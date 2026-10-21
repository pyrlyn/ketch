---
id: F6
title: Deterministic process-listing tests
status: done
created_at: 1792583218
updated_at: 1792583218
---

`process::tests::lists_a_child_running_from_a_copied_file` and `yes_stops_the_child_holding_the_file` fail under a loaded `just check` after ~14 s (`panicked at src/process.rs:337`, "no occupant") and pass alone. Done means both tests pass or fail on what they assert, not on scheduling, and neither assertion is weaker than today.

Cause: the sleeper is a copy of `/bin/sleep`. macOS launch constraints SIGKILL a copied platform binary a few milliseconds after exec (`rc=137`, reproduced outside any sandbox), so the test only passes when `lsof` happens to catch the child before it dies. Under load each `lsof -t` scan takes ~280 ms, the first one lands after the kill, and 50 iterations × (scan + 20 ms) is the observed ~14 s. The stop test is vacuous for the same reason: its child is already dead before `offer_to_stop` runs.

Plan (done):

1. `src/process.rs` tests: on macOS spawn a copy of the test binary itself (not a platform binary, so launch constraints do not apply; `std::fs::copy` clones on APFS, so size is free), running a helper test that sleeps only when an env var is set. Linux keeps `/bin/sleep`, Windows keeps `PING.EXE`.
2. `wait_for`: fail at once with the exit status if the child has already exited, and bound the retry by a wall-clock deadline instead of an iteration count. `std::process::Command::spawn` returns after exec, so the first scan is expected to hit.
3. `yes_stops_the_child_holding_the_file`: assert the child is running and is the listed pid before stopping it, and that `wait()` reports it ended by a signal (Unix) / not successfully (Windows) — rather than the always-true "reaped pid is not alive".
4. Verify: `cargo nextest run`, `cargo clippy --all-targets`, `cargo fmt --check`; the two tests repeated under a parallel CPU load; confirm the old code fails that same stress.

Result: all four steps done. Stress (8 parallel copies of each test per round, 5 rounds, 24 extra busy loops on a machine already at load ~130): old tests 20 of 80 failed with the reported `panicked at src/process.rs:337`, new tests 0 of 80. `cargo fmt --check`, `cargo clippy --locked --all-targets -- -D warnings` and `cargo nextest run --locked --all-targets` (572 passed) clean on macOS. Not run locally: the Linux and Windows arms, which change only in structure (same `/bin/sleep` and `PING.EXE` sleepers) and the new exit-status assertions; CI covers them.

---

`mise use -g github:listepo/ketch` installs the release tarball through mise's GitHub backend with no change to the assets. Now a documented install path (README, `docs/COMMANDS.md`, `docs/TROUBLESHOOTING.md`), and ketch behaves around a binary mise owns: `self_update::mise_tool_dir` finds the running binary under `<mise data dir>/installs/<tool>` (`MISE_DATA_DIR`, `XDG_DATA_HOME/mise` or `~/.local/share/mise`, `%LOCALAPPDATA%\mise` on Windows). `ketch self upgrade` without a `ketch` package refuses to rewrite that file and points at `mise upgrade` or `ketch self install`. `ketch self uninstall` asks separately whether to run `mise --yes unuse -g <tool>` (`--yes` answers it too) and runs it last; on a decline it names the command. On Windows the running `ketch.exe` is renamed into `%TEMP%` first, since Windows will not delete a directory holding a mapped image. Unit tests for the path detection and the install-dir → tool-name mapping; `tests/mise.rs` runs on every OS with the real binary copied into a sandboxed mise tree and a `rustc`-built fake `mise` that deletes the tool directory while ketch is still running. Verified: `just check` clean, and the real binary against scratch mise dirs, `KETCH_ROOT` and `HOME` — refusal, `self install` from a mise-installed 0.4.7, uninstall with the mise question declined and accepted.
