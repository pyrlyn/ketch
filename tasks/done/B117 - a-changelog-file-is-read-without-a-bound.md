---
id: B117
title: A changelog file is read without a bound
status: done
created_at: 1792583854
updated_at: 1792583854
---

Row B117 in the 2026-10-12 findings. Done: `from_file` reads a regular file only, stops at 4 MiB, and decodes the bytes lossily. `find_file` skips a symlink, so the command falls through to the release notes instead of opening the link.
