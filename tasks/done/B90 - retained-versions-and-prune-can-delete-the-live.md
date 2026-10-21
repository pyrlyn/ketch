---
id: B90
title: Retained versions and `prune` can delete the live store
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: rollback, upgrade back and `prune` never remove `pkg.prefix`, and `ScopedDir` never deletes a restored retained copy. Plan: `retain_replaced` also drops entries matching the new prefix/version; a retained prefix counts as in-place in `place`; `prune` skips `pkg.prefix`.
Model: Claude Code / opus-5.5 · Status: done, merged in #295 · Priority: P1 · Complexity: 4
Check: an end-to-end test of 1.0 → rollback 0.9 → upgrade 1.0 → `prune --keep 1` with links still resolving.
