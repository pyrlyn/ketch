---
id: B45
title: Breaking-change reminder skipped by `!` before the colon
status: done
created_at: 1792583854
updated_at: 1792583854
---

`case "${subject%%:*}" in *!*) exit 0` accepted `feat(api!): …`, which commitlint does not treat as a breaking marker.

The hook now skips the CLI-surface reminder only when the header ends with `!` (`feat!:` / `feat(scope)!:`), matching commitlint. Added `tests/commit-msg-breaking.sh` and wired it into `just lint-commits`.
