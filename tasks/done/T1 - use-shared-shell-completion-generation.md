---
id: T1
title: Use shared shell completion generation
status: done
created_at: 1792583218
updated_at: 1792583218
---

Use the local `shell-completions` crate in `packages/crates/rust`. Preserve rtok scripts and ketch package-name completion and installation behavior. Add `cox completions <shell>` without loading configuration, credentials, telemetry or a session.

Execution plan: establish existing completion tests; extract the clap generator and Clink implementation with regression tests into the shared crate; connect consumers through local Cargo paths; update dependency inventories; run focused tests, formatting and clippy. Application-specific installation policies and ketch dynamic package lookup stay with their owners. Done when generation has one owner and the three CLI surfaces pass their checks.

Validation: 11 generator unit tests and six completion integration tests passed. All five generated scripts are byte-identical to the baseline. Formatting and all-target clippy passed. The broader all-target test run stopped at the unchanged PowerShell installer version-prefix test in tests/install_ps1.rs.

Reused `clap_complete` and the existing rtok Clink generator. No replacement library or custom generator was introduced. Local path dependencies resolve in the standard apps/packages layout.
