---
id: B113
title: Windows-reserved names pass the path guards
status: in-progress
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

Row B113 in the 2026-10-12 findings. Done: DOS device names, trailing dot or space and `<>:"|?*` are rejected per component on Windows.

Plan: one check shared by `safe_member_path`, `sanitize_component` and `usable_file_name`. Check: table tests over the reserved names.
