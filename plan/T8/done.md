# T8. Install and lifecycle — done

Ported `stats`, `install`, `listing`, `wizard`, `doctor` and `self_update` from ketch-core into
`ketch-core/src/{nativeMain,commonMain}`, with their unit tests in `kotlin.test`: 144 Kotlin
tests in those packages (stats 12, install 52, listing 31, wizard 8, doctor 12, selfupdate 29)
against Rust's 143. Not here yet: the two wizard tests that load through `push` move to T9, and
the two Windows-only self-update probe tests move to T13. Every guard came with its test: a release
without a published checksum refused over a verified install, the lock held while the shell
blocks and the root are removed, a root that is the home directory (or reaches it through a
symlink) never wiped, foreign files under a shared prefix kept, and a new binary that never
answers its probe reported as stopped with the previous one kept.

`stats.db` is SQLDelight on kotlin-kit's `db-sqldelight`, schema-compatible with Rust's diesel
migration. Batch installs and `ketch list` lookups run one at a time (see `plan/README.md`);
running them side by side again is in `roadmap.md`. The PATH split is now one helper,
`splitPathVar`, shared by `Config.binDirOnPath`, plugin lookup and doctor.

kotlin-kit gained `db` cursors, `db-sqldelight`, `process.Command.status` and `currentExe`, and
`fs.rename` and `removeEmptyDir`.

Commits: kotlin-kit 2e2a5ed, 56e8c5b (db), a65fb32 (process), 93d6b8b (fs); ketch-kotlin
75b20d2, 15fb6d0, 48170a2, 382cd0a, afab76d, 2f58b53, 29ce5a8, 1823664 and this close.

Checked on the Mac: `./gradlew build` (684 native tests, ktlint, detekt) is green, `./gradlew
check` in kotlin-kit is green, and `-Pdiffuser.allTargets=true :ketch-core:compileKotlinLinuxArm64
:ketch-core:compileTestKotlinLinuxArm64` compiles. No test reaches the network or the real HOME.
