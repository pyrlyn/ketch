---
id: R12
title: Consolidate duplicated helpers
status: done
created_at: 1792583218
updated_at: 1792583218
priority: 3
---

Done. `remove_any` already lives in `fs_kit::tree::remove_any` (R20); the local copies are gone. `Http::has_token` is used by the GitHub source before a draft listing, so it stays. `sibling` is one function in `platform/mod.rs`: the suffix is appended as bytes (a non-UTF-8 name is kept) and a path with no file name becomes `pkg`. The three backends call that. The GZIP/XZ/BZ2/ZIP magics and `looks_like_tar` are `pub(crate)` in `extract/archive.rs`; the local source classifies from them and still does not peek inside a compressed stream.
