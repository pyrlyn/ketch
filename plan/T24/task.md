---
status: in progress
priority: P1
complexity: 3
readiness: 75%
agent: Codex / GPT-6
---

# T24. Fix confirmed bugs and remove avoidable work

Review core, CLI and desktop behavior against the Rust implementation and existing tests.
Fix reproducible defects and optimize unnecessary work while preserving public contracts.

## Execution plan

1. Establish build and test health, inspect state and request handling, and compare relevant Rust paths.
2. Add focused regression tests for confirmed defects and patch the responsible modules.
3. Run affected tests, ktlint and detekt; run the full build where the environment permits.
4. Record evidence, limitations and the pull request in the closing report.

Done means regression tests pass, relevant checks are clean, and each optimization has observable evidence.
