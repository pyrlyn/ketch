---
status: in progress
priority: P2
complexity: 4
readiness: 5%
agent: Claude Code / claude-opus-5-5
---

# T13.1. kotlin-kit on mingwX64

ketch on Windows needs the kotlin-kit modules it uses on a mingwX64 target: `fs`, `process`,
`archive` and `cli` are written over POSIX, `http-ktor` has engines for Apple and Linux only,
and `db-room`'s `androidx.sqlite:sqlite-bundled` 2.7.1 publishes no mingwX64 variant. Done when
every module ketch uses builds for mingwX64 with Windows implementations where POSIX does not
reach, macOS and Linux behave as before, and the modules' tests pass on the Windows VM.

## Plan

1. Declare mingwX64 in kotlin-kit's build next to the existing targets and cross-compile on the
   Mac to list what does not build.
2. Per module, the Windows side behind the same API: `fs` (file times, renames over open files,
   links), `process` (`CreateProcessW` in place of `posix_spawn`, quoting, deadlines), `cli`
   (console width, colour), `http-ktor` (Ktor's WinHttp engine), `archive` (bzip2 and xz built
   for mingw), SQLite (a mingwX64 driver; the choice goes in kit's README with its reason).
3. Tests: the existing suites run on mingwX64; Windows-only cases get their own tests.
4. Check: `./gradlew check` in kotlin-kit on the Mac; the mingwX64 test binaries run on the
   Parallels Windows VM; ketch-kotlin and rtok-kotlin still build.
