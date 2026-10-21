---
id: B27
title: "`.dmg`/`.pkg` detected by extension ahead of content"
status: done
created_at: 1792583218
updated_at: 1792583218
---

Both sat first in the macOS extractor list and accepted the file name alone, which `extract/mod.rs` says never happens.

`DmgExtractor` and `PkgExtractor` now detect only by content (`koly` trailer and `xar!` magic). Removed the extension fallback and `has_extension`. Added tests that plain files named `.dmg`/`.pkg` are not claimed, and that content without matching extensions is still claimed.
