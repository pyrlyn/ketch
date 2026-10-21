---
id: B123
title: Relink and rollback restore a stale `.old`
status: in-progress
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

Row B123 in the 2026-10-12 findings. Done: the store is restored only when this call made the swap.

Plan: have `move_into_store` report the swap; `restore_retired_store` uses it. Check: a test with a stale `.old` and a failing link that keeps the live prefix.
