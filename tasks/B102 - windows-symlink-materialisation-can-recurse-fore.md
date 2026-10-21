---
id: B102
title: Windows symlink materialisation can recurse forever
status: in-progress
created_at: 1792583218
updated_at: 1792583218
---

Row B102 in the 2026-10-12 findings. Done: a directory link whose target is an ancestor of the link is refused.

Plan: check ancestry in `materialize_symlink_as_copy` and snapshot the entry list before copying. Check: unit tests for `d -> .` and `sub/d -> ..`.
