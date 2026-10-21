---
id: B11
title: Payload symlink entry point never discovered
status: done
created_at: 1792583218
updated_at: 1792583218
---

`discover_executables` kept only regular files, so `bin/tool -> ../libexec/realtool` linked `realtool` under its internal name and left `tool` off `PATH`.

`payload_executable_entry` now accepts a symlink whose canonical target is a regular file inside the payload and returns the symlink's own path; `bin/` preference still keys on that entry path. The old lexical `ParentDir` check rejected valid relative targets such as `../libexec/realtool`. Covered by `discover_executables_keeps_a_bin_symlink_under_its_own_path` on Unix (and Windows when symlinks are available).
