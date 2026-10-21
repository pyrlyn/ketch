---
id: B70
title: Flaky `upgrade_stops_a_process_holding_the_binary_when_yes`
status: done
created_at: 1792583218
updated_at: 1792583218
---

`tests/auto_update.rs` starts the installed sleeper, sleeps a fixed 400 ms, then runs `ketch upgrade --yes` and expects it to report the process as `in use`. On macOS under load (several cargo builds in parallel) it failed on 2026-09-30 and 2026-10-01 and passed when run alone. Done means the test waits on a condition, not a delay, and survives a stress loop.

Plan (Claude Code / opus-5.5):
1. Reproduce: run the built `auto_update` test binary 64-way in parallel for several rounds.
2. `tests/support/mod.rs`: `Entry::sleeper` creates the file named by `KETCH_TEST_SLEEPER_READY`, when set, before it starts waiting (sh and cmd).
3. `tests/auto_update.rs`: set that variable on the child and wait for the file (bounded at 60 s so a sleeper that never starts fails instead of hanging) in place of the 400 ms sleep.
4. Verify: the same stress loop, then `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`.

Done (Claude Code / opus-5.5): reproduced with the prebuilt test binary run 64-way in parallel — 2 of 192 runs failed with no `in use` line, because the shell had not opened the script within 400 ms, so `lsof` found nobody. After the fix: 0 of 800 runs failed (64- and 96-way), and the full suite passes. Running `cargo nextest` several times at once was no use as a reproducer: concurrent runs relink the binaries and macOS SIGKILLs them.

Status: done 2026-10-01
