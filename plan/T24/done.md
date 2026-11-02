# T24. Completed

PR: https://github.com/pyrlyn/ketch-kotlin/pull/3

## Changes

- `9fd4ca3`: bound unanswered desktop question handlers to their call's lifetime, removed cancelled callers from the open-call registry, and explicitly completed pending calls before shutting down the reader. Closing while stderr was draining previously left pending and subsequent calls waiting forever; a completed cancelled operation also left its question coroutine alive. Both regression tests failed with timeouts before the patch and pass afterwards.
- `a66ba46`: merged registry offers against installed rows only. Previously every offer filtered an output list that grew with preceding offers, even though those rows could never match. The matching phase changes from O(A * L + A²) to O(A * L), for A offers and L installed packages; sorting is unchanged. Tests preserve case-insensitive name priority, source fallback and one-to-one consumption.

The existing `sameSource`, `normalizeName`, sorting and fixture helpers are reused. The kotlin-kit search found no equivalent package-list merge or desktop call-lifetime facility. Existing kotlinx.coroutines scopes provide lifecycle ownership; no new dependency or shared utility is needed. Listing semantics were checked against Rust's `crates/ketch-core/src/listing.rs::merge`.

## Validation

`./gradlew build` passed on macOS arm64 with the shared-build environment variables set for this worktree. It includes compilation, tests, ktlint and detekt:

| Test target | Tests | Failures |
| --- | ---: | ---: |
| Desktop JVM | 235 | 0 |
| Core macOS arm64 | 807 | 0 |
| CLI macOS arm64 | 345 | 0 |
| Wire macOS arm64 | 7 | 0 |
| Wire JVM | 8 | 0 |
| Total | 1402 | 0 |

Windows artifacts compiled and linked; Windows tests were skipped on macOS. No dependencies or pinned versions changed. The build reported Gradle deprecation and native debug-symbol warnings but completed successfully.

The same 10,000-offer, no-installed-packages test took 4.713 seconds before the merge change and 1.002 seconds after it, as recorded in the native JUnit report. These are single-run test timings, including fixture creation and assertions, rather than an application-wide benchmark. Before the change, this input scanned 49,995,000 offered-only rows; afterwards it scans none.

The initial baseline tests passed. Two overlapping verification builds collided while writing native build artifacts; rerunning the complete build as a single Gradle invocation passed. The main checkout's existing local settings were preserved.
