---
id: B30
title: "`find_mount_point` takes the first mount"
status: done
created_at: 1792583218
updated_at: 1792583218
---

`.find` contradicted its own doc comment; a DMG with a helper volume mounted first gave `copy_volume` the wrong volume.

`find_mount_point` now keeps the last mounted directory from `hdiutil attach` output. Regression: `prefers_the_last_mount_when_hdiutil_lists_multiple_volumes`.
