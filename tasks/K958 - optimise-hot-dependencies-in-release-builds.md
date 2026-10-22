---
id: K958
title: Optimise hot dependencies in release builds
status: open
created_at: 1792603927
updated_at: 1792603927
priority: 3
---

`[profile.dist]` inherits `dev` on purpose (#279), so released binaries hash, decompress and verify every download unoptimised; the cost is not measured.

Done when hash-plus-extract time of a large asset and tarball size are measured, and, with the creator's OK, per-package `opt-level` overrides apply to sha2, flate2, lzma-rust2, bzip2, zip, pgp and sigstore while ketch's own crates keep debug info.
