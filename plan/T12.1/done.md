# T12.1. The app's core: done

`desktop/src/main/kotlin/ketch/desktop/core/` is the core as the app sees it:

- `Core`: one suspending `call` per operation; `value` reads the result as the type the call
  returns.
- `Callbacks`, `CancelToken`, and `CoreException`, which carries the wire `KetchError` in the
  Rust apps' wording.
- `ServeCore` runs `ketch serve`:
  - It checks the `hello` protocol and routes events, questions and results by call id.
  - Each question is answered on its own coroutine. A cancel token and a cancelled caller
    both send `cancel`.
  - A child that dies fails every open call with its exit and the tail of its stderr. A line
    that is no message is skipped.
  - `close` cancels what is open, closes stdin and kills the child after five seconds.
- `locateKetch`: `KETCH_EXE`, else `ketch` in the packaged app's resources directory.

`desktop/src/test/.../FakeCore` replays `desktop/contract/scenarios` as the Rust apps' fakes do:

- A read returns its scenario's value.
- Install, upgrade and uninstall play the script and stop with `Cancelled` between steps. A
  declined binary choice cancels. Each changes the installed list the fake answers.

The desktop tests depend on the host's debug `ketch` link task and get it as `KETCH_EXE`.

Duplicate-code check: `decline` repeats the native `Server`'s, in a JVM module that cannot
reach it. Moving it to `:ketch-wire` would serve both; that is left to the next change that
touches the wire.

Commits: `af2438d` (T12: split and claim T12.1) and the T12.1 code commit after it, on `main`.
No remote yet, so no PR.

Checked on the Mac (arm64): `./gradlew :desktop:test` runs 14 tests, all green:

- 7 on the fake: every scenario plays back as the core sent it, cancels, installed-list
  changes, error wording, the token.
- 7 on `ServeCore`. 4 run against the built native `ketch` on a scratch root and home: an
  empty root, a local install with its events, a question answered while the call waits,
  and a cancel that declines an unanswered question. 2 use scripted stand-ins: a child that
  dies, and a wrong or missing hello. 1 tests `locateKetch`.

`./gradlew build` is green.
