---
id: T901
title: Isolate UI test settings
status: done
created_at: 1792583854
updated_at: 1792583854
---

Concurrent unit tests mutate COLOR, EMOJI and LEVEL while other tests render snapshot lines, producing mixed styles. The creator approved this narrow fix alongside the owned R6 task, whose ownership and scope remain unchanged.

Execution plan: reproduce the snapshot failures with the threaded Cargo runner; use a test-only standard-library mutex guard around settings initialization and assertions; reuse existing snapshots as regression checks; repeat threaded UI tests, then run nextest, Clippy and formatting. Existing project and shared-package searches found no reusable UI test-settings guard; std::sync::Mutex already used by this module is sufficient without a dependency.

Validation: the 16-thread UI run failed 7 of 29 tests before the fix. After the fix, all 29 pass, including 20 consecutive repetitions (580 test executions). All 1248 workspace nextest tests pass with installed PowerShell 7.6.6 selected for the existing installer tests. Workspace Clippy with warnings denied, cargo fmt --check and git diff --check pass. Production code and snapshot expectations are unchanged.
