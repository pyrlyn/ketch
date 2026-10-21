---
id: K909
title: Reduce plan.md to the active tasks
status: open
created_at: 1792603861
updated_at: 1792603862
priority: 1
---

After the move to rtok `plan.md` is mostly history: about 65 `fixed` rows in two review tables, finished audit lists, empty "Plan 2026-09-27" sections, a dangling "Plan:" and the finished D12 still open in `tasks/`. #465 merged with its own `tests/tasks.rs` red (finished rows left in the table).

Done when `plan.md` holds the header, the task table and the active cards, the review tables and audit notes live in `done.md` or a maintainer doc, D12 is closed with the creator's OK, and `cargo test --test tasks` passes.
