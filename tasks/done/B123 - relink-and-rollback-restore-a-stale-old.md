---
id: B123
title: Relink and rollback restore a stale `.old`
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B123 in the 2026-10-12 findings. Done: the store is restored only when this call made the swap. `move_into_store` reports whether it retired a tree, and `restore_retired_store` runs only then. A stale `.old` left by an earlier interrupted swap stays aside when relink fails: `a_failed_relink_keeps_the_live_prefix_over_a_stale_old_copy` on macOS and on Linux.
