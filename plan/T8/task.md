---
status: in progress
priority: P0
complexity: 5
readiness: 55%
agent: Claude Code / claude-opus-5-5
---

# T8. Install and lifecycle

`install`, `listing`, `stats` (`stats.db`, schema identical to Rust's migrations), `doctor`,
`wizard`, `self_update`.

## Plan

About 8.5k lines of Rust and 143 tests, ported from 41738f64.

1. `stats`: SQLDelight 2.4.1 (the creator's choice; Rust uses diesel) with the schema copied
   from Rust's migration and typed queries in `.sq` files. Its driver is kotlin-kit's own
   `pyrlyn.kit:db-sqldelight`, a `SqlDriver` over kit `db`, so SQLite stays the bundled one. A
   new database also gets diesel's `__diesel_schema_migrations` row, so Rust ketch opens a
   `stats.db` written by the port and the other way round.
2. `install`, part one: `InstallRequest`, `prepare` (resolve, download, verify, extract, place),
   `commit` (hooks, state, lock, stats).
3. `install`, part two: `batch`, `uninstall`, `relink`, `unlink`, `pin`, `rollback`, `prune` and
   the upgrade helpers.
4. `listing`: rows, merge, sort, latest and cached lookups, footers and cells.
5. `wizard` and `doctor`.
6. `self_update`: install, update, stale aside check, uninstall plan and run.

Files: `ketch-core/src/nativeMain/kotlin/ketch/core/{stats,install,listing,doctor,wizard,
selfupdate}/`, tests beside them; kotlin-kit `db-sqldelight`. Check: `./gradlew build`, the
linuxArm64 compile with `-Pdiffuser.allTargets=true`, kit `check`, every ported test green on
the Mac.
