# T2. Pure core — done

Ported from `crates/ketch-core`:

- `error` → `ketch.core.error.KetchError`: messages, `details`, `hint` and exit codes as in Rust.
- `text`: `bytes`, `truncate`, Rust `str::lines`, Rust byte order for sorting.
- `diff` → `unified`.
- `cancel` → `Cancel`.
- `decide` → `Decider`, `NoDecider`.
- `bin_choice` → `ketch.core.binchoice`.
- `platform/scoring`, with the model types it reads (`Os`, `Arch`, `TargetSpec` and the native `host()`).

Every Rust unit test of these files is ported. The `fs_kit` error test waits for `fsio` (T4).

The code meets the strict detekt set unchanged: no rules loosened, `config/detekt/detekt.yml` holds
octo's overrides. The placeholder `VersionTest` from T1 is gone.

Check: `./gradlew :ketch-core:build` green on macosArm64. That covers 56 T2 tests, ktlint and
detekt. `-Pdiffuser.allTargets=true` also compiles linuxX64 and linuxArm64.

Commit: `T2: pure core`.
