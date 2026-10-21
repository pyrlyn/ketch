---
id: B102
title: Windows symlink materialisation can recurse forever
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B102 in the 2026-10-12 findings. Done: a directory link whose target is an ancestor of the link is refused.

Plan: check ancestry in `materialize_symlink_as_copy` and snapshot the entry list before copying. Check: unit tests for `d -> .` and `sub/d -> ..`.

Status: done. `materialize_symlink_as_copy` canonicalises the target and the link's parent and refuses when the parent is inside the target, before any copy. `materialize_symlink_as_copy_refuses_a_link_to_its_own_ancestor` covers `d -> .`, `sub/d -> ..`, `sub/d -> .` and `sub/d -> ../sub`, and asserts nothing was written.
