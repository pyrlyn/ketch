---
id: B37
title: Plugin `digest.algo` ignored
status: done
created_at: 1792583854
updated_at: 1792583854
---

The wire type carries the algorithm and `docs/PLUGINS.md` never restricted it, but `install::verify_checksum` compared the hex against its own sha256 unconditionally.

`verify_checksum` now uses an inline plugin digest only when `algo` is `sha256`; other algorithms fall through to `checksums()`. `docs/PLUGINS.md` documents the restriction. Regression: `ignores_a_non_sha256_plugin_digest`, `uses_a_sha256_plugin_digest_without_calling_checksums`.
