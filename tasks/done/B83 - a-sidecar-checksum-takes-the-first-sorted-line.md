---
id: B83
title: A sidecar checksum takes the first sorted line
status: done
created_at: 1792583854
updated_at: 1792583854
---

A `.sha256` sidecar used to take the alphabetically first hash. It now prefers the line that names the asset, and falls back to a lone entry only when the file has exactly one.

Test: `a_sidecar_prefers_the_named_asset_over_the_alphabetically_first_line`.
