---
id: B71
title: Ambiguous bin glob refuses instead of taking directory order
status: done
created_at: 1792583854
updated_at: 1792583854
---

`glob_preferred` (`crates/ketch-core/src/model.rs`, called from `platform/unix.rs` and `platform/windows.rs`) falls back to the first match in directory order when a `bin` glob matches several files and none has the entry's `name` as its stem, or the entry has no `name`. Directory order differs per OS (NTFS against ext4 and APFS), so the linked binary differs per OS: the same bug class as B62 and B64.

Decision (creator, 2026-10-01): refuse in that case. The error lists the candidate files, sorted so the message is the same on every OS, and says how to resolve it: set the `bin` entry's `name` or `path` in the manifest. `ketch install --bin` is not offered: it already refuses when the manifest names its binaries. A single match, or a stem equal to `name` (case-insensitive, `.exe` ignored), behaves as before. A glob matching nothing keeps its existing error. This is breaking: an install that used to link something now refuses, so the commit is `fix!:`.

Execution plan:

1. Add the failing unit test beside `glob_preferred_picks_the_stem_named_match_over_directory_order`.
2. Make `glob_preferred` return `Result<Option<&Path>>`, one OS-independent function; update both platform callers.
3. Update `docs/MANIFESTS.md` where `bin` globs are described.
4. Verify with `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo nextest run`, and a run of the binary against a scratch `KETCH_ROOT` if it can be done offline.

Status: done 2026-10-01, PR https://github.com/pyrlyn/ketch/pull/202. Unit and end-to-end tests in `tests/bin_choice.rs`.
