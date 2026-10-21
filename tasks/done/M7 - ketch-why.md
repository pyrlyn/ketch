---
id: M7
title: "`ketch why`"
status: done
created_at: 1792583218
updated_at: 1792583218
---

Resolution is inspectable without changing it. `evaluate_assets`, `select_release`, and `list_opts` are the functions install already uses: `ketch why <pkg> [--json]` runs one `Source::resolve` like install, records rejected assets and releases, and never fetches checksum sidecars or runs trust inspection.

The trace names the manifest tier and origin, the source, the selected version, scored and rejected assets, checksum and trust policy, and the final candidate. Secrets, URLs, headers, and bidi overrides stay out. Failure still prints the trace, then exits non-zero.

Tests: unit fixtures in `src/resolve.rs` and `src/source/mod.rs`; binary JSON and text snapshots in `tests/why.rs` for aliases, user-over-registry, registry-over-builtin, prereleases, pinned assets, and no-compatible-asset.
