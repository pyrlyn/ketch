---
id: B76
title: Registry rollback can leave the registry empty
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B76 in the 2026-10-08 findings. `registry.rs` already reported a failed rollback rename, but the live registry was still gone. Done: `replace_dir` copies the aside tree back with `source::local::copy_tree` when the rename back fails; if the copy fails too, the error names all three failures and the aside is kept as the only complete copy.

Status: done (Claude Code / opus-5.5). Test: `registry::tests::a_failed_rename_back_restores_the_previous_registry_by_copy`, which fails with the copy fallback removed. Not covered: the path where the copy also fails, which a unit test cannot force without more injection.
