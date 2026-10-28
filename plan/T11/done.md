# T11. `ketch serve` and the desktop protocol: done

`ketch serve [--root DIR]` speaks JSON lines with the desktop app, in place of the UniFFI
library Rust's apps link (`crates/ketch-ffi`):

- `:ketch-wire` (common, native and jvm) holds the records of `records.rs`, the events,
  questions and answers of `callbacks.rs`, the `KetchError` the app sees, one `Call` per
  `KetchCore` method named as the scenarios name them, the scenario file shape and the messages
  (`hello`, `event`, `ask`, `result`, `rejected`; `call`, `answer`, `cancel`).
- The 19 scenarios Rust generates are in `desktop/contract/scenarios`, unchanged; a jvm test
  decodes each into the wire types, encodes it back unchanged, and reads its value with the
  serializer its call names.
- `ketch.serve` in `:ketch`: `KetchCore` (the port of `lib.rs`), the record and error
  conversions, the reporter and decider adapters, and `Server`, the JSON-lines loop.
  `serve` is read before the parser, so the help, man pages and completions stay Rust's.

The protocol decisions are in `plan/README.md` (`ketch serve`). One differs from the plan
written at claim time: the end of input no longer cancels the calls. A file of calls piped in
would otherwise end as soon as it was read. It now declines open questions and lets what was
sent finish; `cancel` is the way to stop work.

Every unit test of `lib.rs`, `records.rs`, `callbacks.rs` and `error.rs` is ported (48 tests),
plus:

- `ServerTest` (7): a question answered, stale answers, one call at a time, cancels, the end
  of input and errors, over a scratch root in process.
- `e2e/ServeTest` (5) against the built binary: hello and the version, a local install's
  events before its result, a busy lock, a rejected line and a usage error.

An answered question and a cancel during a run are not tested against the binary, because
kit `:process` cannot write to a child after it has read from it.

Duplicate-code check: the lock, `Cancel`, the reporter, `Decider` and the e2e sandbox are
reused, and so is `ui.SpinLock` for the server's shared state. `KetchCoreTest`'s private
`withEnv` mirrors ketch-core's test-only helper, which the `:ketch` tests cannot reach.

Commits: `1e07039` (T11: claim and plan) to `f65880f` (T11: ketch serve), then this close, on
`main`. The repository has no remote yet, so there is no PR.

Checked on the Mac (arm64):

- `./gradlew build`: compile, ktlint, detekt, 1126 native tests and 7 jvm tests, all green.
- `./gradlew -Pdiffuser.allTargets=true :ketch:compileKotlinLinuxX64 :ketch:compileKotlinLinuxArm64 :ketch-wire:compileKotlinLinuxX64 :ketch:compileTestKotlinLinuxX64`:
  clean. The Linux tests are compiled, not run.
