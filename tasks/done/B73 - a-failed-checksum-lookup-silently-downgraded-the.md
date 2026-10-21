---
id: B73
title: A failed checksum lookup silently downgraded the install to trust-on-first-use
status: done
created_at: 1792583218
updated_at: 1792583218
---

`install.rs` `verify_checksum` discarded every `Source::checksums` error to a debug line and an empty map, so a release that does publish a checksum was installed as `checksum_verified: false` with nothing the user could see — a proxy eating the sidecar request (api.github.com vs the CDN serving the asset) forced first-use recording while README:76-78 advertised the check. Found by the 2026-10-07 audit. `Ok(empty)` stays the only "publishes no checksum" answer; an `Err` now emits a user-visible `report.warn` naming the asset and the error while the install still fails open (unless `--require-checksums`). Two tests pin the split: a failing lookup warns exactly once, a clean absence stays quiet.
Model: ZCode / GLM-5.3 · Status: done 2026-10-07 · Priority: P2 · Complexity: 1 · Files: `crates/ketch-core/src/install.rs`
Check: `cargo test -p ketch-core --lib install::tests::a_` — 11 passed, including the two new tests.
