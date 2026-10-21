---
id: B10
title: Registry swap deletes working copy before rename
status: done
created_at: 1792583854
updated_at: 1792583854
---

`swap_in` moved the working copy aside before installing the fresh tree, restored it when the second rename failed, and only then removed the aside — so a failed swap no longer leaves `<root>/registry` missing and concurrent installs still see `registry::exists() == true`. Covered by `a_failed_swap_puts_the_old_registry_back` on Unix.
