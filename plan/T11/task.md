---
status: in progress
priority: P1
complexity: 3
readiness: 85%
agent: Claude Code / claude-opus-5-5
---

# T11. `ketch serve` and the desktop protocol

A JSON-lines mode of the binary with the `ketch-ffi` surface: one call per operation
(`installed`, `search`, `outdated`, `install`, `upgrade`, `uninstall`, pin, rollback, prune,
changelog, info, history, doctor, path, registry refresh, config), events, questions
(`choose_binary`, `stop_processes`) and cancellation. Records live in `:ketch-wire`; the
`desktop/contract/scenarios` files replay against it.

## Plan

The Rust side is `crates/ketch-ffi` (`lib.rs`, `records.rs`, `callbacks.rs`, `error.rs`, about
2.6k lines with tests) and the scenarios `tests/contract.rs` generates. UniFFI and
`crates/ketch-capi` are not ported: the app runs `ketch serve` instead of linking the core.

1. `:ketch-wire` (common, native and jvm): the records of `records.rs`, the events, questions
   and answers of `callbacks.rs`, `KetchError`, the calls (one per `KetchCore` method, named
   as the scenarios name them), the scenario file shape, and the JSON-lines messages:
   `hello`, then `event`, `ask` and `result` from the binary; `call`, `answer` and `cancel`
   from the app. Field names are the Rust ones in snake case.
2. The scenarios copied to `desktop/contract/scenarios` as Rust generates them; a jvm test
   decodes every file into the wire types and encodes it back unchanged.
3. `:ketch` `ketch.serve`: `KetchCore` (the port of `lib.rs`, one function per call), the
   record and error conversions, the reporter and decider adapters, with every unit test of
   the four Rust files.
4. A hidden `ketch serve` command (help output stays Rust's): stdin is read on its own
   thread, so answers and cancels reach a running call; calls run one at a time in arrival
   order (the kotlinx-io `basename` race of T8); stdin closing declines open questions, lets
   what was sent finish and ends it (stopping work is what `cancel` is for).
5. End-to-end tests of `ketch serve` against the built binary: hello, a local install with
   its events, a busy lock, a malformed line, a usage error. Questions answered and cancels
   need input after output, which the process kit cannot drive; `ServerTest` covers them in
   process over the same `Server`.

Files: `ketch-wire/src/`, `ketch/src/nativeMain/kotlin/ketch/serve/`, `ketch/src/nativeTest/`,
`desktop/contract/`. Check: `./gradlew build`, the Linux compile with
`-Pdiffuser.allTargets=true`.

