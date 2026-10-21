---
id: B80
title: A lone `.xz`/`.bz2` file fails as a tarball
status: done
created_at: 1792583218
updated_at: 1792583218
---

Found by the 2026-10-08 cloud review: `TarXzExtractor` claimed any xz stream without peeking for ustar. On re-check it was already fixed by 5152c87: detection peeks the decompressed head, and `XzFileExtractor` and `Bz2FileExtractor` are registered on all three platforms. A lone bz2 has only a detection test.
Model: Claude Code / opus-5.5 · Status: done 2026-10-12 (already fixed in 5152c87) · Priority: P2 · Complexity: 3
Check: the lone `.xz` extraction test in `extract/archive.rs`.
