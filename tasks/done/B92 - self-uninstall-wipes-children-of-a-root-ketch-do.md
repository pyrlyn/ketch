---
id: B92
title: "`self uninstall` wipes children of a root ketch does not own"
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review. Done: named children are wiped only under a root carrying a ketch marker; otherwise only recorded entries go; home is compared canonicalized and case-folded. Plan: write a marker at `self install`/root creation; `remove_root_at` checks it; fallback removes recorded entries only.
Model: Claude Code / opus-5.5 · Status: done, merged in #307 · Priority: P1 · Complexity: 4
Check: a test with a root holding foreign files in `bin/` and no marker, left untouched.
