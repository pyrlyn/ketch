# T10. Command line: done

The `ketch` command line on Kotlin/Native: kit `:cli` with clap's semantics and output in place
of Clikt, the command tree, `main`'s error and exit-code funnel, `ui`, `__complete`, `man`,
`self_docs` and every `cmd/*` module, plus the end-to-end suite of `tests/*.rs` and the
`tests/cases/*.trycmd` snapshots run against the built `ketch.kexe`.

The suite runs offline: a sandbox root and `HOME` per test, a source plugin serving fixture
archives, and kit `:loopback` standing in for the GitHub API, catalogues and downloads.
`install.rs` lives in `ketch/src/macosTest/` and `install_linux.rs` in `ketch/src/linuxTest/`.
`task.md` lists the test files left to T14 and T15 and those not ported.

The suite found one behaviour difference, now fixed. Rust prepares every request in a batch
against the state from before the batch. The one-at-a-time Kotlin batch saw the first copy of a
package asked for twice and refused the second as already installed (`plan/README.md`, batch
installs).

Commits: `c785bcb` (T10: claim and plan) to `0256043` (T10: e2e install_linux), 31 commits on
`main`. The repository has no remote yet, so there is no PR.

Checked on the Mac (arm64):

- `./gradlew build`: compile, ktlint, detekt and 1060 native tests (230 of them end to end), all
  green.
- `./gradlew :ketch:compileTestKotlinLinuxArm64 :ketch:compileTestKotlinLinuxX64 -Pdiffuser.allTargets=true`,
  plus ktlint and detekt with the same flag: clean. The Linux tests are compiled, not run.
- kotlin-kit `./gradlew check`: green.
