---
id: K1
title: Track ketch tasks in rtok and check plan.md against them
status: done
created_at: 1792583059
updated_at: 1792584023
assignee: 389d32cb-6b35-47a7-b2a5-bbc8679450e1
---

Tasks live in rtok's disk adapter (tasks/, .rtok.toml prefix K); plan.md, todo.md and done.md stay as the human view. Done: every existing task is migrated to tasks/ with its id kept; a Rust test (tests/tasks.rs, run by nextest in CI) fails when plan.md, todo.md or done.md name a task with no matching tasks/ file, when statuses differ, or when an active task file is missing from plan.md; the checker's own cases are tested; AGENTS.md says new tasks come from rtok task create. Lands after the open PR wave merges, with tasks/ regenerated from the final plan.md.
