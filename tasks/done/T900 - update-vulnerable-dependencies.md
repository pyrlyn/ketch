---
id: T900
title: Update vulnerable dependencies
status: done
created_at: 1792583854
updated_at: 1792583854
---

The creator authorized dependency updates across the audit. Update only vulnerable dependencies and the resolver-required graph without weakening existing guards or advisory policy.

Execution plan: reproduce the advisory audit; apply compatible package-manager updates; review the lockfile diff; run advisory regression checks and the project test/lint/format gates; publish a PR and merge only after required checks pass. No custom dependency implementation is needed. Rollback is a revert of the dependency-update commit.

Validation: rustls 0.23.46 resolves its RustSec advisories; the existing rsa RUSTSEC-2023-0071 has no patched release and remains reported. The complete nextest run passed 1241 of 1242 tests; the sole failure was an unconfigured mise PowerShell shim. Re-running all 7 PowerShell tests with installed PowerShell 7.6.6 passed. Workspace clippy with warnings denied and formatting passed. Existing rustls is reused; no custom implementation or new dependency was added. Regenerated workspace-hack for the resolved graph; hakari generate --diff, manage-deps --dry-run, and verify passed.
