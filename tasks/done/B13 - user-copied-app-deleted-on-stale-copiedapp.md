---
id: B13
title: User copied `.app` deleted on stale `CopiedApp`
status: done
created_at: 1792583218
updated_at: 1792583218
---

For a copied bundle any directory at the recorded path still counts as ours, so `unlink`/`uninstall` removes a user's own copy.

`still_placed` for `CopiedApp` now requires `record.target.exists()` in addition to the link being a directory, so stale records cannot authorize `unplace` or `clear_destination` once the store copy is gone. Covered by the directory case in `unplace_leaves_a_file_the_user_put_where_a_link_was` (macOS), `a_recorded_link_is_ours_only_while_the_disk_still_agrees` (unix `is_ours`), and `unplace_refuses_to_delete_a_replaced_copied_app_without_a_store_target` (Windows).
