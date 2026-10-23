---
id: K962
title: Mutation-test the trust-boundary guards
status: open
created_at: 1792603928
updated_at: 1792603928
priority: 3
---

Coverage is measured but nothing shows whether the tests would notice a weakened `safe_member_path`, `sanitize_component`, `validate_repo`, `Manifest::validate`, `hooks::allowed` or `changelog::sanitize`.

Done when a scheduled `cargo-mutants` run scoped to those functions reports no missed mutants, or each gap has become a task.
