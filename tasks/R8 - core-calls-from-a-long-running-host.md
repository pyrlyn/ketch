---
id: R8
title: Core calls from a long-running host
status: in-progress
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

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
