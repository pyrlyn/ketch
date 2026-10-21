---
id: B105
title: Windows same-version reinstall refuses its own copied bin
status: done
created_at: 1792583854
updated_at: 1792583854
---

Found by the 2026-10-12 review: ownership of a copied bin was proven against `record.target` after the swap had changed it. On re-check it was already fixed by 13efcf9: `retired_targets` compares bin and extra copies against `<store>.old` after the swap.
Model: Claude Code / opus-5.5 · Status: done 2026-10-12 (already fixed in 13efcf9) · Priority: P2 · Complexity: 3
Check: `placing_a_new_build_into_the_same_store_path_replaces_our_copy` in `platform/windows.rs`.
