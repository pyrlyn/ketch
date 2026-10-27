---
status: in progress
priority: P1
complexity: 3
readiness: 100%
agent: Claude Code / claude-opus-5-5
---

# T9. Import and registry push

`import` (`mod`, `brew`, `linux`, `winget`, `apply`, `fetch`) and `push` (`ketch registry
push`: forks, refs, contents and pull requests through the GitHub REST API, as octocrab does).

Two wizard tests wait here because they load through `push`:
`the_push_loader_accepts_what_the_wizard_renders` and
`quotes_and_backslashes_in_an_answer_survive_the_round_trip` (`wizard.rs`). Port them with `push`.

## Plan

About 4.2k lines of Rust and 85 tests (plus the two wizard tests), ported from 41738f64.

1. `import/mod` and `fetch` in `ketch.core.importer` (`import` is a Kotlin keyword): release URL
   parsing, platform tokens, `assemble`, asset globs, `Endpoints` with the `KETCH_IMPORT_*`
   overrides, `Fetch` with an HTTP implementation and recorded responses for tests.
2. `brew`, `linux` (Arch repositories, AUR, `.SRCINFO`) and `winget` converters against the
   recorded fixtures in `import/fixtures`.
3. `apply`: writing the user manifest an import produces.
4. `push` in `ketch.core.push`: `load`, `open`, `current`, `plan` over an `Api` interface, and a
   GitHub implementation on the project's HTTP client. octocrab has no Kotlin counterpart; the
   handful of REST calls go through the existing `Http` client, which gains POST, PUT and PATCH
   through kotlin-kit's `http` interface.
5. The two push-gated wizard tests.

Files: `ketch-core/src/{commonMain,nativeMain}/kotlin/ketch/core/{importer,push}/`, tests beside
them; kotlin-kit `http` and `http-ktor` for the new methods. Check: `./gradlew build`, the
linuxArm64 compile with `-Pdiffuser.allTargets=true`, kit `check`, every ported test green on
the Mac, no network in tests.
