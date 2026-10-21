---
id: B113
title: Windows-reserved names pass the path guards
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B113 in the 2026-10-12 findings. Done: DOS device names, trailing dot or space and `<>:"|?*` are rejected per component on Windows.

Plan: one check shared by `safe_member_path`, `sanitize_component` and `usable_file_name`. Check: table tests over the reserved names.

Status: done. `windows_unusable_component` is the check: DOS device names (any case, any extension, `COM`/`LPT` 0–9 and the superscript digits), a trailing dot or space, and `<>:"|?*` plus `\` and `:`. `safe_member_path` rejects such a component on Windows. `sanitize_component` rewrites one so it cannot be a device name, and `usable_file_name` rejects a name that rewrite would change. `windows_device_names_and_trailing_dots_are_unusable_there` and the `safe_member_path_for` table run on every host.
