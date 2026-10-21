---
id: R14
title: Delete the stale self-update patch script
status: done
created_at: 1792583854
updated_at: 1792583854
---

`scripts/b32_patch.py` opened `src/self_update.rs` after the core move, and nothing referenced it. The script is gone.
